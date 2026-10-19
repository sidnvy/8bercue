package dev.sid.fiveberinspector;
import java.util.List;
/** Read-only settling after REFRESH. This helper has no write/rollback capability. */
final class LifecyclePostflight {
  interface Reader {List<ProfileLifecycleGuard.Row> read() throws Exception;}
  interface Pause {void sleep(long millis) throws InterruptedException;}
  static final class Result {
    final boolean verified,unchanged;final int reads;
    Result(boolean verified,boolean unchanged,int reads){this.verified=verified;this.unchanged=unchanged;this.reads=reads;}
  }
  static Result observe(ProfileLifecycleGuard.Operation op,List<ProfileLifecycleGuard.Row> before,String target,String nickname,Reader reader,Pause pause) throws Exception {
    String original=ProfileLifecycleGuard.fingerprint(before);
    long[] delays=(op==ProfileLifecycleGuard.Operation.ENABLE||op==ProfileLifecycleGuard.Operation.DISABLE)?new long[]{0,300,700,1500,2500,4000,6000}:new long[]{0};
    boolean unchanged=false;
    for(int i=0;i<delays.length;i++) {
      DownloadWorkerSupervisor.checkCancelled();if(delays[i]>0)pause.sleep(delays[i]);DownloadWorkerSupervisor.checkCancelled();
      unchanged=false;
      try {
        List<ProfileLifecycleGuard.Row> after=reader.read();DownloadWorkerSupervisor.checkCancelled();
        boolean verified=ProfileLifecycleGuard.exactTransition(op,before,after,target,nickname);
        unchanged=original.equals(ProfileLifecycleGuard.fingerprint(after));
        if(verified)return new Result(true,false,i+1);
      } catch(InterruptedException interrupted){throw interrupted;}
      catch(Exception temporarilyUnavailable) { /* Only another read, never another mutation. */ }
    }
    return new Result(false,unchanged,delays.length);
  }
  private LifecyclePostflight(){}
}
