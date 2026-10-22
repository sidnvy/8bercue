package dev.sid.fiveberinspector;

import android.os.Process;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only capability-based reader discovery. Ambiguity never selects a default slot. */
public final class FiveberReaderSelector {
    private static final String ISDR_AID = "A0000005591010FFFFFFFF8900000100";
    static boolean isKnownCardHash(String digest){return ReaderAdmission.known(digest);}
    static String knownCardLabel(String digest){return ReaderAdmission.label(digest);}
    private static final byte[] GET_EID = {(byte)0xBF,0x3E,3,0x5C,1,0x5A};
    private static final int MAX_RESPONSE_BYTES = 256;
    private static final int MAX_FRAMES = 6;

    public static final class Target {
        public final String reader;
        public final String sha256Prefix;
        public final String identityHash;
        public final String eidMasked;
        /** GSMA decimal EID (32 digits). Stays in the Shell service; the app receives it only for explicit copy. */
        public final String eidDecimal;
        private Target(String reader,String prefix,String masked,String decimal) {
            this.reader=reader;this.identityHash=prefix.toLowerCase(Locale.ROOT);this.sha256Prefix=prefix.substring(0,16);this.eidMasked=masked;this.eidDecimal=decimal;
        }
    }

    private static final ThreadLocal<CompatibilityReport> REPORT=new ThreadLocal<>();
    static CompatibilityReport compatibilityReport(){return REPORT.get();}
    static void clearCompatibilityReport(){REPORT.remove();}
    private FiveberReaderSelector() {}
    private static String upperHex(byte[] data) {
        StringBuilder sb=new StringBuilder(data.length*2);
        for(byte b:data) sb.append(String.format(Locale.ROOT,"%02X",b&255));
        return sb.toString();
    }
    private static byte[] fromHex(String str) {
        if(str==null || (str.length()%2)!=0)throw new SecurityException("Bad AID");
        byte[] r=new byte[str.length()/2];
        for(int i=0;i<r.length;i++)r[i]=(byte)Integer.parseInt(str.substring(2*i,2*i+2),16);
        return r;
    }
    private static Throwable unwrap(Throwable e) {
        while(e instanceof InvocationTargetException &&
                ((InvocationTargetException)e).getTargetException()!=null)
            e=((InvocationTargetException)e).getTargetException();
        return e;
    }
    private static byte[] readEid(SecureElementTransport hal,int ch) throws Exception {
        byte[] cmd = new byte[5+GET_EID.length];
        cmd[0]=(byte)(0x80|ch);cmd[1]=(byte)0xE2;cmd[2]=(byte)0x91;
        cmd[3]=0;cmd[4]=(byte)GET_EID.length;
        System.arraycopy(GET_EID,0,cmd,5,GET_EID.length);
        java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();
        for(int i=0;i<MAX_FRAMES;i++) {
            byte[] reply=hal.transmit(cmd);
            if(reply==null || reply.length<2 || body.size()+reply.length>MAX_RESPONSE_BYTES)
                throw new SecurityException("Unexpected GetEID reply length");
            body.write(reply,0,reply.length-2);
            int sw1=reply[reply.length-2]&255, sw2=reply[reply.length-1]&255;
            if(sw1==0x90&&sw2==0)break;
            if(sw1!=0x61 || i==MAX_FRAMES-1)
                throw new SecurityException("GetEID status/continuation rejected");
            cmd=new byte[]{(byte)(0x80|ch),(byte)0xC0,0,0,(byte)sw2};
        }
        byte[] data=body.toByteArray();
        if(data.length!=21 ||
            (data[0]&255)!=0xBF || (data[1]&255)!=0x3E ||
            (data[2]&255)!=0x12 || (data[3]&255)!=0x5A ||
            (data[4]&255)!=0x10)
            throw new SecurityException("Invalid BER eUICC GetEID response");
        return Arrays.copyOfRange(data,5,21);
    }
    private static String digestPrefix(byte[] eid) throws Exception {
        byte[] h=MessageDigest.getInstance("SHA-256").digest(eid);
        return upperHex(h);
    }
    static String eidDecimalDigits(byte[] eid) {
        if(eid==null||eid.length!=16)throw new SecurityException("Invalid EID length");
        StringBuilder decimal=new StringBuilder(32);
        for(byte v:eid){int high=(v>>>4)&15,low=v&15;if(high>9||low>9)throw new SecurityException("Invalid EID digits");decimal.append(high).append(low);}
        return decimal.toString();
    }
    private static Target inspectOne(String reader,CompatibilityReport.Backend backend,CompatibilityReport report) throws Exception {
        SecureElementTransport hal=null;int channel=-1;Target found=null;
        CompatibilityReport.Stage phase=CompatibilityReport.Stage.SERVICE_LOOKUP;
        SecureElementFailure.Reason failure=null;
        try {
            hal=SystemHalAdapter.connect(reader);
            phase=CompatibilityReport.Stage.CARD_PRESENCE;
            if(!hal.isCardPresent())failure=SecureElementFailure.Reason.NO_CARD;
            else {
                phase=CompatibilityReport.Stage.MANAGEMENT_CHANNEL;
                channel=hal.openLogicalChannel(fromHex(ISDR_AID),(byte)0);
                if(channel<1||channel>3)throw new SecureElementFailure(SecureElementFailure.Reason.APPLET_UNAVAILABLE);
                phase=CompatibilityReport.Stage.EID_READ;
                byte[] eid=readEid(hal,channel);String digest=digestPrefix(eid);
                if(!ReaderAdmission.accepts(digest))throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_EID_RESPONSE);
                String digits=upperHex(eid);found=new Target(reader,digest,"…"+digits.substring(digits.length()-4),eidDecimalDigits(eid));
            }
        }catch(Exception error) {
            Throwable cause=unwrap(error);SecureElementFailure typed=SecureElementFailure.find(error);
            if(typed!=null)failure=typed.reason;
            else if(cause instanceof ClassNotFoundException||cause instanceof NoSuchMethodException||cause instanceof NoSuchFieldException)
                failure=SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE;
            else if(phase==CompatibilityReport.Stage.EID_READ)failure=SecureElementFailure.Reason.INVALID_EID_RESPONSE;
            else if(cause instanceof SecurityException)failure=SecureElementFailure.Reason.ACCESS_DENIED;
            else if(phase==CompatibilityReport.Stage.MANAGEMENT_CHANNEL)failure=SecureElementFailure.Reason.APPLET_UNAVAILABLE;
            else failure=SecureElementFailure.Reason.SERVICE_UNREACHABLE;
            Log.w("EightberReaderPilot","reader="+reader+" phase="+phase+" code="+failure);
        }finally{
            if(channel>0&&hal!=null)try{hal.closeChannel(channel);}
            catch(Exception cleanup){failure=SecureElementFailure.Reason.CHANNEL_CLEANUP_FAILED;phase=CompatibilityReport.Stage.CHANNEL_CLOSE;}
        }
        report.record(backend,reader,phase,failure);
        if(failure!=null)throw new SecureElementFailure(failure);
        return found;
    }

    // The only automatic card commands are SELECT ISD-R, GetEID and bounded GET RESPONSE.
    // Every mutation separately verifies caller, intent, current inventory and full identity.
    public static Target discover() throws Exception {return discoverInternal();}
    public static Target discoverKnownCardReadOnly() throws Exception {return discoverInternal();}

    private static volatile Target explicitSelection;
    private static CompatibilityReport.Backend backendOf(String reader){
        return reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX)?CompatibilityReport.Backend.TELEPHONY:
            reader.startsWith(ReaderDescriptor.HIDL_PREFIX)?CompatibilityReport.Backend.HAL_HIDL:
            reader.startsWith(ReaderDescriptor.OMAPI_PREFIX)?CompatibilityReport.Backend.OMAPI:CompatibilityReport.Backend.HAL_AIDL;
    }
    static void clearExplicitSelection(){explicitSelection=null;}
    static Target selectExplicitly(Target expected)throws Exception {
        if(Process.myUid()!=Process.SHELL_UID)throw new SecurityException("Shell required");
        Target observed=verifySelected(expected);explicitSelection=observed;return observed;
    }
    /** Re-open the pinned reader and GetEID without rescanning every backend. */
    public static Target verifySelectedCard(Target expected) throws Exception {
        if(Process.myUid()!=Process.SHELL_UID)throw new SecurityException("Requires authorized ADB Shell UID2000");
        return verifySelected(expected);
    }
    private static Target verifySelected(Target expected)throws Exception {
        CompatibilityReport report=new CompatibilityReport();REPORT.set(report);
        CompatibilityReport.Backend backend=backendOf(expected.reader);
        try {
            if(!SystemHalAdapter.readers(backend).contains(expected.reader))throw new SecureElementFailure(SecureElementFailure.Reason.SELECTED_CARD_CHANGED);
            Target observed=inspectOne(expected.reader,backend,report);
            if(!observed.identityHash.equals(expected.identityHash))throw new SecureElementFailure(SecureElementFailure.Reason.SELECTED_CARD_CHANGED);
            report.selected(backend,observed.reader);return observed;
        }catch(Exception error){
            SecureElementFailure failure=typed(error);
            report.record(backend,expected.reader,CompatibilityReport.Stage.SELECTION,failure.reason);
            if(failure.reason==SecureElementFailure.Reason.CHANNEL_CLEANUP_FAILED&&CardTransactionLock.isActive())CardTransactionLock.quarantine();
            throw failure; // Never fall back from a lost or changed explicit selection.
        }
    }
    private static Target discoverInternal() throws Exception {
        if(Process.myUid()!=Process.SHELL_UID)throw new SecurityException("Requires authorized ADB Shell UID2000");
        Target selected=explicitSelection;if(selected!=null)return verifySelected(selected);
        List<Target> matches=discoverChoices();CompatibilityReport report=REPORT.get();
        if(matches.size()!=1){report.record(backendOf(matches.get(0).reader),"",CompatibilityReport.Stage.SELECTION,SecureElementFailure.Reason.AMBIGUOUS_EUICC);throw new SecureElementFailure(SecureElementFailure.Reason.AMBIGUOUS_EUICC);}
        Target target=matches.get(0);report.selected(backendOf(target.reader),target.reader);return target;
    }
    /** Read-only choices; does not alter the active selection or admit a mutation. */
    static List<Target> discoverChoices()throws Exception {
        if(Process.myUid()!=Process.SHELL_UID)throw new SecurityException("Requires authorized ADB Shell UID2000");
        CompatibilityReport report=new CompatibilityReport();REPORT.set(report);
        SecureElementFailure best=null;
        for(CompatibilityReport.Backend backend:CompatibilityReport.Backend.values()){
            List<String> readers;
            try {
                readers=SystemHalAdapter.readers(backend);
                if(readers.isEmpty())throw new SecureElementFailure(SecureElementFailure.Reason.NO_SERVICE);
            }catch(Exception error){
                SecureElementFailure failure=typed(error);
                report.record(backend,"",CompatibilityReport.Stage.ENUMERATION,failure.reason);
                if(failure.reason==SecureElementFailure.Reason.INVALID_READER_INVENTORY)throw failure;
                best=prefer(best,failure);continue;
            }
            List<Target> matches=new ArrayList<>();
            for(String reader:readers)try{Target found=inspectOne(reader,backend,report);if(found!=null)matches.add(found);}
            catch(SecureElementFailure failure){
                if(failure.reason==SecureElementFailure.Reason.CHANNEL_CLEANUP_FAILED){if(CardTransactionLock.isActive())CardTransactionLock.quarantine();throw failure;}
                best=prefer(best,failure);
            }
            if(matches.size()>16)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
            if(!matches.isEmpty())return java.util.Collections.unmodifiableList(matches);
        }
        throw best==null?new SecureElementFailure(SecureElementFailure.Reason.NO_READABLE_EUICC):best;
    }
    private static SecureElementFailure typed(Exception error){
        SecureElementFailure failure=SecureElementFailure.find(error);if(failure!=null)return failure;
        Throwable cause=unwrap(error);
        if(cause instanceof ClassNotFoundException||cause instanceof NoSuchMethodException||cause instanceof NoSuchFieldException)
            return new SecureElementFailure(SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE);
        if(cause instanceof SecurityException)return new SecureElementFailure(SecureElementFailure.Reason.ACCESS_DENIED);
        return new SecureElementFailure(SecureElementFailure.Reason.SERVICE_UNREACHABLE);
    }
    private static SecureElementFailure prefer(SecureElementFailure before,SecureElementFailure now){
        if(before==null||priority(now.reason)>priority(before.reason))return now;return before;
    }
    private static int priority(SecureElementFailure.Reason reason){return switch(reason){
        case ACCESS_DENIED -> 5;case INVALID_EID_RESPONSE -> 4;case APPLET_UNAVAILABLE -> 3;
        case SYSTEM_INTERFACE_UNAVAILABLE,SERVICE_UNREACHABLE -> 2;case NO_CARD -> 1;default -> 0;
    };}
}
