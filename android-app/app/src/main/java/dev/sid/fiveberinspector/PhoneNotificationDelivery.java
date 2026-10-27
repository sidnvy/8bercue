package dev.sid.fiveberinspector;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One delivery attempt for a freshly verified install; acknowledged cleanup never sends HTTP. */
public final class PhoneNotificationDelivery {
  private static final File DIRECTORY=new File("/data/local/tmp/fiveber-lpac-lab/notifications");
  private static CleanupPlan pendingCleanup;
  static synchronized void discardCleanupPreview(){pendingCleanup=null;}
  private static final class CleanupPlan {
    final String token,id,reader,card,inventory;final long created=SystemClock.elapsedRealtime();
    CleanupPlan(String id,String reader,String card,String inventory){token=OperationTokens.create();this.id=id;this.reader=reader;this.card=card;this.inventory=inventory;}
  }
  private static void requireShell(){if(Process.myUid()!=Process.SHELL_UID)throw new SecurityException("Shell identity required");}
  private static NotificationJournal journal() throws Exception {return new NotificationJournal(DIRECTORY);}
  static String hash(String value) throws Exception {
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:digest)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();
  }
  public static JSONObject status() throws Exception {
    requireShell();CardTransactionLock.acquire();
    try {return status(FiveberReaderSelector.discover().identityHash);}
    finally {CardTransactionLock.release();}
  }
  static JSONObject statusUnderLock(String card)throws Exception {
    requireShell();if(!CardTransactionLock.ownedByCurrentThread())throw new SecurityException("Owned card lock required");
    return status(card);
  }
  private static JSONObject status(String card) throws Exception {
    requireShell();List<Properties> records=journal().recordsForCard(card);Properties selected=null;int count=0;boolean cleanup=false;
    for(Properties record:records) {if(!"delivered".equals(NotificationJournal.outcome(record))){count++;selected=record;}cleanup|=NotificationJournal.cleanup(record);}
    if(selected==null&&!records.isEmpty())selected=records.get(records.size()-1);
    return new JSONObject().put("notificationStatus",selected==null?"none":NotificationJournal.outcome(selected))
        .put("notificationReviewRequired",count>0).put("notificationPendingCount",count)
        .put("notificationCleanupAvailable",cleanup).put("notificationWorkerStopped",!CardTransactionLock.isQuarantined());
  }
  /** Caller retains the card lock from install through exact inventory verification and this attempt. */
  static JSONObject afterInstall(String reader,String card,String host,long sequence,String iccid) {
    try {
      requireShell();if(!CardTransactionLock.isActive()||!EnrolledWritePolicy.isAllowed(card))throw new SecurityException("Notification card not enrolled");
      Properties operation=OperationJournal.forCard(new File("/data/local/tmp/fiveber-rescue-state"),card).read();
      if(!"2".equals(operation.getProperty("schema"))||!"running".equals(operation.getProperty("status"))
          ||!"download".equals(operation.getProperty("operation"))||!card.equals(operation.getProperty("card")))
        throw new SecurityException("Bound download journal required before notification");
      NotificationJournal journal=journal();Properties record=journal.beginBound(card,sequence,host,hash(iccid),operation.getProperty("txId"));
      return attempt(journal,record,reader,card,iccid,false);
    } catch(Exception unavailable) {
      return unavailable(card);
    }
  }
  private static JSONObject unavailable(String card){
    JSONObject result;
    try {result=status(card);}catch(Exception ignored){result=new JSONObject();}
    try {
      result.put("notificationStatus","not_started").put("notificationReviewRequired",true)
          .put("notificationWorkerStopped",!CardTransactionLock.isQuarantined());
      if(!result.has("notificationPendingCount"))result.put("notificationPendingCount",0);
      if(!result.has("notificationCleanupAvailable"))result.put("notificationCleanupAvailable",false);
      if(CardTransactionLock.isQuarantined())result.put("recoveryAction","reconnect_companion_then_verify");
    } catch(JSONException impossible) {throw new IllegalStateException("Notification status unavailable");}
    return result;
  }
  private static String approvedInstallHost(String card,String iccid)throws Exception {
    String host=null;for(Properties record:journal().recordsForCard(card))if(hash(iccid).equals(record.getProperty("profile"))&&!record.containsKey("notificationOperation")&&"delivered".equals(NotificationJournal.outcome(record)))host=ActivationCode.validateHost(record.getProperty("host"));return host;
  }
  static Set<Long> beforeDeletion(String reader,String card,String iccid)throws Exception {
    requireShell();if(approvedInstallHost(card,iccid)==null)return null;
    Set<Long> sequences=new HashSet<>();for(NotificationMetadataInventory.Entry entry:NotificationMetadataInventory.list(reader,card))sequences.add(entry.sequence);return sequences;
  }
  static JSONObject afterDeletion(String reader,String card,String iccid,Set<Long> before){
    if(before==null)return null;try{
      Properties operation=OperationJournal.forCard(new File("/data/local/tmp/fiveber-rescue-state"),card).read();
      requireDeletionBinding(operation,card,iccid,"running");String host=approvedInstallHost(card,iccid);NotificationMetadataInventory.Entry selected=null;
      for(NotificationMetadataInventory.Entry entry:NotificationMetadataInventory.list(reader,card))if(!before.contains(entry.sequence)&&entry.operation==0x10&&entry.iccid.equals(iccid)&&entry.host.equals(host)){if(selected!=null)throw new SecurityException("Ambiguous new deletion notification");selected=entry;}
      if(selected==null)throw new SecurityException("No bound deletion notification");
      return deliverDeletion(reader,card,iccid,host,operation.getProperty("txId"),selected.sequence);
    }catch(Exception failure){return unavailable(card);}
  }
  private static void requireDeletionBinding(Properties operation,String card,String iccid,String phase)throws Exception {
    requireShell();if(!CardTransactionLock.isActive()||!EnrolledWritePolicy.isAllowed(card)||!"2".equals(operation.getProperty("schema"))||!"delete".equals(operation.getProperty("operation"))||!phase.equals(operation.getProperty("status"))||!card.equals(operation.getProperty("card"))||!hash(iccid).equals(operation.getProperty("target")))throw new SecurityException("Deletion transaction binding required");
  }
  private static JSONObject deliverDeletion(String reader,String card,String iccid,String host,String transaction,long sequence)throws Exception {
    Properties record=journal().beginDeletion(card,sequence,host,hash(iccid),transaction);return attempt(journal(),record,reader,card,iccid,false);
  }
  /** Operator repair of one previously verified deletion. Not exposed as a Binder/RPC method. */
  static JSONObject recoverVerifiedDeletion(String reader,String card,String iccid,String transaction,long sequence)throws Exception {
    requireShell();if(transaction==null||!transaction.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new SecurityException("Invalid deletion transaction");
    Properties operation=OperationJournal.forCard(new File("/data/local/tmp/fiveber-rescue-state"),card).readTransaction(transaction);
    requireDeletionBinding(operation,card,iccid,"verified");
    for(CardProfileInventory.Entry entry:CardProfileInventory.list())if(entry.iccid.equals(iccid))throw new SecurityException("Deleted profile still present");
    String host=approvedInstallHost(card,iccid);if(host==null)throw new SecurityException("No prior approved install recipient");boolean found=false;
    for(NotificationMetadataInventory.Entry entry:NotificationMetadataInventory.list(reader,card))if(entry.sequence==sequence&&entry.operation==0x10&&entry.iccid.equals(iccid)&&entry.host.equals(host))found=true;
    if(!found)throw new SecurityException("Deletion notification not bound to approved asset");return deliverDeletion(reader,card,iccid,host,transaction,sequence);
  }
  private static JSONObject attempt(NotificationJournal journal,Properties record,String reader,String card,String iccid,boolean cleanupOnly) throws Exception {
    java.util.concurrent.atomic.AtomicBoolean unsafeChannel=new java.util.concurrent.atomic.AtomicBoolean();
    DownloadWorkerSupervisor.Outcome<Boolean> outcome=DownloadWorkerSupervisor.run(()->{
      try(PortableNotificationHalBridge bridge=new PortableNotificationHalBridge(reader,card,Long.parseLong(record.getProperty("sequence")),unsafeChannel)) {
        NotificationTransaction.run(journal,record,iccid,new NotificationTransaction.Card(){
          public byte[] retrieve() throws Exception{return bridge.retrieve();}
          public byte[] remove() throws Exception{return bridge.removeAfterAcknowledgement();}
        },notification->{
          DownloadWorkerSupervisor.checkCancelled();
          GsmaEs9Transport transport=new GsmaEs9Transport(notification.host);
          String address="https://"+notification.host+"/gsma/rsp2/es9plus/handleNotification";
          byte[] body=new JSONObject().put("pendingNotification",java.util.Base64.getEncoder().encodeToString(notification.signedPayload)).toString().getBytes(StandardCharsets.UTF_8);
          JSONArray headers=new JSONArray().put("User-Agent: gsma-rsp-lpad").put("X-Admin-Protocol: gsma/rsp/v2.2.2").put("Content-Type: application/json");
          GsmaEs9Transport.Reply reply=transport.postEs9(address,body,headers);
          String execution="";
          if(reply.statusCode==200&&reply.data.length>0) {
            JSONObject response=new JSONObject(new String(reply.data,StandardCharsets.UTF_8));
            execution=response.getJSONObject("header").getJSONObject("functionExecutionStatus").getString("status");
          }
          return NotificationTransaction.acknowledged(reply.statusCode,reply.data.length==0,execution);
        },cleanupOnly);return Boolean.TRUE;
      }
    },()->{},60_000L,5_000L);
    if(!outcome.workerStopped||unsafeChannel.get())CardTransactionLock.quarantine();
    String durableOutcome="not_started";
    for(Properties saved:journal.records())if(record.getProperty("id").equals(saved.getProperty("id")))durableOutcome=NotificationJournal.outcome(saved);
    JSONObject result=status(card).put("notificationStatus",durableOutcome)
        .put("notificationWorkerStopped",outcome.workerStopped&&!unsafeChannel.get());
    if(!outcome.workerStopped||unsafeChannel.get())result.put("notificationReviewRequired",true).put("recoveryAction","reconnect_companion_then_verify");
    return result;
  }
  private static String fingerprint(List<CardProfileInventory.Entry> entries) throws Exception {
    List<String> values=new ArrayList<>();for(CardProfileInventory.Entry e:entries)values.add(e.iccid+":"+e.enabled+":"+e.provider.length()+":"+e.provider+e.name.length()+":"+e.name+e.nickname.length()+":"+e.nickname);Collections.sort(values);return hash(String.join("\n",values));
  }
  private static String findIccid(Properties record,List<CardProfileInventory.Entry> entries) throws Exception {
    String found=null;for(CardProfileInventory.Entry e:entries)if(hash(e.iccid).equals(record.getProperty("profile"))){if(found!=null)throw new SecurityException("Ambiguous profile");found=e.iccid;}
    if(found==null)throw new SecurityException("Notification profile is no longer present");return found;
  }
  public static synchronized JSONObject prepareCleanup() throws Exception {
    requireShell();pendingCleanup=null;CardTransactionLock.acquire();
    try {
      FiveberReaderSelector.Target target=FiveberReaderSelector.discover();ReaderDescriptor.requireWritable(target.reader);
      if(!EnrolledWritePolicy.isAllowed(target.identityHash))throw new SecurityException("Card not enrolled for notification cleanup");
      List<CardProfileInventory.Entry> entries=CardProfileInventory.list();Properties selected=null;
      for(Properties record:journal().records())if(NotificationJournal.cleanup(record)&&target.identityHash.equals(record.getProperty("card")))selected=record;
      if(selected==null)throw new SecurityException("No acknowledged notification to clean up");findIccid(selected,entries);
      pendingCleanup=new CleanupPlan(selected.getProperty("id"),target.reader,target.identityHash,fingerprint(entries));
      return new JSONObject().put("ok",true).put("token",pendingCleanup.token).put("validSeconds",90).put("cleanupOnly",true)
          .put("description","Remove one notification already acknowledged by the operator. No network request or profile change.");
    } finally {CardTransactionLock.release();}
  }
  public static JSONObject executeCleanup(String token) throws Exception {
    requireShell();CleanupPlan plan;
    synchronized(PhoneNotificationDelivery.class){
      plan=pendingCleanup;pendingCleanup=null;
      if(plan==null||token==null||!token.equals(plan.token)||SystemClock.elapsedRealtime()-plan.created>90_000L)throw new SecurityException("Notification cleanup preview expired");
      CardTransactionLock.acquire();
    }
    try {
      FiveberReaderSelector.Target target=FiveberReaderSelector.discover();ReaderDescriptor.requireWritable(target.reader);
      if(!plan.reader.equals(target.reader)||!plan.card.equals(target.identityHash)||!EnrolledWritePolicy.isAllowed(target.identityHash))throw new SecurityException("Cleanup card changed");
      List<CardProfileInventory.Entry> entries=CardProfileInventory.list();if(!plan.inventory.equals(fingerprint(entries)))throw new SecurityException("Profiles changed since cleanup preview");
      NotificationJournal journal=journal();Properties record=null;
      for(Properties candidate:journal.records())if(plan.id.equals(candidate.getProperty("id")))record=candidate;
      if(record==null||!NotificationJournal.cleanup(record))throw new SecurityException("Cleanup state changed");
      JSONObject result=attempt(journal,record,target.reader,target.identityHash,findIccid(record,entries),true);
      boolean delivered="delivered".equals(result.optString("notificationStatus"));
      return result.put("ok",delivered).put("verified",delivered).put("cleanupOnly",true).put("reviewRequired",!delivered);
    } finally {CardTransactionLock.release();}
  }
  private PhoneNotificationDelivery(){}
}
