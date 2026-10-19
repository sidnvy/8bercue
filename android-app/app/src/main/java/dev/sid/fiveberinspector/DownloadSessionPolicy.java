package dev.sid.fiveberinspector;

/**
 * Pure Java, testable gate for a SINGLE user-approved GSMA download.
 *
 * No device IO: this enforces the exact ES10b stage progression and
 * hard-blocks ES10c enable/disable/delete/reset/nickname commands.
 * A future actual download can use this independently audited policy.
 */
public final class DownloadSessionPolicy {
    public enum Stage {
        CHALLENGE, INFO, INITIATE_HTTP, AUTHENTICATE_SERVER,
        AUTHENTICATE_CLIENT_HTTP, PREPARE_DOWNLOAD, BOUND_HTTP,
        LOAD_PACKAGE, CANCEL_CARD, CANCEL_HTTP, CANCELLED, ABORTED
    }
    private Stage stage = Stage.CHALLENGE;
    private boolean pendingGetResponse;
    private boolean fragmented;
    private int nextSeq;
    private int commandTag = -1;
    private boolean lastSegment;
    private int frameCount;
    private int totalCommandBytes;
    private int installCommands;
    private int httpCount;
    private static final int MAX_FRAMES = 18000;
    private static final int MAX_COMMAND_BYTES = 2 * 1024 * 1024;
    private static final int MAX_HTTP = 12;

