package dev.sid.fiveberinspector;
import java.util.*;
import java.lang.reflect.*;

/** Synthetic card identities and APIs only. No device, account, network or activation code. */
public final class CompatibilityOfflineTest {
 private static int assertions;
 static void check(boolean value){assertions++;if(!value)throw new AssertionError("Assertion "+assertions);}
 interface Throwing {void run()throws Exception;}
 static void rejects(Throwing action)throws Exception {
  try{action.run();throw new AssertionError("Expected rejection");}
  catch(SecurityException expected){assertions++;}
 }
 public static final class Opened {public int channelNumber=2;}
 public static final class FakeAidl {
  int closed,transmitted;byte[] last;
  public boolean isCardPresent(){return true;}
  public Opened openLogicalChannel(byte[] aid,byte p2){return new Opened();}
  public byte[] transmit(byte[] command){transmitted++;last=command.clone();return new byte[]{(byte)0x90,0};}
  public void closeChannel(byte channel){closed=channel;}
 }
 public interface Listener {}
 public static final class FakeChannel {
  boolean closed,failClose;byte[] last;
  public byte[] transmit(byte[] command){last=command.clone();return new byte[]{(byte)0x90,0};}
  public void close(){closed=true;if(failClose)throw new SecurityException("Synthetic close denial");}
 }
 public static final class FakeSession {
  boolean closed,denied,nullChannel;FakeChannel channel=new FakeChannel();
  public FakeChannel openLogicalChannel(byte[] aid,byte p2,Listener listener){
   if(denied)throw new SecurityException("Never expose this vendor message");
   return nullChannel?null:channel;
  }
  public void close(){closed=true;}
 }
 public static final class FakeReader {
  FakeSession session=new FakeSession();
  public boolean isSecureElementPresent(){return true;}
  public FakeSession openSession(){return session;}
 }
 static OmapiSecureElementTransport omapi(FakeReader reader){
  return new OmapiSecureElementTransport(reader,new Listener(){},FakeReader.class,FakeSession.class,FakeChannel.class,Listener.class);
 }
 static final class Card implements SecureElementTransport {
  int closed;byte[] eid=new byte[16];boolean denied,bad,fragmented,endless,absent,failClose;int frames;
  Card(int identity){eid[0]=(byte)identity;}
  public boolean isCardPresent(){return !absent;}
  public int openLogicalChannel(byte[] aid,byte p2){if(denied)throw new SecureElementFailure(SecureElementFailure.Reason.ACCESS_DENIED);return 1;}
  public byte[] transmit(byte[] cmd){
   frames++;
   if(endless)return new byte[]{0x61,0x15};
   if(bad)return new byte[]{0x6A,(byte)0x82};
   byte[] body=new byte[21];System.arraycopy(new byte[]{(byte)0xBF,0x3E,0x12,0x5A,0x10},0,body,0,5);System.arraycopy(eid,0,body,5,16);
   if(fragmented&&frames==1)return new byte[]{0x61,0x15};
   if(fragmented)check(Arrays.equals(cmd,new byte[]{(byte)0x81,(byte)0xC0,0,0,0x15}));
   return java.nio.ByteBuffer.allocate(23).put(body).put((byte)0x90).put((byte)0).array();
  }
  public void closeChannel(int channel){closed++;if(failClose)throw new SecurityException("Never expose private close message");}
 }

