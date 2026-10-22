package dev.sid.fiveberinspector;
import java.io.*;
import java.util.concurrent.TimeUnit;
/** System AIDL or framework OMAPI, selected by actual service availability. */
final class SystemHalAdapter {
 static String dexPath()throws Exception {
  java.lang.Process process=new ProcessBuilder("/system/bin/pm","path","com.android.se").redirectError(ProcessBuilder.Redirect.to(new File("/dev/null"))).start();
  try {
   if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new SecureElementFailure(SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE);
   try(Reader in=new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)){
    String line=BoundedLines.read(in,2048);if(line==null||!line.startsWith("package:")||BoundedLines.read(in,2048)!=null)throw new SecureElementFailure(SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE);
    String path=line.substring(8);File file=new File(path);
    if(!path.matches("/(system|system_ext|product|vendor|apex)/[A-Za-z0-9_./@+-]+\\.apk")||!file.isFile()||!file.getCanonicalPath().equals(path))throw new SecureElementFailure(SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE);
    return path;
   }
  }finally{if(process.isAlive())process.destroyForcibly();}
 }
 static void requireShell() {
  if(android.os.Process.myUid()!=android.os.Process.SHELL_UID)
   throw new SecurityException("Authorized Shell identity required");
 }
 private static Object service(String name)throws Exception {
  return Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
 }
 private static Object asInterface(String name,Object binder)throws Exception {
  return Class.forName(name+"$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
 }
 static java.util.List<String> readers()throws Exception {return readers(CompatibilityReport.Backend.HAL_AIDL);}
 static java.util.List<String> readers(CompatibilityReport.Backend backend)throws Exception {
  requireShell();
  try {
   if(backend==CompatibilityReport.Backend.HAL_AIDL) {
    String[] services=(String[])Class.forName("android.os.ServiceManager").getMethod("listServices").invoke(null);
    return ReaderDescriptor.instances(services);
   }
   if(backend==CompatibilityReport.Backend.HAL_HIDL) {
    ClassLoader loader=new dalvik.system.PathClassLoader(dexPath(),SystemHalAdapter.class.getClassLoader());
    Class.forName("android.hardware.secure_element.V1_0.ISecureElement",true,loader);
    // The native platform merges vendor/ODM manifest fragments. Do not guess SIM1/SIM2
    // or require the optional Java HIDL service-manager classes.
    String[] declarations=(String[])Class.forName("android.os.VintfObject").getMethod("report").invoke(null);
    return HidlManifestReaders.parse(declarations);
   }
   if(backend==CompatibilityReport.Backend.TELEPHONY)return telephonyReaders();
   Object binder=service("secure_element");
   if(binder==null)throw new SecureElementFailure(SecureElementFailure.Reason.NO_SERVICE);
   Object omapi=asInterface("android.se.omapi.ISecureElementService",binder);
   String[] names=(String[])Class.forName("android.se.omapi.ISecureElementService").getMethod("getReaders").invoke(omapi);
   return ReaderDescriptor.omapiInstances(names);
  } catch(ClassNotFoundException|NoSuchMethodException error) {
   throw new SecureElementFailure(SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE);
  } catch(java.lang.reflect.InvocationTargetException error) {
   Throwable cause=error.getTargetException();
   if(cause instanceof SecurityException)throw new SecureElementFailure(SecureElementFailure.Reason.ACCESS_DENIED);
   throw new SecureElementFailure(SecureElementFailure.Reason.SERVICE_UNREACHABLE);
  }
 }
 static SecureElementTransport connectForLifecycle(String reader,ProfileLifecycleGuard.Operation op,String id,String nickname)throws Exception {
  requireShell();
  if(!reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX)){ReaderDescriptor.requireWritable(reader);return connect(reader);}
  if(op==null)throw new SecurityException("Operation required");
  return openTelephony(reader).bindLifecycle(op,id,nickname);
 }
 static SecureElementTransport connectForDownload(String reader)throws Exception {
  requireShell();ReaderDescriptor.requireWritable(reader);
  if(!reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX))return connect(reader);
  return openTelephony(reader).bindDownload(new DownloadSessionPolicy());
 }
 static SecureElementTransport connectForNotification(String reader,long sequence)throws Exception {
  requireShell();ReaderDescriptor.requireWritable(reader);
  if(!reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX))return connect(reader);
  return openTelephony(reader).bindNotification(sequence,false);
 }
 static SecureElementTransport connectForNotificationRemoval(String reader,long sequence)throws Exception {
  requireShell();ReaderDescriptor.requireWritable(reader);
  if(!reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX))return connect(reader);
  return openTelephony(reader).bindNotification(sequence,true);
 }
 static SecureElementTransport connectForNotificationMetadata(String reader)throws Exception {
  requireShell();if(!ReaderDescriptor.valid(reader))throw new SecurityException("Invalid reader");
  if(!reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX))return connect(reader);
  return openTelephony(reader).bindMetadata();
 }
 static SecureElementTransport connect(String reader)throws Exception {
  requireShell();
  if(!ReaderDescriptor.valid(reader))throw new SecurityException("Invalid reader");
  try {
   if(reader.startsWith(ReaderDescriptor.HIDL_PREFIX)) {
    ClassLoader loader=new dalvik.system.PathClassLoader(dexPath(),SystemHalAdapter.class.getClassLoader());
    String name="android.hardware.secure_element.V1_0.ISecureElement";
    Class<?> api=Class.forName(name,true,loader);
    Class<?> callback=Class.forName(name+"$openLogicalChannelCallback",true,loader);
    api.getMethod("isCardPresent");api.getMethod("openLogicalChannel",java.util.ArrayList.class,byte.class,callback);
    api.getMethod("transmit",java.util.ArrayList.class);api.getMethod("closeChannel",byte.class);
    Object hal=api.getMethod("getService",String.class,boolean.class).invoke(null,reader.substring(ReaderDescriptor.HIDL_PREFIX.length()),false);
    return new HidlSecureElementTransport(hal,api,callback);
   }
   if(reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX))return openTelephony(reader);
   if(reader.startsWith(ReaderDescriptor.OMAPI_PREFIX)) {
    Object binder=service("secure_element");
    if(binder==null)throw new SecureElementFailure(SecureElementFailure.Reason.NO_SERVICE);
    String name="android.se.omapi.ISecureElementService";
    Object omapi=asInterface(name,binder);
    Object selected=Class.forName(name).getMethod("getReader",String.class).invoke(omapi,reader.substring(ReaderDescriptor.OMAPI_PREFIX.length()));
    if(selected==null)throw new SecureElementFailure(SecureElementFailure.Reason.NO_SERVICE);
    Class<?> listener=Class.forName("android.se.omapi.ISecureElementListener");
    android.os.Binder token=new android.os.Binder();
    Object callback=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(proxy,method,args)->{
     if(method.getName().equals("asBinder"))return token;
     throw new UnsupportedOperationException("Unexpected listener method");
    });
    return new OmapiSecureElementTransport(selected,callback,
     Class.forName("android.se.omapi.ISecureElementReader"),Class.forName("android.se.omapi.ISecureElementSession"),
     Class.forName("android.se.omapi.ISecureElementChannel"),listener);
   }
   Object binder=service(ReaderDescriptor.PREFIX+reader);
   if(binder==null)throw new SecureElementFailure(SecureElementFailure.Reason.NO_SERVICE);
   ClassLoader loader=new dalvik.system.PathClassLoader(dexPath(),SystemHalAdapter.class.getClassLoader());
   String name="android.hardware.secure_element.ISecureElement";
   Class<?> api=Class.forName(name,true,loader);
   api.getMethod("isCardPresent");api.getMethod("openLogicalChannel",byte[].class,byte.class);
   api.getMethod("transmit",byte[].class);api.getMethod("closeChannel",byte.class);
   Object hal=Class.forName(name+"$Stub",true,loader).getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
   return new AidlSecureElementTransport(hal,api);
  } catch(ClassNotFoundException|NoSuchMethodException error) {
   throw new SecureElementFailure(SecureElementFailure.Reason.SYSTEM_INTERFACE_UNAVAILABLE);
  } catch(java.lang.reflect.InvocationTargetException error) {
   if(error.getTargetException() instanceof SecurityException)
    throw new SecureElementFailure(SecureElementFailure.Reason.ACCESS_DENIED);
   throw error;
  }
 }
 static String describeReader(String reader){
  if(!reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX))return "Secure Element reader "+reader;
  int[] address=ReaderDescriptor.telephonyIndices(reader);
  String fallback="Physical slot "+address[0]+", port "+address[1];
  try {
   Object binder=service("phone");if(binder==null)return fallback;
   String name="com.android.internal.telephony.ITelephony";
   Object[] slots=(Object[])Class.forName(name).getMethod("getUiccSlotsInfo",String.class).invoke(asInterface(name,binder),"com.android.shell");
   if(slots==null||slots.length>8||address[0]>=slots.length||slots[address[0]]==null)return fallback;
   Object slot=slots[address[0]];boolean removable=(Boolean)slot.getClass().getMethod("isRemovable").invoke(slot);
   return (removable?"Removable eUICC":"Built-in eUICC")+" · "+fallback;
  }catch(Exception unavailable){return fallback;}
 }
 private static boolean telephonyPortRequests(Class<?> api)throws Exception{
  try{
   Class<?> request=Class.forName("com.android.internal.telephony.IccLogicalChannelRequest");
   api.getMethod("iccOpenLogicalChannel",request);api.getMethod("iccCloseLogicalChannel",request);
   api.getMethod("iccTransmitApduLogicalChannelByPort",int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class,String.class);
   return true;
  }catch(ClassNotFoundException|NoSuchMethodException unsupported){return false;}
 }
 static TelephonySecureElementTransport openTelephony(String reader)throws Exception {
  int[] address=ReaderDescriptor.telephonyIndices(reader);
  Object binder=service("phone");if(binder==null)throw new SecureElementFailure(SecureElementFailure.Reason.NO_SERVICE);
  String name="com.android.internal.telephony.ITelephony";
  Class<?> api=Class.forName(name);Object phone=asInterface(name,binder);Object token=new android.os.Binder();
  if(telephonyPortRequests(api)){
   Class<?> request=Class.forName("com.android.internal.telephony.IccLogicalChannelRequest");
   return new TelephonySecureElementTransport(phone,api,request,address[0],address[1],token);
  }
  int sub=subscriptionForSlot(phone,api,address[0]);
  if(sub<0)throw new SecureElementFailure(SecureElementFailure.Reason.NO_CARD);
  return new TelephonySecureElementTransport(phone,api,sub,token);
 }
 static java.util.List<String> telephonyReaders()throws Exception {
  requireShell();
  Object binder=service("phone");if(binder==null)throw new SecureElementFailure(SecureElementFailure.Reason.NO_SERVICE);
  String name="com.android.internal.telephony.ITelephony";
  Class<?> api=Class.forName(name);Object phone=asInterface(name,binder);
  if(telephonyPortRequests(api))return portReaders(api,phone);
  return subscriptionReaders(api,phone);
 }
 private static java.util.List<String> portReaders(Class<?> api,Object phone)throws Exception {
  java.util.List<String> readers=new java.util.ArrayList<>();
  Object[] slots=(Object[])api.getMethod("getUiccSlotsInfo",String.class).invoke(phone,"com.android.shell");
  if(slots==null||slots.length>8)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
  for(int slot=0;slot<slots.length;slot++){
   Object info=slots[slot];if(info==null||(Integer)info.getClass().getMethod("getCardStateInfo").invoke(info)!=2)continue;
   java.util.Collection<?> ports=(java.util.Collection<?>)info.getClass().getMethod("getPorts").invoke(info);
   if(ports==null||ports.size()>8)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
   for(Object port:ports){
    if(!(Boolean)port.getClass().getMethod("isActive").invoke(port))continue;
    int index=(Integer)port.getClass().getMethod("getPortIndex").invoke(port);
    addReader(readers,ReaderDescriptor.TELEPHONY_PREFIX+slot+"."+index);
   }
  }
  return readers;
 }
 private static java.util.List<String> subscriptionReaders(Class<?> api,Object phone)throws Exception {
  api.getMethod("iccOpenLogicalChannel",int.class,String.class,String.class,int.class);
  api.getMethod("iccCloseLogicalChannel",int.class,int.class);
  api.getMethod("iccTransmitApduLogicalChannel",int.class,int.class,int.class,int.class,int.class,int.class,int.class,String.class);
  java.util.List<String> readers=new java.util.ArrayList<>();
  Object raw=api.getMethod("getUiccSlotsInfo").invoke(phone);
  Object[] slots=raw instanceof Object[]?(Object[])raw:raw instanceof java.util.Collection?((java.util.Collection<?>)raw).toArray():null;
  if(slots==null||slots.length>8)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
  for(int slot=0;slot<slots.length;slot++){
   Object info=slots[slot];if(info==null||(Integer)info.getClass().getMethod("getCardStateInfo").invoke(info)!=2)continue;
   if(subscriptionForSlot(phone,api,slot)<0)continue;
   addReader(readers,ReaderDescriptor.TELEPHONY_PREFIX+slot+".0");
  }
  return readers;
 }
 private static void addReader(java.util.List<String> readers,String reader){
  ReaderDescriptor.telephonyIndices(reader);
  if(readers.contains(reader))throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
  readers.add(reader);
 }
 private static int subscriptionForSlot(Object phone,Class<?> telephony,int physical)throws Exception {
  Object raw=telephony.getMethod("getUiccSlotsInfo").invoke(phone);
  Object[] slots=raw instanceof Object[]?(Object[])raw:raw instanceof java.util.Collection?((java.util.Collection<?>)raw).toArray():null;
  if(slots==null||physical<0||physical>=slots.length||slots[physical]==null)return -1;
  int logical=physical;
  try{logical=(Integer)slots[physical].getClass().getMethod("getLogicalSlotIdx").invoke(slots[physical]);}
  catch(NoSuchMethodException unused){}
  if(logical<0)return -1;
  Object binder=service("isub");if(binder==null)throw new SecureElementFailure(SecureElementFailure.Reason.NO_SERVICE);
  String name="com.android.internal.telephony.ISub";
  Object result=Class.forName(name).getMethod("getSubId",int.class).invoke(asInterface(name,binder),logical);
  if(result instanceof int[]){int[] ids=(int[])result;return ids.length==0?-1:ids[0];}
  return result instanceof Integer?(Integer)result:-1;
 }
 private SystemHalAdapter(){}
}