    private byte[] transactionId;
    private boolean authenticated, cancellationStarted;
    public void bindTransaction(String hex) {
        if(stage!=Stage.AUTHENTICATE_SERVER || transactionId!=null || hex==null || !hex.matches("(?i)[0-9a-f]{2,64}") || (hex.length()&1)!=0)
            throw new SecurityException("Invalid download transaction binding");
        transactionId=new byte[hex.length()/2];
        for(int i=0;i<transactionId.length;i++)transactionId[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
    }
    public void authorizeCancellationHttp(String hex) {
        if(stage!=Stage.CANCEL_HTTP || transactionId==null || hex==null || hex.length()!=transactionId.length*2)
            reject("Unbound cancellation HTTP");
        for(int i=0;i<transactionId.length;i++)
            if(Character.digit(hex.charAt(i*2),16)<0 || Character.digit(hex.charAt(i*2+1),16)<0
                || (transactionId[i]&255)!=Character.digit(hex.charAt(i*2),16)*16+Character.digit(hex.charAt(i*2+1),16))
                reject("Cancellation transaction mismatch");
    }
    private void authorizeCancellation(byte[] a) {
        // Only lpac's single-frame CancelSession for this authenticated transaction.
        // This cannot target a profile, reset the card, or start another download.
        if(!authenticated || cancellationStarted || transactionId==null || fragmented || pendingGetResponse
            || (a[2]&255)!=0x91 || a.length!=13+transactionId.length || (a[7]&255)!=5+transactionId.length
            || (a[8]&255)!=0x80 || (a[9]&255)!=transactionId.length)
            reject("Unbound cancellation APDU");
        for(int i=0;i<transactionId.length;i++)if(a[10+i]!=transactionId[i])reject("Cancellation transaction mismatch");
        int end=10+transactionId.length;
        if((a[end]&255)!=0x81 || a[end+1]!=1 || a[end+2]!=0)reject("Unexpected cancellation reason");
        cancellationStarted=true;stage=Stage.CANCEL_CARD;
    }

    public Stage stage() { return stage; }
    public int installCommands() { return installCommands; }
    public int commandFrames() { return frameCount; }

    private void reject(String why) {
        stage = Stage.ABORTED;
        throw new SecurityException(why);
    }
    public boolean isGetResponse(byte[] bytes, int channel) {
        return bytes != null && bytes.length == 5 &&
            (bytes[0] & 255) == (0x80 | channel) &&
            (bytes[1] & 255) == 0xC0 &&
            bytes[2] == 0 && bytes[3] == 0;
    }

    private static int tag(byte[] bytes) {
        if (bytes.length < 7) throw new SecurityException("ES10 command too short");
        int first = bytes[5] & 255;
        if (first == 0xBF) return (first << 8) | (bytes[6] & 255);
        return first;
    }
    private boolean isPackageTag(int tag) {
        // Payload must come from the verified and bound SM-DP+ transaction.
        // These tags denote the BPP envelope, metadata and protected chunks.
        return tag == 0xBF36 || tag == 0xBF23 ||
            tag == 0xA0 || tag == 0xA1 || tag == 0xA2 || tag == 0xA3 ||
            tag == 0x86 || tag == 0x87 || tag == 0x88 || tag == 0x89 ||
            tag == 0x90 || tag == 0x91 || tag == 0x92 || tag == 0x93;
    }

    public void authorize(byte[] apdu, int channel) {
        if (stage == Stage.CANCELLED) reject("Download already cancelled");
        if (apdu == null || apdu.length < 5 || apdu.length > 261)
            reject("APDU length outside protocol bounds");
        if (channel < 1 || channel > 3 || (apdu[0] & 255) != (0x80 | channel))
            reject("Unexpected HAL logical channel");
        if (++frameCount > MAX_FRAMES) reject("Download APDU frame budget exceeded");
        totalCommandBytes += apdu.length;
        if (totalCommandBytes > MAX_COMMAND_BYTES)
            reject("Download exceeds APDU transfer budget");

        if (isGetResponse(apdu, channel)) {
            if (!pendingGetResponse) reject("Unsolicited GET RESPONSE");
            return;
        }
        if (pendingGetResponse) reject("Missing response continuation");
        if ((apdu[1] & 255) != 0xE2 || !((apdu[2] & 255) == 0x11 ||
              (apdu[2] & 255) == 0x91)) reject("Non-ES10 APDU command");
        if ((apdu[4] & 255) != apdu.length - 5)
            reject("ES10 APDU data length mismatch");

        int sequence = apdu[3] & 255;
        if (!fragmented) {
            if (sequence != 0) reject("Expected first ES10 command fragment");
            commandTag = tag(apdu);
            if(commandTag==0xBF41)authorizeCancellation(apdu);
            switch (stage) {
                case CANCEL_CARD: if(commandTag!=0xBF41)reject("Only bound cancellation allowed");break;
                case CHALLENGE:
                    if (commandTag != 0xBF2E) reject("Expected GetEuiccChallenge only");
                    break;
                case INFO:
                    if (commandTag != 0xBF20) reject("Expected GetEuiccInfo1 only");
                    break;
                case AUTHENTICATE_SERVER:
                    if (commandTag != 0xBF38) reject("Expected AuthenticateServer only");
                    break;
                case PREPARE_DOWNLOAD:
                    if (commandTag != 0xBF21) reject("Expected PrepareDownload only");
                    break;
                case LOAD_PACKAGE:
                    if (!isPackageTag(commandTag)) reject("BPP chunk tag not allowed");
                    break;
                default:
                    reject("No APDU allowed at this protocol stage");
            }
            nextSeq = 0;
        } else if (sequence != nextSeq) {
            reject("Out-of-order fragmented ES10 command");
        }
        nextSeq = (sequence + 1) & 255;
        lastSegment = (apdu[2] & 255) == 0x91;
        fragmented = !lastSegment;
    }

    public void acceptResponse(byte[] command, int channel, byte[] response) {
        if (response == null || response.length < 2)
            reject("Malformed card response");
        int sw1 = response[response.length - 2] & 255;
        int sw2 = response[response.length - 1] & 255;
        if (sw1 == 0x61) {
            pendingGetResponse = true;
            return;
        }
        if (sw1 != 0x90 || sw2 != 0x00) {
            stage = Stage.ABORTED;
            return; // hand SW to native lpac; no retry from our transport
        }
        if (isGetResponse(command, channel)) {
            if (!pendingGetResponse) reject("Unexpected completion");
            pendingGetResponse = false;
        }
        if (lastSegment) {
            switch (stage) {
                case CHALLENGE: stage = Stage.INFO; break;
                case INFO: stage = Stage.INITIATE_HTTP; break;
                case AUTHENTICATE_SERVER: authenticated = true; stage = Stage.AUTHENTICATE_CLIENT_HTTP; break;
                case CANCEL_CARD: stage = Stage.CANCEL_HTTP; break;
                case PREPARE_DOWNLOAD: stage = Stage.BOUND_HTTP; break;
                case LOAD_PACKAGE: installCommands++; break;
                default: break;
            }
        }
    }

    public void authorizeHttp(String operation) {
        boolean allowed=("initiateAuthentication".equals(operation) && stage==Stage.INITIATE_HTTP)
            || ("authenticateClient".equals(operation) && stage==Stage.AUTHENTICATE_CLIENT_HTTP)
            || ("getBoundProfilePackage".equals(operation) && stage==Stage.BOUND_HTTP)
            || ("cancelSession".equals(operation) && stage==Stage.CANCEL_HTTP);
        if(!allowed || fragmented || pendingGetResponse)reject("Unexpected ES9+ request stage");
    }

    public void httpCompleted(String operation, int status) {
        if (++httpCount > MAX_HTTP) reject("HTTP request budget exceeded");
        if("cancelSession".equals(operation) && stage==Stage.CANCEL_HTTP) {stage=Stage.CANCELLED;return;}
        if (status < 200 || status >= 300) {
            stage = Stage.ABORTED;
            return;
        }
        if ("initiateAuthentication".equals(operation) && stage == Stage.INITIATE_HTTP) {
            stage = Stage.AUTHENTICATE_SERVER;
        } else if ("authenticateClient".equals(operation)
                && stage == Stage.AUTHENTICATE_CLIENT_HTTP) {
            stage = Stage.PREPARE_DOWNLOAD;
        } else if ("getBoundProfilePackage".equals(operation)
                && stage == Stage.BOUND_HTTP) {
            stage = Stage.LOAD_PACKAGE;
        } else {
            reject("Unexpected ES9+ HTTP stage or operation");
        }
    }
}
