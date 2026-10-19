package dev.sid.fiveberinspector;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Fixed argv native operation runner with bounded protocol and 60-second watchdog. */
public final class PhoneProfileLifecycle {
 private static final String ROOT="/data/local/tmp/fiveber-lpac-lab";
 private PhoneProfileLifecycle(){}
 private static String readBounded(BufferedReader in)throws IOException {
  StringBuilder s=new StringBuilder();for(int c;(c=in.read())!=-1;){if(c=='\n')return s.toString();if(s.length()>=65536)throw new IOException("Native frame too large");s.append((char)c);}return s.length()==0?null:s.toString();
 }
 static void execute(ProfileLifecycleGuard.Operation operation,String id,String nickname,String reader,String hash)throws Exception {
   execute(operation,id,nickname,reader,hash,new boolean[1]);
 }
 static void execute(ProfileLifecycleGuard.Operation operation,String id,String nickname,String reader,String hash,boolean[] submitted)throws Exception {
        if(submitted==null||submitted.length!=1)throw new IllegalArgumentException("Submission marker required");submitted[0]=false;
        Process nativeProcess=null;
        java.util.concurrent.ScheduledExecutorService watchdog=null;
        java.util.concurrent.ScheduledFuture<?> deadline=null;
        ScopedLifecycleHalBridge bridge=new ScopedLifecycleHalBridge(operation,id,nickname,reader,hash);
        try {
            String action=operation==ProfileLifecycleGuard.Operation.RENAME?"nickname":operation.name().toLowerCase(java.util.Locale.ROOT);
            ProcessBuilder pb=operation==ProfileLifecycleGuard.Operation.RENAME?new ProcessBuilder(ROOT+"/lpac","profile",action,id,nickname):(operation==ProfileLifecycleGuard.Operation.ENABLE||operation==ProfileLifecycleGuard.Operation.DISABLE)?new ProcessBuilder(ROOT+"/lpac","profile",action,id,"true"):new ProcessBuilder(ROOT+"/lpac","profile",action,id);
            pb.directory(new File(ROOT));
            pb.environment().put("LPAC_APDU","stdio");
            pb.environment().put("LPAC_HTTP","stdio");
            pb.environment().put("LD_LIBRARY_PATH",ROOT);
            pb.environment().put("LPAC_APDU_DEBUG","false");
            pb.environment().put("LPAC_HTTP_DEBUG","false");
            pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
            nativeProcess=pb.start();
            final Process monitored=nativeProcess;
            watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                task->{Thread t=new Thread(task,"5ber-switch-timeout");t.setDaemon(true);return t;});
            deadline=watchdog.schedule(()->monitored.destroyForcibly(),
                60,java.util.concurrent.TimeUnit.SECONDS);
            BufferedReader in=new BufferedReader(new InputStreamReader(
                nativeProcess.getInputStream(),StandardCharsets.UTF_8));
            BufferedWriter out=new BufferedWriter(new OutputStreamWriter(
                nativeProcess.getOutputStream(),StandardCharsets.UTF_8));
            JSONObject result=null;
            for(int i=0;i<24;i++) {
                String line=readBounded(in);
                if(line==null)break;
                if(line.length()>65536)throw new SecurityException("Unexpected native payload");
                JSONObject event=new JSONObject(line);
                String kind=event.optString("type");
                if("apdu".equals(kind)) {
                    JSONObject answer=bridge.handle(event);
                    out.write(answer.toString());
                    out.newLine();out.flush();
                }else if("lpa".equals(kind)) {
                    if(result!=null)throw new SecurityException("Multiple result frames");
                    result=event.getJSONObject("payload");
                }else if("progress".equals(kind)){
                    // Intentionally do not log / forward any native progress data.
                }else{
                    throw new SecurityException("Unexpected native operation during Profile switch");
                }
            }
            if(!nativeProcess.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("Native operation did not exit");
            if(!bridge.wasSubmitted())
                throw new SecurityException("Native lpac did not send the scoped profile operation");
            if(nativeProcess.exitValue()!=0 || result==null || result.optInt("code",-1)!=0)
                throw new IllegalStateException("Card rejected scoped profile operation");
            // Result is independently verified by the transaction engine.
        }finally{
            submitted[0]=bridge.wasSubmitted();
            if(deadline!=null)deadline.cancel(false);
            if(watchdog!=null)watchdog.shutdownNow();
            if(nativeProcess!=null){
                try{nativeProcess.getOutputStream().close();}catch(Exception ignored){}
                if(nativeProcess.isAlive())nativeProcess.destroyForcibly();
                try {
                    if(!nativeProcess.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))CardTransactionLock.quarantine();
                }catch(InterruptedException interrupted){CardTransactionLock.quarantine();Thread.currentThread().interrupt();}
            }
            bridge.close();
        }
    }

}
