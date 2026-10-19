package dev.sid.fiveberinspector;

import java.net.URI;
import java.util.Locale;

/** Bounded GSMA format 1 parser shared by camera, image import and manual input. */
public final class ActivationCode {
    public final String host;
    public final boolean confirmationRequired;
    private final String canonical;
    private ActivationCode(String host, String canonical, boolean required) {
        this.host=host;this.canonical=canonical;this.confirmationRequired=required;
    }
    public static String validateHost(String host) {
        if(host==null || host.length()>253 || host.length()<3 || !host.equals(host.trim()))
            throw new IllegalArgumentException("Invalid SM-DP+ address");
        String value=host.toLowerCase(Locale.ROOT);
        // Require DNS names. No URL, port, IP literal, userinfo, local alias or escapes.
        if(!value.matches("[a-z0-9.-]+") || !value.contains(".") || value.matches("[0-9.]+")
                || value.endsWith(".local") || value.endsWith(".localhost") || value.endsWith(".internal"))
            throw new IllegalArgumentException("Use the operator's SM-DP+ domain name");
        for(String label:value.split("\\.",-1))
            if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                throw new IllegalArgumentException("Invalid SM-DP+ domain name");
        return value;
    }
    public static ActivationCode parse(String raw) {
        if(raw==null || raw.length()>1024)throw new IllegalArgumentException("Invalid activation code length");
        String text=raw.trim();
        if(text.regionMatches(true,0,"LPA:",0,4))text=text.substring(4);
        String[] fields=text.split("\\$",-1);
        if(fields.length<3 || fields.length>5 || !"1".equals(fields[0]))
            throw new IllegalArgumentException("Use a GSMA format 1 activation code");
        String host=validateHost(fields[1]);
        if(!fields[2].matches("[A-Za-z0-9-]{1,255}"))
            throw new IllegalArgumentException("Missing or unsupported matching ID");
        // Native lpac ignores OID; never silently discard a caller's server-identity constraint.
        if(fields.length>=4 && !fields[3].isEmpty())
            throw new IllegalArgumentException("Activation codes with an SM-DP+ OID are not supported by this engine");
        boolean required=false;
        if(fields.length==5) {
            if(!"1".equals(fields[4]) && !"0".equals(fields[4]))
                throw new IllegalArgumentException("Invalid confirmation-code flag");
            required="1".equals(fields[4]);
        }
        return new ActivationCode(host,"LPA:1$"+host+"$"+fields[2]+(required?"$$1":""),required);
    }
    public String confirmation(String raw) {
        String value=raw==null?"":raw;
        if(value.isEmpty()) {
            if(confirmationRequired)throw new IllegalArgumentException("This activation code requires a confirmation code");
            return "";
        }
        if(!value.matches("[\\x21-\\x7E]{1,128}"))
            throw new IllegalArgumentException("Invalid confirmation code");
        return value;
    }
    String nativeValue(){return canonical;}
    @Override public String toString(){return "ActivationCode[credentials hidden]";}

    static URI endpoint(String raw,String expectedHost) throws Exception {
        String pinned=validateHost(expectedHost);
        if(raw==null || raw.length()>400)throw new SecurityException("Invalid SM-DP+ endpoint");
        URI uri=new URI(raw);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || !pinned.equalsIgnoreCase(uri.getHost())
            || (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getRawUserInfo()!=null
            || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !uri.equals(uri.normalize()))
            throw new SecurityException("SM-DP+ endpoint does not match the approved transaction");
        String path=uri.getRawPath();
        if(!("/gsma/rsp2/es9plus/initiateAuthentication".equals(path)
            || "/gsma/rsp2/es9plus/authenticateClient".equals(path)
            || "/gsma/rsp2/es9plus/getBoundProfilePackage".equals(path)
            || "/gsma/rsp2/es9plus/cancelSession".equals(path)
            || "/gsma/rsp2/es9plus/handleNotification".equals(path)))
            throw new SecurityException("Unexpected ES9+ endpoint");
        return uri;
    }
}
