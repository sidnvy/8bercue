package dev.sid.fiveberinspector;
import org.json.JSONObject;
/** Compatibility entry point using the shared lifecycle transaction and lock. */
public final class PortableProfileSwitch {
 private PortableProfileSwitch(){}
 public static JSONObject prepare(int index)throws Exception{return PortableProfileLifecycle.prepare("enable",index,"");}
 public static JSONObject execute(String token)throws Exception{return PortableProfileLifecycle.execute(token);}
}
