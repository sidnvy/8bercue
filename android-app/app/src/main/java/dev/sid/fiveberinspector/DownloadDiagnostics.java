package dev.sid.fiveberinspector;
import java.util.Properties;
/** Fixed diagnostic vocabulary only. No messages, URLs, credentials or card payloads. */
final class DownloadDiagnostics {
    static final String PHASES="LAUNCH|CARD_(CHALLENGE|INFO|INITIATE_HTTP|AUTHENTICATE_SERVER|AUTHENTICATE_CLIENT_HTTP|PREPARE_DOWNLOAD|BOUND_HTTP|LOAD_PACKAGE|CANCEL_CARD|CANCEL_HTTP|CANCELLED|ABORTED)|INITIATE_AUTHENTICATION|AUTHENTICATE_CLIENT|BOUND_PROFILE_PACKAGE|CANCEL_SESSION|NATIVE_EXIT";
    private String phase="LAUNCH",failure="NONE",nativeCode="not_reported";
    static final String SERVER_STATUS="Executed-Success|Executed-WithWarning|Failed|Expired|not_reported";
    static final String STATUS_CODE="not_reported|[0-9]{1,3}(\\.[0-9]{1,3}){1,3}";
    private String serverStatus="not_reported",subjectCode="not_reported",reasonCode="not_reported";
    private int httpStatus;
    private boolean nativeStopped=true;
    synchronized void phase(String value){if(!value.matches(PHASES))throw new IllegalArgumentException("Invalid diagnostic phase");if(failure.equals("NONE"))phase=value;}
    synchronized void http(String endpoint){
        switch(endpoint){
            case "initiateAuthentication":phase("INITIATE_AUTHENTICATION");break;
            case "authenticateClient":phase("AUTHENTICATE_CLIENT");break;
            case "getBoundProfilePackage":phase("BOUND_PROFILE_PACKAGE");break;
            case "cancelSession":phase("CANCEL_SESSION");break;
            default:throw new IllegalArgumentException("Invalid diagnostic endpoint");
        }
    }
    synchronized void httpStatus(int status){if(!failure.equals("NONE"))return;httpStatus=status>=100&&status<=599?status:0;}
    synchronized void server(String status,String subject,String reason) {
        if(!failure.equals("NONE"))return;
        serverStatus=status!=null&&status.matches(SERVER_STATUS)?status:"not_reported";
        subjectCode=subject!=null&&subject.matches(STATUS_CODE)?subject:"not_reported";
        reasonCode=reason!=null&&reason.matches(STATUS_CODE)?reason:"not_reported";
        if(serverStatus.equals("Failed")||serverStatus.equals("Expired"))failure="SERVER_REJECTED";
    }
    synchronized void nativeCode(int code){nativeCode=code>=-9999&&code<=9999?Integer.toString(code):"not_reported";if(code!=0&&failure.equals("NONE"))failure="NATIVE_REJECTED";}
    // lpac reports the original failed operation only AFTER cancellation cleanup.
    // Recover its bounded phase without retaining native messages or overriding an earlier failure.
    synchronized void nativeResult(int code,String operation) {
        if(code!=0 && failure.equals("NONE")) {
            String original=null;
            if(operation!=null)switch(operation) {
                case "es10b_get_euicc_challenge_and_info":original="CARD_CHALLENGE";break;
                case "es9p_initiate_authentication":original="INITIATE_AUTHENTICATION";break;
                case "es10b_authenticate_server":original="CARD_AUTHENTICATE_SERVER";break;
                case "es9p_authenticate_client":original="AUTHENTICATE_CLIENT";break;
                case "es10b_prepare_download":original="CARD_PREPARE_DOWNLOAD";break;
                case "es9p_get_bound_profile_package":original="BOUND_PROFILE_PACKAGE";break;
                case "es10b_load_bound_profile_package":original="CARD_LOAD_PACKAGE";break;
                default:break;
            }
            if(original!=null)phase(original);
        }
        nativeCode(code);
    }
    synchronized void nativeStarted(){nativeStopped=false;}
    synchronized void nativeStopped(boolean stopped){nativeStopped=stopped;if(!stopped)failure="NATIVE_STOP_UNCONFIRMED";}
    synchronized boolean nativeStopped(){return nativeStopped;}
    synchronized void failure(Throwable error){
        if(!failure.equals("NONE"))return;
        if(error instanceof javax.net.ssl.SSLException)failure="TLS";
        else if(error instanceof java.net.UnknownHostException)failure="DNS";
        else if(error instanceof java.net.SocketTimeoutException)failure="NETWORK_TIMEOUT";
        else if(error instanceof InterruptedException)failure="INTERRUPTED";
        else if(error instanceof SecurityException)failure="POLICY";
        else if(error instanceof java.io.IOException)failure="IO";
        else failure="PROTOCOL";
    }
    synchronized Properties snapshot(boolean resultPresent,boolean workerStopped){
        Properties p=new Properties();p.setProperty("downloadPhase",phase);
        p.setProperty("downloadFailure",failure.equals("NONE")&&!resultPresent?"WORKER_RESULT_UNAVAILABLE":failure);
        p.setProperty("downloadHttpStatus",Integer.toString(httpStatus));p.setProperty("downloadNativeCode",nativeCode);
        p.setProperty("downloadWorkerStopped",Boolean.toString(workerStopped));p.setProperty("downloadNativeStopped",Boolean.toString(nativeStopped));
        p.setProperty("downloadServerStatus",serverStatus);p.setProperty("downloadSubjectCode",subjectCode);p.setProperty("downloadReasonCode",reasonCode);return p;
    }
}
