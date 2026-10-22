package dev.sid.fiveberinspector;
import android.os.Build;
import org.json.JSONObject;
import java.util.*;

/** Single-use inventory-bound transactions; failures reconcile without blind retries. */
public final class PortableProfileLifecycle {
 private static Plan pending;
    static synchronized void discardPreview(){pending=null;}
 private static final class Plan {
  String token,reader,hash,identityHash,digest,id,nickname;long created;ProfileLifecycleGuard.Operation op;
 }
 private PortableProfileLifecycle(){}
 private static void shell(){if(android.os.Process.myUid()!=android.os.Process.SHELL_UID)throw new SecurityException("Shell UserService required");}
 static List<ProfileLifecycleGuard.Row> rows(List<CardProfileInventory.Entry> entries){List<ProfileLifecycleGuard.Row> r=new ArrayList<>();for(CardProfileInventory.Entry p:entries)r.add(new ProfileLifecycleGuard.Row(p.iccid,p.enabled,p.nickname,p.provider,p.name));return r;}
 private static boolean qualified(String hash){return EnrolledWritePolicy.isAllowed(hash);}
 private static String safe(String value){String s=value.replaceAll("[\\p{Cntrl}]","").replaceAll("[0-9]{8,}","[hidden]");return s.substring(0,Math.min(96,s.length()));}
 public static JSONObject prepare(String operation,int index,String nickname)throws Exception{return prepare(operation,index,nickname,null,null);}
 public static synchronized JSONObject prepare(String operation,int index,String nickname,String expectedFingerprint,String expectedCard)throws Exception{
  shell();CardTransactionLock.acquire();try{
   pending=null;ProfileLifecycleGuard.Operation op;
   try{op=ProfileLifecycleGuard.Operation.valueOf(operation.toUpperCase(Locale.ROOT));}catch(Exception e){throw new SecurityException("Unsupported profile operation");}
   FiveberReaderSelector.Target card=FiveberReaderSelector.discover();PhoneNativeLpac.listProfiles(card);
   List<CardProfileInventory.Entry> entries=CardProfileInventory.list();List<ProfileLifecycleGuard.Row> before=rows(entries);
   String digest=ProfileLifecycleGuard.fingerprint(before);
   if(expectedFingerprint!=null&&(!expectedFingerprint.equals(ProfileLifecycleGuard.selectionFingerprint(before))||expectedCard==null||!expectedCard.equals(card.identityHash)))throw new SecurityException("Card inventory changed since displayed selection; refresh required");
   if(index<0||index>=entries.size())throw new SecurityException("Profile selection out of bounds");
   CardProfileInventory.Entry target=entries.get(index);ReaderDescriptor.requireLifecycle(card.reader,op,target.enabled);ProfileLifecycleGuard.validate(op,before,target.iccid,nickname);LabSupport.validateMutation(card.identityHash,op,before,target.iccid);ShellOperationScope.validateMutation(card.identityHash,op,before,target.iccid);
   Plan p=new Plan();byte[] random=new byte[24];new java.security.SecureRandom().nextBytes(random);StringBuilder token=new StringBuilder();for(byte b:random)token.append(String.format(Locale.ROOT,"%02x",b&255));
   p.token=token.toString();p.reader=card.reader;p.hash=card.identityHash;p.identityHash=card.identityHash;p.digest=digest;p.id=target.iccid;p.nickname=op==ProfileLifecycleGuard.Operation.RENAME?nickname:"";p.op=op;p.created=android.os.SystemClock.elapsedRealtime();pending=p;
   String from="";for(CardProfileInventory.Entry e:entries)if(e.enabled)from=safe(e.provider+" / "+e.name);
   return new JSONObject().put("token",p.token).put("operation",operation.toLowerCase(Locale.ROOT)).put("from",from).put("to",safe(target.provider+" / "+target.name)).put("validSeconds",90).put("writeQualified",qualified(p.hash)).put("readOnlyPreview",!qualified(p.hash)).put("irreversible",op==ProfileLifecycleGuard.Operation.DELETE).put("noOp",false).put("disconnectsProfile",op==ProfileLifecycleGuard.Operation.DISABLE).put("beforeFingerprint",p.digest);
  }finally{CardTransactionLock.release();}
 }
 public static synchronized OperationJournal.Binding journalBinding(String token)throws Exception{
  if(pending==null||token==null||!pending.token.equals(token)||android.os.SystemClock.elapsedRealtime()-pending.created>90000)
   throw new SecurityException("Operation preview expired or invalid");
  return new OperationJournal.Binding(pending.op.name().toLowerCase(Locale.ROOT),pending.identityHash,
      IntentDigests.of(pending.id),pending.digest,IntentDigests.of(pending.nickname));
 }
 public static JSONObject execute(String token)throws Exception{
  shell();Plan p;synchronized(PortableProfileLifecycle.class){
   if(pending==null||token==null||!pending.token.equals(token)||android.os.SystemClock.elapsedRealtime()-pending.created>90000){pending=null;throw new SecurityException("Operation preview expired or invalid");}
   CardTransactionLock.acquire();p=pending;pending=null;
  }
  try{
   if(!qualified(p.hash))throw new SecurityException("Invalid card identity or local test scope does not permit this card");
   FiveberReaderSelector.Target card=verifyIdentity(p);PhoneNativeLpac.listProfiles(card);List<ProfileLifecycleGuard.Row> before=rows(CardProfileInventory.list());
   if(!p.digest.equals(ProfileLifecycleGuard.fingerprint(before)))throw new SecurityException("Card changed since preview");
   ReaderDescriptor.requireLifecycle(p.reader,p.op,ProfileLifecycleGuard.selected(before,p.id).enabled);
   ProfileLifecycleGuard.validate(p.op,before,p.id,p.nickname);LabSupport.validateMutation(p.identityHash,p.op,before,p.id);ShellOperationScope.validateMutation(p.identityHash,p.op,before,p.id);NativeLpacBundle.ensureInstalled();
   java.util.Set<Long> notificationBefore=p.op==ProfileLifecycleGuard.Operation.DELETE?PhoneNotificationDelivery.beforeDeletion(p.reader,p.hash,p.id):null;
   boolean nativeCompleted=false,verified=false,unchanged=false,restored=false,rollback=false;
   boolean[] submitted={false};
   // Explicitly confirmed active disable submits one command; zero-active readback is required.
   try{PhoneProfileLifecycle.execute(p.op,p.id,p.nickname,p.reader,p.hash,submitted);nativeCompleted=true;}catch(Exception ignored){}
   if(CardTransactionLock.isQuarantined())return new JSONObject().put("ok",false).put("verified",false).put("status","unknown").put("reviewRequired",true).put("cardReadRequired",true).put("restartRequired",true);
   DownloadWorkerSupervisor.Outcome<LifecyclePostflight.Result> postflight=DownloadWorkerSupervisor.run(
       ()->LifecyclePostflight.observe(p.op,before,p.id,p.nickname,()->{verifyIdentity(p);return rows(CardProfileInventory.list());},Thread::sleep),
       ()->{},25_000L,3_000L);
   if(!postflight.workerStopped)CardTransactionLock.quarantine();
   if(postflight.result!=null){verified=postflight.result.verified;unchanged=postflight.result.unchanged;}
   // REFRESH can temporarily remove the HAL channel or expose an intermediate inventory.
   // Never issue an automatic restoration write because of a transient or uncertain read.
   JSONObject subscription=new JSONObject().put("systemSubscriptionStatus","unavailable")
       .put("systemSubscriptionWorkerStopped",true).put("networkRegistrationChecked",false);
   if(verified&&p.op==ProfileLifecycleGuard.Operation.ENABLE) {
    for(int attempt=0;attempt<3;attempt++) {
     if(Thread.currentThread().isInterrupted())break;
     if(attempt>0)try{Thread.sleep(750);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
     subscription=SystemSubscriptionObservation.observe(p.id);
     if("matched".equals(subscription.optString("systemSubscriptionStatus"))||!subscription.optBoolean("systemSubscriptionWorkerStopped",false))break;
    }
   }
   JSONObject deletionNotice=verified&&p.op==ProfileLifecycleGuard.Operation.DELETE?PhoneNotificationDelivery.afterDeletion(p.reader,p.hash,p.id,notificationBefore):null;
   JSONObject lifecycleResult=new JSONObject().put("systemSubscriptionStatus",subscription.getString("systemSubscriptionStatus"))
       .put("systemSubscriptionWorkerStopped",subscription.getBoolean("systemSubscriptionWorkerStopped"))
       .put("networkRegistrationChecked",false).put("ok",verified).put("operation",p.op.name().toLowerCase(Locale.ROOT)).put("verified",verified).put("switched",verified&&p.op==ProfileLifecycleGuard.Operation.ENABLE).put("cardEnabledVerified",verified&&p.op==ProfileLifecycleGuard.Operation.ENABLE).put("refreshRequested",(p.op==ProfileLifecycleGuard.Operation.ENABLE||p.op==ProfileLifecycleGuard.Operation.DISABLE)&&submitted[0]).put("restartRequired",!postflight.workerStopped).put("postflightReads",postflight.result==null?0:postflight.result.reads).put("restored",restored).put("unchanged",unchanged).put("nativeCompleted",nativeCompleted).put("rollbackAttempted",rollback).put("reviewRequired",!verified).put("cardReadRequired",true).put("status",verified?"verified":p.op==ProfileLifecycleGuard.Operation.ENABLE?"unknown":unchanged?"not_applied":"unknown");
   if(deletionNotice!=null)for(String key:new String[]{"notificationStatus","notificationReviewRequired","notificationCleanupAvailable","notificationPendingCount","notificationWorkerStopped","recoveryAction"})if(deletionNotice.has(key))lifecycleResult.put(key,deletionNotice.get(key));
   if(CardTransactionLock.isQuarantined())lifecycleResult.put("restartRequired",true).put("reviewRequired",true);
   return lifecycleResult;
  }finally{CardTransactionLock.release();}
 }
 private static FiveberReaderSelector.Target verifyIdentity(Plan p)throws Exception{FiveberReaderSelector.Target c=FiveberReaderSelector.discover();if(!p.reader.equals(c.reader)||!p.hash.equals(c.identityHash)||!p.identityHash.equals(c.identityHash))throw new SecurityException("Card identity changed");return c;}
}
