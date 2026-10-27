package dev.sid.fiveberinspector;
import java.util.*;
final class ReaderDescriptor {
 static final String PREFIX="android.hardware.secure_element.ISecureElement/";
 static final String TELEPHONY_PREFIX="telephony.";
 static final String OMAPI_PREFIX="omapi.";
 static final String HIDL_PREFIX="hidl.";
 static boolean valid(String name){return name!=null&&name.matches("[A-Za-z0-9_.-]{1,64}")&&!name.equals(".")&&!name.equals("..");}
 static List<String> instances(String[] services){
  if(services==null||services.length>2048)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
  List<String> readers=new ArrayList<>();Set<String> unique=new HashSet<>();
  for(String service:services){if(service==null)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);if(!service.startsWith(PREFIX))continue;
   String name=service.substring(PREFIX.length());if((name.startsWith(OMAPI_PREFIX)||name.startsWith(TELEPHONY_PREFIX)||name.startsWith(HIDL_PREFIX))||!valid(name)||!unique.add(name)||readers.size()>=32)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);readers.add(name);
  }
  return Collections.unmodifiableList(readers);
 }
 static List<String> omapiInstances(String[] names){
  if(names==null||names.length>32)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
  List<String> readers=new ArrayList<>();Set<String> unique=new HashSet<>();
  for(String name:names){
   String encoded=OMAPI_PREFIX+name;
   if(!valid(name)||!valid(encoded)||!unique.add(name))throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
   readers.add(encoded);
  }
  return Collections.unmodifiableList(readers);
 }
 static int[] telephonyIndices(String name){
  if(name==null||!name.matches("telephony\\.[0-7]\\.[0-7]"))throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
  String[] fields=name.split("\\.");return new int[]{Integer.parseInt(fields[1]),Integer.parseInt(fields[2])};
 }
 static List<String> hidlInstances(List<String> names){
  if(names==null||names.size()>32)throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
  List<String> readers=new ArrayList<>();Set<String> unique=new HashSet<>();
  for(String name:names){String encoded=HIDL_PREFIX+name;
   if(!valid(name)||!valid(encoded)||!unique.add(name))throw new SecureElementFailure(SecureElementFailure.Reason.INVALID_READER_INVENTORY);
   readers.add(encoded);
  }
  return Collections.unmodifiableList(readers);
 }
 // A HAL instance name is a logical reader label, not proof of physical slot order.
 static String simLabel(String name){
  if(name==null)return "";
  String raw=name.startsWith(HIDL_PREFIX)?name.substring(HIDL_PREFIX.length()):name.startsWith(OMAPI_PREFIX)?name.substring(OMAPI_PREFIX.length()):name;
  return raw.matches("SIM[1-8]")?raw.substring(3):"";
 }
 static boolean writable(String name){
  if(!valid(name)||name.startsWith(HIDL_PREFIX))return false;
  if(name.startsWith(TELEPHONY_PREFIX))try{telephonyIndices(name);}catch(SecurityException invalid){return false;}
  return true;
 }
 static boolean lifecycleAllowed(String name,ProfileLifecycleGuard.Operation op,boolean enabled){return writable(name)&&op!=null;}
 static void requireLifecycle(String name,ProfileLifecycleGuard.Operation op,boolean enabled){
  if(!lifecycleAllowed(name,op,enabled))throw new SecureElementFailure(SecureElementFailure.Reason.READ_ONLY_TRANSPORT);
 }
 static void requireWritable(String name){if(!writable(name))throw new SecureElementFailure(SecureElementFailure.Reason.READ_ONLY_TRANSPORT);}
 private ReaderDescriptor(){}
}