 public static final class Request {
  public String aid,callingPackage;public int slotIndex,portIndex,subId,p2,channel;public Object binder;
 }
 public static final class Result {
  int status=1,channel=2;
  public int getStatus(){return status;}public int getChannel(){return channel;}
 }
 public static final class Phone {
  Request last;Result result=new Result();int frames,cla,command,p3;String data;String reply="9000";boolean failSend,closed,closeOk=true;
  public Result iccOpenLogicalChannel(Request request){last=request;return result;}
  public String iccTransmitApduLogicalChannelByPort(int slot,int port,int channel,int cla,int command,int p1,int p2,int p3,String data){
   check(slot==1&&port==0&&channel==2);frames++;this.cla=cla;this.command=command;this.p3=p3;this.data=data;if(failSend)throw new IllegalStateException("Synthetic lost result");return reply;
  }
  public boolean iccCloseLogicalChannel(Request request){check(request.slotIndex==1&&request.portIndex==0&&request.channel==2);closed=true;return closeOk;}
  public Result iccOpenLogicalChannel(int subId,String callingPackage,String aid,int p2){last=new Request();last.subId=subId;last.callingPackage=callingPackage;last.aid=aid;last.p2=p2;return result;}
  public String iccTransmitApduLogicalChannel(int subId,int channel,int cla,int command,int p1,int p2,int p3,String data){check(subId==4&&channel==2);frames++;this.cla=cla;this.command=command;this.p3=p3;this.data=data;if(failSend)throw new IllegalStateException("Synthetic lost result");return reply;}
  public boolean iccCloseLogicalChannel(int subId,int channel){check(subId==4&&channel==2);closed=true;return closeOk;}
 }
 static byte[] concat(byte[]...arrays){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();for(byte[] a:arrays)out.writeBytes(a);return out.toByteArray();}
 static byte[] deletionMetadata(String id,long sequence,int operation,String host){return NotificationCodec.tlv(0xbf2f,concat(NotificationCodec.tlv(0x80,new byte[]{(byte)sequence}),NotificationCodec.tlv(0x81,new byte[]{4,(byte)operation}),NotificationCodec.tlv(0x5a,Arrays.copyOfRange(ProfileLifecycleGuard.command(ProfileLifecycleGuard.Operation.DELETE,id,"",1),10,20)),NotificationCodec.tlv(0x0c,host.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));}
 static byte[] deletionReply(String id,long sequence,int operation,String host){return NotificationCodec.tlv(0xbf2b,NotificationCodec.tlv(0xa0,NotificationCodec.tlv(0x30,deletionMetadata(id,sequence,operation,host))));}
 static void deletionNotificationTests()throws Exception{
  String id="8900000000000000001",other="8900000000000000002",host="example.test";byte[] reply=deletionReply(id,63,16,host);
  NotificationCodec.Pending parsed=NotificationCodec.pending(reply,63,id,host,"delete");check(parsed.host.equals(host));
  rejects(()->NotificationCodec.pending(reply,63,id,host));rejects(()->NotificationCodec.pending(reply,62,id,host,"delete"));rejects(()->NotificationCodec.pending(reply,63,other,host,"delete"));rejects(()->NotificationCodec.pending(reply,63,id,"another.test","delete"));rejects(()->NotificationCodec.pending(deletionReply(id,63,64,host),63,id,host,"delete"));
  List<NotificationMetadataInventory.Entry> metadata=NotificationMetadataInventory.decode(NotificationCodec.tlv(0xbf28,NotificationCodec.tlv(0xa0,deletionMetadata(id,63,16,host))));check(metadata.size()==1&&metadata.get(0).sequence==63&&metadata.get(0).iccid.equals(id)&&metadata.get(0).operation==16&&metadata.get(0).host.equals(host));
  rejects(()->NotificationMetadataInventory.decode(NotificationCodec.tlv(0xbf28,NotificationCodec.tlv(0xa0,concat(deletionMetadata(id,63,16,host),deletionMetadata(id,63,16,host))))));
  byte[] list=tag(0xbf28);CardReadApduPolicy ordinary=new CardReadApduPolicy(2,false);rejects(()->ordinary.authorize(list));CardReadApduPolicy opted=new CardReadApduPolicy(2,false,true);opted.authorize(list);opted.acceptResponse(new byte[]{(byte)0x90,0});rejects(()->opted.authorize(command(NotificationCodec.command(63,true),2)));
  java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("notification-delete-test");
  try{
   NotificationJournal journal=new NotificationJournal(directory.toFile());String card="1".repeat(64),transaction="11111111-1111-4111-8111-111111111111";
   Properties record=journal.beginDeletion(card,63,host,IntentDigests.of(id),transaction);check(record.getProperty("notificationOperation").equals("delete"));
   int[] sent={0},removed={0};NotificationTransaction.Card io=new NotificationTransaction.Card(){public byte[] retrieve(){return reply;}public byte[] remove(){removed[0]++;return NotificationCodec.tlv(0xbf30,NotificationCodec.tlv(0x80,new byte[]{0}));}};
   NotificationTransaction.run(journal,record,id,io,notification->{sent[0]++;return true;},false);check(sent[0]==1&&removed[0]==1&&NotificationJournal.outcome(journal.records().get(0)).equals("delivered"));
   Properties unknown=journal.beginDeletion(card,64,host,IntentDigests.of(id),transaction);NotificationTransaction.Card unknownIo=new NotificationTransaction.Card(){public byte[] retrieve(){return deletionReply(id,64,16,host);}public byte[] remove(){throw new AssertionError("Removal before acknowledgment");}};
   try{NotificationTransaction.run(journal,unknown,id,unknownIo,notification->{throw new java.io.IOException("Synthetic lost acknowledgment");},false);throw new AssertionError("Expected unknown delivery");}catch(java.io.IOException expected){check(NotificationJournal.outcome(unknown).equals("delivery_unknown"));}
   rejects(()->NotificationTransaction.run(journal,unknown,id,unknownIo,notification->{throw new AssertionError("No HTTP repeat");},true));
   Properties cleanup=journal.beginDeletion(card,65,host,IntentDigests.of(id),transaction);int[] attempts={0};NotificationTransaction.Card cleanupIo=new NotificationTransaction.Card(){public byte[] retrieve(){return deletionReply(id,65,16,host);}public byte[] remove(){if(attempts[0]++==0)throw new SecurityException("Synthetic interrupted cleanup");return NotificationCodec.tlv(0xbf30,NotificationCodec.tlv(0x80,new byte[]{0}));}};
   rejects(()->NotificationTransaction.run(journal,cleanup,id,cleanupIo,notification->{sent[0]++;return true;},false));check(NotificationJournal.cleanup(cleanup));NotificationTransaction.run(journal,cleanup,id,cleanupIo,notification->{throw new AssertionError("No duplicate notification post");},true);check(NotificationJournal.outcome(cleanup).equals("delivered")&&sent[0]==2&&attempts[0]==2);
  }finally{try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.walk(directory)){for(java.nio.file.Path file:files.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(file);}}
 }
 static final class OneChannelSlot {
  boolean busy;int identity=7,removals;List<String> events=new ArrayList<>();
 }
 static final class Session implements SecureElementTransport {
  final OneChannelSlot slot;final String role;boolean failClose;
  Session(OneChannelSlot slot,String role){this.slot=slot;this.role=role;}
  public boolean isCardPresent(){return true;}
  public int openLogicalChannel(byte[] aid,byte p2){if(slot.busy)throw new java.util.NoSuchElementException("One session only");slot.busy=true;slot.events.add(role+"-open");return 1;}
  public byte[] transmit(byte[] cmd){
   slot.events.add(role+"-send");
   if(role.equals("identity")){byte[] body=new byte[23];System.arraycopy(new byte[]{(byte)0xbf,0x3e,0x12,0x5a,0x10},0,body,0,5);body[5]=(byte)slot.identity;body[21]=(byte)0x90;return body;}
   if(role.equals("notification"))return new byte[]{(byte)0xbf,0x2b,2,(byte)0xa0,0,(byte)0x90,0};
   check(Arrays.equals(cmd,command(NotificationCodec.command(62,true),1)));slot.removals++;return new byte[]{(byte)0xbf,0x30,3,(byte)0x80,1,0,(byte)0x90,0};
  }
  public void closeChannel(int channel){slot.events.add(role+"-close");if(failClose)throw new SecurityException("Synthetic close failure");slot.busy=false;}
 }
 static void singleChannelNotificationTests()throws Exception{
  for(int scenario=0;scenario<3;scenario++){
   OneChannelSlot slot=new OneChannelSlot();Session identity=new Session(slot,"identity"),read=new Session(slot,"notification"),remove=new Session(slot,"removal");
   SystemHalAdapter.transports.clear();SystemHalAdapter.fallback.clear();SystemHalAdapter.transports.put("SIM1",identity);SystemHalAdapter.notification=read;SystemHalAdapter.removal=remove;
   FiveberReaderSelector.Target card=FiveberReaderSelector.discover();java.util.concurrent.atomic.AtomicBoolean unsafe=new java.util.concurrent.atomic.AtomicBoolean();
   try(PortableNotificationHalBridge bridge=new PortableNotificationHalBridge(card.reader,card.identityHash,62,unsafe)){
    bridge.retrieve();check(slot.busy&&slot.removals==0);
    if(scenario==1)slot.identity=8;
    if(scenario==2)read.failClose=true;
    if(scenario==0){check(NotificationCodec.removed(bridge.removeAfterAcknowledgement()));check(slot.removals==1);}
    else rejects(()->bridge.removeAfterAcknowledgement());
   }
   if(scenario==0){check(!slot.busy&&!unsafe.get());int closed=slot.events.indexOf("notification-close"),opened=slot.events.indexOf("removal-open");check(closed>=0&&opened>closed&&slot.events.subList(closed+1,opened).contains("identity-open"));}
   else check(slot.removals==0&&!slot.events.contains("removal-open"));
   if(scenario==2)check(unsafe.get());
  }
  TelephonyNotificationPolicy removal=new TelephonyNotificationPolicy(62,true);
  rejects(()->removal.authorize(command(NotificationCodec.command(62,false),2),2));
  rejects(()->removal.authorize(command(NotificationCodec.command(63,true),2),2));
  byte[] exact=command(NotificationCodec.command(62,true),2);removal.authorize(exact,2);rejects(()->removal.authorize(exact,2));
  removal.acceptResponse(new byte[]{0x61,4});rejects(()->removal.authorize(new byte[]{(byte)0x82,(byte)0xc0,0,0,5},2));
  removal.authorize(new byte[]{(byte)0x82,(byte)0xc0,0,0,4},2);removal.acceptResponse(new byte[]{(byte)0x90,0});rejects(()->removal.authorize(exact,2));
 }
 static byte[] command(byte[] body,int channel){byte[] a=new byte[body.length+5];a[0]=(byte)(0x80|channel);a[1]=(byte)0xe2;a[2]=(byte)0x91;a[4]=(byte)body.length;System.arraycopy(body,0,a,5,body.length);return a;}
 static byte[] tag(int tag){return command(new byte[]{(byte)(tag>>8),(byte)tag,0},2);}
 static TelephonySecureElementTransport downloading(Phone phone)throws Exception{
  TelephonySecureElementTransport t=new TelephonySecureElementTransport(phone,Phone.class,Request.class,1,0,new Object(),new DownloadSessionPolicy());t.openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0);return t;
 }
 static void protocolTests()throws Exception{
  String id="8900000000000000001";
  for(ProfileLifecycleGuard.Operation op:ProfileLifecycleGuard.Operation.values()){
   TelephonyLifecyclePolicy policy=new TelephonyLifecyclePolicy(op,id,"");
   for(ProfileLifecycleGuard.Operation other:ProfileLifecycleGuard.Operation.values())if(other!=op)rejects(()->policy.authorize(ProfileLifecycleGuard.command(other,id,"",2),2));
   byte[] exact=ProfileLifecycleGuard.command(op,id,"",2);policy.authorize(exact,2);policy.acceptResponse(new byte[]{(byte)0x90,0});rejects(()->policy.authorize(exact,2));
  }
  for(int forbidden:new int[]{0xbf31,0xbf32,0xbf33,0xbf29,0xbf30,0xbf2b,0xbf3e}){
   Phone f=new Phone();TelephonySecureElementTransport t=downloading(f);rejects(()->t.transmit(tag(forbidden)));check(f.frames==0);t.closeChannel(2);
  }
  Phone f=new Phone();TelephonySecureElementTransport t=downloading(f);
  f.reply="6104";t.transmit(tag(0xbf2e));
  rejects(()->t.transmit(new byte[]{(byte)0x82,(byte)0xc0,0,0,3}));check(f.frames==1);
  f.reply="9000";t.transmit(new byte[]{(byte)0x82,(byte)0xc0,0,0,4});t.transmit(tag(0xbf20));
  t.authorizeDownloadHttp("initiateAuthentication");t.downloadHttpCompleted("initiateAuthentication",200);t.bindDownloadTransaction("abcd");
  t.transmit(tag(0xbf38));t.authorizeDownloadHttp("authenticateClient");t.downloadHttpCompleted("authenticateClient",200);
  t.transmit(tag(0xbf21));t.authorizeDownloadHttp("getBoundProfilePackage");t.downloadHttpCompleted("getBoundProfilePackage",200);
  t.transmit(tag(0xbf36));check(f.frames==6);t.closeChannel(2);rejects(()->t.openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0));
  Phone lost=new Phone();lost.failSend=true;TelephonySecureElementTransport uncertain=downloading(lost);
  try{uncertain.transmit(tag(0xbf2e));throw new AssertionError("Expected lost Binder result");}catch(InvocationTargetException expected){check(lost.frames==1);}
  rejects(()->uncertain.transmit(tag(0xbf2e)));check(lost.frames==1);uncertain.closeChannel(2);
  Phone notify=new Phone();TelephonySecureElementTransport n=new TelephonySecureElementTransport(notify,Phone.class,Request.class,1,0,new Object(),19L);
  n.openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0);
  n.transmit(command(NotificationCodec.command(19,false),2));
  rejects(()->n.transmit(command(NotificationCodec.command(19,true),2)));check(notify.frames==1);
  n.acknowledgeNotification();n.transmit(command(NotificationCodec.command(19,true),2));check(notify.frames==2);
  rejects(()->n.transmit(command(NotificationCodec.command(19,true),2)));n.closeChannel(2);
 }
 static void scopeTests()throws Exception{
  String active="8900000000000000001",smc="8900000000000000002",test="8900000000000000003",foreign="8900000000000000004",card="1".repeat(64);
  ProfileLifecycleGuard.Row povo=new ProfileLifecycleGuard.Row(active,true,"","povo","KDDI"),old=new ProfileLifecycleGuard.Row(smc,false,"","SMC","Name"),added=new ProfileLifecycleGuard.Row(test,false,"","Speedtest","Test");
  List<ProfileLifecycleGuard.Row> baseline=List.of(povo,old),installed=List.of(povo,old,added);
  Properties p=new Properties();p.setProperty("schema","1");p.setProperty("card",card);p.setProperty("active",IntentDigests.of(active));p.setProperty("activation",IntentDigests.of("public-code"));p.setProperty("baseline",IntentDigests.of(active)+","+IntentDigests.of(smc));p.setProperty("switchAllowed","true");
  for(ProfileLifecycleGuard.Row row:baseline){String hash=IntentDigests.of(row.id);p.setProperty("meta."+hash,OwnerOperationScope.metadata(row));p.setProperty("state."+hash,Boolean.toString(row.enabled));}
  OwnerOperationScope before=new OwnerOperationScope(p);before.download(card,baseline,"public-code");
  rejects(()->before.download(card,baseline,"other-code"));rejects(()->before.download(card,installed,"public-code"));rejects(()->before.mutation(card,ProfileLifecycleGuard.Operation.DELETE,installed,test));
  p.setProperty("disposable",IntentDigests.of(test));OwnerOperationScope scope=new OwnerOperationScope(p);
  scope.mutation(card,ProfileLifecycleGuard.Operation.ENABLE,installed,test);scope.mutation(card,ProfileLifecycleGuard.Operation.DELETE,installed,test);
  for(ProfileLifecycleGuard.Operation op:ProfileLifecycleGuard.Operation.values()){
   rejects(()->scope.mutation(card,op,installed,active));rejects(()->scope.mutation(card,op,installed,smc));rejects(()->scope.mutation(card,op,installed,foreign));
  }
  List<ProfileLifecycleGuard.Row> switched=List.of(new ProfileLifecycleGuard.Row(active,false,"","povo","KDDI"),old,new ProfileLifecycleGuard.Row(test,true,"","Speedtest","Test"));
  scope.mutation(card,ProfileLifecycleGuard.Operation.ENABLE,switched,active);
  rejects(()->scope.mutation(card,ProfileLifecycleGuard.Operation.DELETE,switched,test));rejects(()->scope.mutation(card,ProfileLifecycleGuard.Operation.DISABLE,switched,test));
  rejects(()->scope.mutation("2".repeat(64),ProfileLifecycleGuard.Operation.DELETE,installed,test));
  rejects(()->scope.mutation(card,ProfileLifecycleGuard.Operation.DELETE,List.of(new ProfileLifecycleGuard.Row(active,true,"changed","povo","KDDI"),old,added),test));
  rejects(()->scope.mutation(card,ProfileLifecycleGuard.Operation.DELETE,List.of(povo,old,added,new ProfileLifecycleGuard.Row(foreign,false,"","Other","Other")),test));
  p.setProperty("switchAllowed","false");OwnerOperationScope protectedState=new OwnerOperationScope(p);rejects(()->protectedState.mutation(card,ProfileLifecycleGuard.Operation.ENABLE,installed,test));
  p.setProperty("disposable",IntentDigests.of(active));rejects(()->new OwnerOperationScope(p));
 }
 static void renameTests()throws Exception{
  String id="8900000000000000001",other="8900000000000000002";
  for(ProfileLifecycleGuard.Operation op:ProfileLifecycleGuard.Operation.values()){
   check(ReaderDescriptor.lifecycleAllowed("telephony.1.0",op,false));
   check(ReaderDescriptor.lifecycleAllowed("telephony.1.0",op,true));
  }
  check(!ReaderDescriptor.lifecycleAllowed("telephony.8.0",ProfileLifecycleGuard.Operation.RENAME,false));
  for(int channel=1;channel<=3;channel++){
   final int c=channel;TelephonyLifecyclePolicy policy=new TelephonyLifecyclePolicy(ProfileLifecycleGuard.Operation.RENAME,id,"SMC test");
   rejects(()->policy.authorize(ProfileLifecycleGuard.command(ProfileLifecycleGuard.Operation.RENAME,other,"SMC test",c),c));
   rejects(()->policy.authorize(ProfileLifecycleGuard.command(ProfileLifecycleGuard.Operation.RENAME,id,"wrong",c),c));
   for(ProfileLifecycleGuard.Operation op:ProfileLifecycleGuard.Operation.values())if(op!=ProfileLifecycleGuard.Operation.RENAME)
    rejects(()->policy.authorize(ProfileLifecycleGuard.command(op,id,"",c),c));
   byte[] exact=ProfileLifecycleGuard.command(ProfileLifecycleGuard.Operation.RENAME,id,"SMC test",c);
   policy.authorize(exact,c);rejects(()->policy.authorize(exact,c));
   policy.acceptResponse(new byte[]{0x61,2});
   rejects(()->policy.authorize(new byte[]{(byte)(0x80|c),(byte)0xC0,0,0,3},c));
   for(int i=0;i<6;i++){policy.authorize(new byte[]{(byte)(0x80|c),(byte)0xC0,0,0,2},c);policy.acceptResponse(new byte[]{0x61,2});}
   rejects(()->policy.authorize(new byte[]{(byte)(0x80|c),(byte)0xC0,0,0,2},c));
  }
  Phone phone=new Phone();TelephonySecureElementTransport transport=new TelephonySecureElementTransport(phone,Phone.class,Request.class,1,0,new Object(),id,"SMC test");
  byte[] aid=TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR);transport.openLogicalChannel(aid,(byte)0);
  rejects(()->transport.transmit(ProfileLifecycleGuard.command(ProfileLifecycleGuard.Operation.DELETE,id,"",2)));check(phone.frames==0);
  byte[] exact=ProfileLifecycleGuard.command(ProfileLifecycleGuard.Operation.RENAME,id,"SMC test",2);
  transport.transmit(exact);check(phone.frames==1&&phone.cla==0x80&&phone.data.equals(TelephonyChannelCodec.hex(exact,5)));
  rejects(()->transport.transmit(exact));check(phone.frames==1);transport.closeChannel(2);
  rejects(()->transport.openLogicalChannel(aid,(byte)0));
  Phone lost=new Phone();lost.failSend=true;TelephonySecureElementTransport uncertain=new TelephonySecureElementTransport(lost,Phone.class,Request.class,1,0,new Object(),id,"SMC test");
  uncertain.openLogicalChannel(aid,(byte)0);
  try{uncertain.transmit(exact);throw new AssertionError("Expected lost response");}catch(InvocationTargetException expected){check(lost.frames==1);}
  rejects(()->uncertain.transmit(exact));check(lost.frames==1);uncertain.closeChannel(2);
  List<ProfileLifecycleGuard.Row> before=List.of(new ProfileLifecycleGuard.Row(id,false,"SMC","S","N"),new ProfileLifecycleGuard.Row(other,true,"povo","K","N"));
  List<ProfileLifecycleGuard.Row> renamed=List.of(new ProfileLifecycleGuard.Row(id,false,"SMC test","S","N"),before.get(1));
  check(ProfileLifecycleGuard.exactTransition(ProfileLifecycleGuard.Operation.RENAME,before,renamed,id,"SMC test"));
  check(ProfileLifecycleGuard.exactTransition(ProfileLifecycleGuard.Operation.RENAME,renamed,before,id,"SMC"));
  for(ProfileLifecycleGuard.Row changed:List.of(new ProfileLifecycleGuard.Row(other,false,"povo","K","N"),new ProfileLifecycleGuard.Row(other,true,"changed","K","N"),new ProfileLifecycleGuard.Row(other,true,"povo","other","N")))
   check(!ProfileLifecycleGuard.exactTransition(ProfileLifecycleGuard.Operation.RENAME,before,List.of(renamed.get(0),changed),id,"SMC test"));
 }
 static void telephonyTests()throws Exception{
  byte[] canonical=java.util.HexFormat.of().parseHex(TelephonyChannelCodec.ISDR);
  String encoded=TelephonyChannelCodec.aid(canonical);
  check(Arrays.equals(canonical,java.util.HexFormat.of().parseHex(encoded)));
  rejects(()->TelephonyChannelCodec.aid(new byte[]{0,0}));
  rejects(()->TelephonyChannelCodec.unhex("900"));
  rejects(()->TelephonyChannelCodec.unhex("90xz"));
  check(Arrays.equals(ReaderDescriptor.telephonyIndices("telephony.1.0"),new int[]{1,0}));
  rejects(()->ReaderDescriptor.telephonyIndices("telephony.8.0"));
  rejects(()->ReaderDescriptor.telephonyIndices("telephony.1.-1"));
  check(ReaderDescriptor.writable("telephony.1.0"));
  check(ReaderDescriptor.writable("SIM1")&&ReaderDescriptor.writable("omapi.SIM1"));
  Phone phone=new Phone();Object token=new Object();
  TelephonySecureElementTransport transport=new TelephonySecureElementTransport(phone,Phone.class,Request.class,1,0,token);
  check(transport.openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0)==2);
  check(phone.last.subId==-1&&phone.last.callingPackage.equals("com.android.shell")&&phone.last.binder==token);
  check(phone.last.aid.equals("a0000005591010ffffffff8900000100"));
  rejects(()->transport.openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0));
  rejects(()->transport.transmit(new byte[]{(byte)0x82,(byte)0xE2,(byte)0x91,0,3,(byte)0xBF,0x31,0}));
  check(phone.frames==0);
  transport.transmit(new byte[]{(byte)0x82,(byte)0xE2,(byte)0x91,0,3,(byte)0xBF,0x2D,0});
  check(phone.frames==1&&phone.cla==0x80&&phone.command==0xE2&&phone.p3==3&&phone.data.equals("bf2d00"));
  rejects(()->transport.transmit(new byte[]{(byte)0x82,(byte)0xC0,0,0,0}));
  rejects(()->transport.closeChannel(1));check(!phone.closed);
  transport.closeChannel(2);check(phone.closed);rejects(()->transport.transmit(new byte[]{(byte)0x82,0,0,0,0}));
  Phone closeDenied=new Phone();closeDenied.closeOk=false;
  TelephonySecureElementTransport closeTransport=new TelephonySecureElementTransport(closeDenied,Phone.class,Request.class,1,0,token);
  closeTransport.openLogicalChannel(canonical,(byte)0);
  try{closeTransport.closeChannel(2);throw new AssertionError();}
  catch(SecureElementFailure expected){check(expected.reason==SecureElementFailure.Reason.CHANNEL_CLEANUP_FAILED);}
  Phone unavailable=new Phone();unavailable.result.status=3;
  try{new TelephonySecureElementTransport(unavailable,Phone.class,Request.class,1,0,token).openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0);throw new AssertionError();}
  catch(java.util.NoSuchElementException expected){check(!unavailable.closed);}
  Phone legacy=new Phone();
  TelephonySecureElementTransport subscription=new TelephonySecureElementTransport(legacy,Phone.class,4,token);
  check(subscription.openLogicalChannel(canonical,(byte)0)==2);
  check(legacy.last.subId==4&&legacy.last.callingPackage.equals("com.android.shell")&&legacy.last.aid.equals("a0000005591010ffffffff8900000100"));
  subscription.transmit(new byte[]{(byte)0x82,(byte)0xE2,(byte)0x91,0,3,(byte)0xBF,0x2D,0});
  check(legacy.frames==1&&legacy.cla==0x80&&legacy.p3==3&&legacy.data.equals("bf2d00"));
  subscription.closeChannel(2);check(legacy.closed);
  SystemHalAdapter.transports.clear();SystemHalAdapter.fallback.clear();
  Card denied=new Card(1);denied.denied=true;SystemHalAdapter.transports.put("omapi.SIM1",denied);
  SystemHalAdapter.fallback.put("telephony.1.0",new Card(1));
  check(FiveberReaderSelector.discover().reader.equals("telephony.1.0"));
  SystemHalAdapter.transports.clear();SystemHalAdapter.transports.put("SIM1",new Card(1));SystemHalAdapter.transports.put("SIM2",new Card(2));
  int calls=SystemHalAdapter.fallbackCalls;
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.AMBIGUOUS_EUICC);}
  check(SystemHalAdapter.fallbackCalls==calls);SystemHalAdapter.fallback.clear();SystemHalAdapter.transports.clear();
 }

 public static final class HidlResponse {public byte channelNumber=2;}
 public interface HidlCallback {void onValues(HidlResponse response,byte status);}
 public static final class FakeHidl {
  int closeCount;byte status,closeStatus;boolean lost,duplicate;
  public boolean isCardPresent(){return true;}
  public void openLogicalChannel(ArrayList<Byte> aid,byte p2,HidlCallback callback){
   if(lost)throw new IllegalStateException("Lost reply");HidlResponse response=new HidlResponse();
   if(status!=0)response.channelNumber=0;callback.onValues(response,status);if(duplicate)callback.onValues(response,status);
  }
  public ArrayList<Byte> transmit(ArrayList<Byte> command){return new ArrayList<>(List.of((byte)0x90,(byte)0));}
  public byte closeChannel(byte channel){closeCount++;check(channel==2);return closeStatus;}
 }
 static HidlSecureElementTransport hidl(FakeHidl fake){return new HidlSecureElementTransport(fake,FakeHidl.class,HidlCallback.class);}
 static void compatibilityDetectionTests()throws Exception{
  String manifest="<manifest><hal format='hidl'><name>android.hardware.secure_element</name><fqname>@1.2::ISecureElement/SIM2</fqname></hal></manifest>";
  check(HidlManifestReaders.parse(new String[]{manifest}).equals(List.of("hidl.SIM2")));
  check(HidlManifestReaders.parse(new String[]{manifest,manifest}).equals(List.of("hidl.SIM2")));
  check(HidlManifestReaders.parse(new String[]{manifest.replace("manifest","compatibility-matrix")}).isEmpty());
  check(HidlManifestReaders.parse(new String[]{manifest.replace("format='hidl'","format='aidl'")}).isEmpty());
  check(HidlManifestReaders.parse(new String[]{"<manifest><hal><name>android.hardware.secure_element</name><version>1.0-2</version><interface><name>ISecureElement</name><instance>SIM9</instance></interface></hal></manifest>"}).equals(List.of("hidl.SIM9")));
  rejects(()->HidlManifestReaders.parse(new String[]{manifest.replace("@1.2","@2.0")}));
  rejects(()->HidlManifestReaders.parse(new String[]{"<!DOCTYPE x [<!ENTITY bad SYSTEM 'file:///not-readable'>]>"+manifest}));
  rejects(()->HidlManifestReaders.parse(new String[]{manifest.replace("SIM2","../SIM2")}));
  SystemHalAdapter.transports.clear();SystemHalAdapter.fallback.clear();SystemHalAdapter.failures.clear();SystemHalAdapter.order.clear();
  SystemHalAdapter.transports.put("SIM1",new Card(9));SystemHalAdapter.fallback.put("telephony.1.0",new Card(2));
  check(FiveberReaderSelector.discover().reader.equals("SIM1"));check(SystemHalAdapter.order.equals(List.of(CompatibilityReport.Backend.HAL_AIDL)));
  CompatibilityReport report=FiveberReaderSelector.compatibilityReport();check(report.selectedBackend().equals("HAL_AIDL")&&report.summary().contains("owned channel closed"));
  SystemHalAdapter.transports.clear();SystemHalAdapter.failures.put(CompatibilityReport.Backend.HAL_HIDL,SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE);SystemHalAdapter.order.clear();
  Card denied=new Card(9);denied.denied=true;SystemHalAdapter.transports.put("SIM1",denied);
  check(FiveberReaderSelector.discover().reader.equals("telephony.1.0"));report=FiveberReaderSelector.compatibilityReport();
  check(SystemHalAdapter.order.equals(List.of(CompatibilityReport.Backend.HAL_AIDL,CompatibilityReport.Backend.HAL_HIDL,CompatibilityReport.Backend.TELEPHONY)));
  check(report.attempts().size()==3&&report.attempts().get(0).reason==SecureElementFailure.Reason.ACCESS_DENIED&&report.attempts().get(1).reason==SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE);
  check(report.route().equals("Telephony / telephony.1.0")&&!report.summary().contains("vendor"));
  SystemHalAdapter.failures.clear();SystemHalAdapter.transports.clear();SystemHalAdapter.fallback.clear();SystemHalAdapter.order.clear();
  SystemHalAdapter.transports.put("hidl.SIM1",new Card(9));check(FiveberReaderSelector.discover().reader.equals("hidl.SIM1"));
  check(SystemHalAdapter.order.equals(List.of(CompatibilityReport.Backend.HAL_AIDL,CompatibilityReport.Backend.HAL_HIDL)));
  check(!ReaderDescriptor.writable("hidl.SIM1"));
  SystemHalAdapter.transports.clear();Card absent=new Card(8);absent.absent=true;SystemHalAdapter.transports.put("SIM1",absent);
  SystemHalAdapter.failures.put(CompatibilityReport.Backend.TELEPHONY,SecureElementFailure.Reason.ACCESS_DENIED);
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure expected){check(expected.reason==SecureElementFailure.Reason.ACCESS_DENIED);}
  report=FiveberReaderSelector.compatibilityReport();check(report.selectedBackend().isEmpty()&&report.attempts().size()==4&&report.attempts().get(0).reason==SecureElementFailure.Reason.NO_CARD);
  check(report.summary().contains("no card reported")&&report.summary().contains("Shell access denied"));
  SystemHalAdapter.failures.clear();SystemHalAdapter.transports.clear();SystemHalAdapter.order.clear();Card unsafe=new Card(6);unsafe.failClose=true;SystemHalAdapter.transports.put("SIM1",unsafe);SystemHalAdapter.fallback.put("telephony.1.0",new Card(2));CardTransactionLock.active=true;
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure expected){check(expected.reason==SecureElementFailure.Reason.CHANNEL_CLEANUP_FAILED);}
  check(SystemHalAdapter.order.equals(List.of(CompatibilityReport.Backend.HAL_AIDL)));check(FiveberReaderSelector.compatibilityReport().selectedBackend().isEmpty());check(CardTransactionLock.quarantined);CardTransactionLock.active=false;CardTransactionLock.quarantined=false;
  check(FiveberReaderSelector.compatibilityReport().attempts().get(0).stage==CompatibilityReport.Stage.CHANNEL_CLOSE);
  check(ReaderDescriptor.hidlInstances(List.of("SIM2")).equals(List.of("hidl.SIM2")));rejects(()->ReaderDescriptor.hidlInstances(List.of("SIM1","SIM1")));rejects(()->ReaderDescriptor.hidlInstances(List.of("../SIM1")));
  for(int api:new int[]{28,29,30,31,32,33,34,35,36,37})check(ManagementAvailability.reason(api,new String[]{"arm64-v8a"}).isEmpty());
  FakeHidl fake=new FakeHidl();HidlSecureElementTransport transport=hidl(fake);check(transport.isCardPresent());check(transport.openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0)==2);
  check(Arrays.equals(transport.transmit(command(new byte[]{(byte)0xbf,0x3e,3,0x5c,1,0x5a},2)),new byte[]{(byte)0x90,0}));
  HidlSecureElementTransport owned=transport;rejects(()->owned.transmit(command(new byte[]{(byte)0xbf,0x31,0},2)));rejects(()->owned.closeChannel(1));owned.closeChannel(2);check(fake.closeCount==1);
  FakeHidl rejected=new FakeHidl();rejected.status=2;rejects(()->hidl(rejected).openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0));check(rejected.closeCount==0);
  FakeHidl lost=new FakeHidl();lost.lost=true;try{hidl(lost).openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0);throw new AssertionError();}catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.CHANNEL_CLEANUP_FAILED);}
  FakeHidl duplicate=new FakeHidl();duplicate.duplicate=true;rejects(()->hidl(duplicate).openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0));
  FakeHidl badClose=new FakeHidl();badClose.closeStatus=1;HidlSecureElementTransport unclosed=hidl(badClose);unclosed.openLogicalChannel(TelephonyChannelCodec.unhex(TelephonyChannelCodec.ISDR),(byte)0);rejects(()->unclosed.closeChannel(2));
  SystemHalAdapter.transports.clear();SystemHalAdapter.fallback.clear();SystemHalAdapter.failures.clear();SystemHalAdapter.order.clear();
  SystemHalAdapter.failures.put(CompatibilityReport.Backend.HAL_AIDL,SecureElementFailure.Reason.INVALID_READER_INVENTORY);
  SystemHalAdapter.fallback.put("telephony.1.0",new Card(1));
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.INVALID_READER_INVENTORY);}
  check(SystemHalAdapter.order.equals(List.of(CompatibilityReport.Backend.HAL_AIDL)));
  check(FiveberReaderSelector.compatibilityReport().summary().contains("automatic selection stopped"));
  SystemHalAdapter.transports.clear();SystemHalAdapter.fallback.clear();SystemHalAdapter.failures.clear();SystemHalAdapter.order.clear();
 }


 static void expectReason(Throwing action,SecureElementFailure.Reason reason)throws Exception {
  try{action.run();throw new AssertionError("Expected "+reason);}catch(SecureElementFailure failure){check(failure.reason==reason);}
 }
 static void explicitCardSelectionTests()throws Exception {
  FiveberReaderSelector.clearExplicitSelection();SystemHalAdapter.transports.clear();SystemHalAdapter.fallback.clear();SystemHalAdapter.failures.clear();SystemHalAdapter.order.clear();
  try {
   Card builtin=new Card(10),removable=new Card(20);SystemHalAdapter.fallback.put("telephony.0.1",builtin);SystemHalAdapter.fallback.put("telephony.1.0",removable);
   expectReason(()->FiveberReaderSelector.discover(),SecureElementFailure.Reason.AMBIGUOUS_EUICC);
   List<FiveberReaderSelector.Target> targets=FiveberReaderSelector.discoverChoices();check(targets.size()==2);
   expectReason(()->FiveberReaderSelector.discover(),SecureElementFailure.Reason.AMBIGUOUS_EUICC); // Listing is not selecting.
   FiveberReaderSelector.Target target=targets.get(1);check(target.reader.equals("telephony.1.0"));
   check(target.eidMasked.matches("…[0-9A-F]{4}")&&!target.eidMasked.contains(target.identityHash));
   CardChoicePlan plan=new CardChoicePlan(targets,100);String token=plan.choices.get(1).token;
   check(token.matches("[a-f0-9]{48}")&&!token.equals(plan.choices.get(0).token));
   check(plan.consume(token,100+CardChoicePlan.TTL)==target);rejects(()->plan.consume(token,101));
   CardChoicePlan expired=new CardChoicePlan(targets,100);rejects(()->expired.consume(expired.choices.get(1).token,101+CardChoicePlan.TTL));
   rejects(()->expired.consume(expired.choices.get(1).token,101));
   CardChoicePlan rollback=new CardChoicePlan(targets,100);rejects(()->rollback.consume(rollback.choices.get(1).token,99));
   CardChoicePlan malformed=new CardChoicePlan(targets,100);rejects(()->malformed.consume(null,101));rejects(()->malformed.consume(malformed.choices.get(1).token,101));
   CardChoicePlan unoffered=new CardChoicePlan(targets,100);rejects(()->unoffered.consume("f".repeat(48),101));rejects(()->unoffered.consume(unoffered.choices.get(1).token,101));
   rejects(()->new CardChoicePlan(List.of(target,target),100));rejects(()->new CardChoicePlan(Collections.emptyList(),100));
   rejects(()->new CardChoicePlan(Collections.nCopies(17,target),100));rejects(()->new CardChoicePlan(targets,-1));
   int builtinFrames=builtin.frames;SystemHalAdapter.order.clear();
   check(FiveberReaderSelector.selectExplicitly(target).identityHash.equals(target.identityHash));
   check(FiveberReaderSelector.discover().reader.equals("telephony.1.0")&&builtin.frames==builtinFrames);
   check(SystemHalAdapter.order.equals(List.of(CompatibilityReport.Backend.TELEPHONY,CompatibilityReport.Backend.TELEPHONY)));
   check(FiveberReaderSelector.compatibilityReport().selectedBackend().equals("TELEPHONY"));
   // Same route and same visible EID suffix, different complete identity: no fallback.
   Card replacement=new Card(21);SystemHalAdapter.fallback.put("telephony.1.0",replacement);SystemHalAdapter.order.clear();
   expectReason(()->FiveberReaderSelector.discover(),SecureElementFailure.Reason.SELECTED_CARD_CHANGED);
   check(builtin.frames==builtinFrames&&replacement.closed==1&&SystemHalAdapter.order.equals(List.of(CompatibilityReport.Backend.TELEPHONY)));
   check(FiveberReaderSelector.compatibilityReport().selectedBackend().isEmpty());
   // A stale choice must not replace the previously pinned target with a swapped card.
   expectReason(()->FiveberReaderSelector.selectExplicitly(target),SecureElementFailure.Reason.SELECTED_CARD_CHANGED);
   SystemHalAdapter.fallback.remove("telephony.1.0");SystemHalAdapter.transports.put("SIM1",new Card(30));
   expectReason(()->FiveberReaderSelector.discover(),SecureElementFailure.Reason.SELECTED_CARD_CHANGED);check(builtin.frames==builtinFrames);
   SystemHalAdapter.fallback.put("telephony.1.0",removable);check(FiveberReaderSelector.discover().identityHash.equals(target.identityHash));
   removable.failClose=true;CardTransactionLock.active=true;
   expectReason(()->FiveberReaderSelector.discover(),SecureElementFailure.Reason.CHANNEL_CLEANUP_FAILED);check(CardTransactionLock.quarantined);
   removable.failClose=false;CardTransactionLock.active=false;CardTransactionLock.quarantined=false;
   android.os.Process.uid=10001;rejects(()->FiveberReaderSelector.discoverChoices());rejects(()->FiveberReaderSelector.selectExplicitly(target));rejects(()->FiveberReaderSelector.discover());android.os.Process.uid=2000;
   // A service restart cannot silently persist the previous choice.
   FiveberReaderSelector.clearExplicitSelection();SystemHalAdapter.transports.clear();
   expectReason(()->FiveberReaderSelector.discover(),SecureElementFailure.Reason.AMBIGUOUS_EUICC);
  } finally {
   android.os.Process.uid=2000;FiveberReaderSelector.clearExplicitSelection();CardTransactionLock.active=false;CardTransactionLock.quarantined=false;
   SystemHalAdapter.transports.clear();SystemHalAdapter.fallback.clear();SystemHalAdapter.failures.clear();SystemHalAdapter.order.clear();
  }
 }

 public static void main(String[] args)throws Exception {
  compatibilityDetectionTests();
  telephonyTests();renameTests();protocolTests();scopeTests();singleChannelNotificationTests();deletionNotificationTests();
  check(ManagementAvailability.reason(33,new String[]{"arm64-v8a"}).isEmpty());
  check(ManagementAvailability.reason(36,new String[]{"arm64-v8a"}).isEmpty());
  check(!ManagementAvailability.reason(33,new String[]{"armeabi-v7a"}).isEmpty());
  check(!ManagementAvailability.reason(27,new String[]{"arm64-v8a"}).isEmpty());
  check(ReaderDescriptor.omapiInstances(new String[]{"SIM1"}).equals(List.of("omapi.SIM1")));
  rejects(()->ReaderDescriptor.omapiInstances(new String[]{"SIM1","SIM1"}));
  rejects(()->ReaderDescriptor.omapiInstances(new String[]{"../SIM1"}));
  rejects(()->ReaderDescriptor.instances(new String[]{ReaderDescriptor.PREFIX+"omapi.SIM1"}));
  FakeAidl fake=new FakeAidl();SecureElementTransport aidl=new AidlSecureElementTransport(fake,FakeAidl.class);
  check(aidl.isCardPresent());check(aidl.openLogicalChannel(new byte[]{1},(byte)0)==2);
  aidl.transmit(new byte[]{(byte)0x82,0,0,0,0});aidl.closeChannel(2);check(fake.closed==2&&fake.transmitted==1);
  FakeReader reader=new FakeReader();SecureElementTransport se=omapi(reader);
  check(se.isCardPresent());check(se.openLogicalChannel(new byte[]{1},(byte)0)==1);
  rejects(()->se.openLogicalChannel(new byte[]{1},(byte)0));
  byte[] command={(byte)0x81,(byte)0xE2,(byte)0x91,0,0};se.transmit(command);
  check(command[0]==(byte)0x81&&reader.session.channel.last[0]==(byte)0x80);
  rejects(()->se.transmit(new byte[]{(byte)0x82,0,0,0,0}));
  rejects(()->se.closeChannel(2));check(!reader.session.closed);
  se.closeChannel(1);check(reader.session.closed&&reader.session.channel.closed);
  rejects(()->se.transmit(command));se.closeChannel(1);
  FakeReader denied=new FakeReader();denied.session.denied=true;
  try{omapi(denied).openLogicalChannel(new byte[]{1},(byte)0);throw new AssertionError();}
  catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.ACCESS_DENIED);check(!failure.getMessage().contains("vendor"));}
  check(denied.session.closed);
  FakeReader nullChannel=new FakeReader();nullChannel.session.nullChannel=true;
  try{omapi(nullChannel).openLogicalChannel(new byte[]{1},(byte)0);throw new AssertionError();}catch(IllegalStateException expected){check(nullChannel.session.closed);}
  FakeReader cleanup=new FakeReader();OmapiSecureElementTransport cleanupSe=omapi(cleanup);cleanupSe.openLogicalChannel(new byte[]{1},(byte)0);cleanup.session.channel.failClose=true;
  try{cleanupSe.closeChannel(1);throw new AssertionError();}catch(InvocationTargetException expected){check(cleanup.session.closed);}
  SystemHalAdapter.transports.clear();
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.NO_SERVICE);}
  Card one=new Card(1);one.fragmented=true;SystemHalAdapter.transports.put("omapi.SIM1",one);
  FiveberReaderSelector.Target target=FiveberReaderSelector.discover();check(target.reader.equals("omapi.SIM1"));check(target.identityHash.length()==64&&one.closed==1);
  one.fragmented=false;Card two=new Card(2);SystemHalAdapter.transports.put("omapi.SIM2",two);
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.AMBIGUOUS_EUICC);}
  check(two.closed==1);SystemHalAdapter.transports.clear();
  Card blocked=new Card(1);blocked.denied=true;SystemHalAdapter.transports.put("omapi.SIM1",blocked);
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.ACCESS_DENIED);}
  SystemHalAdapter.transports.clear();Card malformed=new Card(1);malformed.bad=true;SystemHalAdapter.transports.put("SIM1",malformed);
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.INVALID_EID_RESPONSE);}
  check(malformed.closed==1);
  SystemHalAdapter.transports.clear();Card endless=new Card(1);endless.endless=true;SystemHalAdapter.transports.put("SIM1",endless);
  try{FiveberReaderSelector.discover();throw new AssertionError();}catch(SecureElementFailure failure){check(failure.reason==SecureElementFailure.Reason.INVALID_EID_RESPONSE);}
  check(endless.frames==6&&endless.closed==1);android.os.Process.uid=10001;rejects(()->FiveberReaderSelector.discover());android.os.Process.uid=2000;
  CardReadApduPolicy policy=new CardReadApduPolicy(1,false);
  rejects(()->policy.authorize(new byte[]{(byte)0x81,(byte)0xE2,(byte)0x91,0,3,(byte)0xBF,0x31,0}));
  policy.authorize(new byte[]{(byte)0x81,(byte)0xE2,(byte)0x91,0,3,(byte)0xBF,0x2D,0});policy.acceptResponse(new byte[]{(byte)0x90,0});
  rejects(()->policy.authorize(new byte[]{(byte)0x81,(byte)0xC0,0,0,0}));
  List<ProfileLifecycleGuard.Row> rows=List.of(new ProfileLifecycleGuard.Row("8900000000000000001",true,"","", ""),new ProfileLifecycleGuard.Row("8900000000000000002",false,"","",""));
  rejects(()->ProfileLifecycleGuard.validate(ProfileLifecycleGuard.Operation.DELETE,rows,rows.get(0).id,""));
  rejects(()->ProtectedProfilePolicy.validate(ProfileLifecycleGuard.Operation.ENABLE,rows,rows.get(1).id,rows.get(0).id));
  check(!ProfileLifecycleGuard.selectionFingerprint(rows).equals(ProfileLifecycleGuard.selectionFingerprint(List.of(rows.get(1),rows.get(0)))));
  check(SecureElementFailure.message("ACCESS_DENIED").contains("ROM"));check(SecureElementFailure.message("untrusted vendor text").isEmpty());
  explicitCardSelectionTests();
  System.out.println("PASS compatibility and safety regressions: "+assertions+" assertions (synthetic; no live success claim)");
 }
}
