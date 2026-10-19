package dev.sid.fiveberinspector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Internal correlation values only; never exported as anonymous identifiers. */
final class IntentDigests {
 static String of(String value)throws Exception{
  byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
  StringBuilder result=new StringBuilder(64);
  for(byte b:bytes)result.append(Character.forDigit((b&255)>>>4,16)).append(Character.forDigit(b&15,16));
  return result.toString();
 }
 private IntentDigests(){}
}
