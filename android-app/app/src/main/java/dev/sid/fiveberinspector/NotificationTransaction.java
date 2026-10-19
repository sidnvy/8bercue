package dev.sid.fiveberinspector;
import java.util.Properties;
/** Durable ordering around injected card/network IO; fixture tests exercise this production flow. */
final class NotificationTransaction {
  interface Card { byte[] retrieve() throws Exception;byte[] remove() throws Exception; }
  interface Sender { boolean send(NotificationCodec.Pending notification) throws Exception; }
  static boolean acknowledged(int status,boolean empty,String executionStatus) {
    return (status==204&&empty)||(status==200&&!empty&&"Executed-Success".equals(executionStatus));
  }
  static void run(NotificationJournal journal,Properties record,String iccid,Card card,Sender sender,boolean cleanupOnly) throws Exception {
    try {
      if(cleanupOnly&&!NotificationJournal.cleanup(record))throw new SecurityException("Delivery was not acknowledged");
      DownloadWorkerSupervisor.checkCancelled();
      byte[] retrieved=card.retrieve();
      if(cleanupOnly&&"removing".equals(record.getProperty("status"))&&NotificationCodec.absent(retrieved)) {
        journal.advance(record,"delivered");return;
      }
      NotificationCodec.Pending pending=NotificationCodec.pending(retrieved,Long.parseLong(record.getProperty("sequence")),iccid,record.getProperty("host"));
      DownloadWorkerSupervisor.checkCancelled();
      if(!cleanupOnly) {
        journal.advance(record,"posting");
        if(!sender.send(pending))throw new SecurityException("Operator acknowledgment unavailable");
        // Persist acknowledgment even if interrupted just after the server reply. No card removal on cancellation.
        boolean interrupted=Thread.interrupted();
        try {journal.advance(record,"acknowledged");}
        finally {if(interrupted)Thread.currentThread().interrupt();}
      }
      DownloadWorkerSupervisor.checkCancelled();journal.advance(record,"removing");
      if(!NotificationCodec.removed(card.remove()))throw new SecurityException("Notification removal not confirmed");
      journal.advance(record,"delivered");
    } catch(NotificationCodec.RecipientMismatch mismatch) {
      if("prepared".equals(record.getProperty("status")))journal.advance(record,"blocked_recipient");throw mismatch;
    } catch(Exception failure) {
      if("prepared".equals(record.getProperty("status")))journal.advance(record,"unavailable");throw failure;
    }
  }
  private NotificationTransaction(){}
}
