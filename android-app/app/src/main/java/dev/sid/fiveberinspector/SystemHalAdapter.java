package dev.sid.fiveberinspector;
import java.io.*;
import java.util.concurrent.TimeUnit;
/** Only Android's installed system Secure Element package supplies the AIDL classes. */
final class SystemHalAdapter {
 static String dexPath()throws Exception {
  java.lang.Process process=new ProcessBuilder("/system/bin/pm","path","com.android.se").redirectError(ProcessBuilder.Redirect.to(new File("/dev/null"))).start();
  try {
   if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new SecurityException("System HAL package unavailable");
   try(Reader in=new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)){
    String line=BoundedLines.read(in,2048);if(line==null||!line.startsWith("package:")||BoundedLines.read(in,2048)!=null)throw new SecurityException("Ambiguous HAL package");
    String path=line.substring(8);File file=new File(path);
    if(!path.matches("/(system|system_ext|product|vendor|apex)/[A-Za-z0-9_./@+-]+\\.apk")||!file.isFile()||!file.getCanonicalPath().equals(path))throw new SecurityException("Untrusted HAL class source");
    return path;
   }
  }finally{if(process.isAlive())process.destroyForcibly();}
 }
 private SystemHalAdapter(){}
}
