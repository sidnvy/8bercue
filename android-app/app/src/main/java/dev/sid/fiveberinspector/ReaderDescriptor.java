package dev.sid.fiveberinspector;
import java.util.*;
final class ReaderDescriptor {
 static final String PREFIX="android.hardware.secure_element.ISecureElement/";
 static boolean valid(String name){return name!=null&&name.matches("[A-Za-z0-9_.-]{1,64}")&&!name.equals(".")&&!name.equals("..");}
 static List<String> instances(String[] services){
  if(services==null||services.length>2048)throw new SecurityException("Invalid service inventory");
  List<String> readers=new ArrayList<>();Set<String> unique=new HashSet<>();
  for(String service:services){if(service==null)throw new SecurityException("Invalid service name");if(!service.startsWith(PREFIX))continue;
   String name=service.substring(PREFIX.length());if(!valid(name)||!unique.add(name)||readers.size()>=32)throw new SecurityException("Ambiguous reader inventory");readers.add(name);
  }
  return Collections.unmodifiableList(readers);
 }
 private ReaderDescriptor(){}
}
