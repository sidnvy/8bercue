package dev.sid.fiveberinspector;

/** Authenticate app calls for Shizuku Binder and the restricted Shell Companion. */
final class ServiceCaller {
    // A request from the legacy Unix-socket backend has no app Binder calling
    // identity. The server first validates SO_PEERCRED and only then installs
    // a per-thread scope. Ordinary apps cannot enter it: it requires Shell UID.
    private static final ThreadLocal<Integer> verifiedSocketPeer = new ThreadLocal<>();

    static final class VerifiedSocketCall implements AutoCloseable {
        private boolean closed;
        private VerifiedSocketCall() {}
        @Override public void close() {
            if (!closed) {
                verifiedSocketPeer.remove();
                closed = true;
            }
        }
    }

    static VerifiedSocketCall enterVerifiedSocketCall(int actualPeerUid, int expectedAppUid) {
        if (android.os.Process.myUid() != android.os.Process.SHELL_UID
                || actualPeerUid != expectedAppUid || expectedAppUid < 10000
                || expectedAppUid > 199999 || verifiedSocketPeer.get() != null)
            throw new SecurityException("Unverified Shell Companion peer");
        verifiedSocketPeer.set(expectedAppUid);
        return new VerifiedSocketCall();
    }

    private static final ThreadLocal<Integer> authenticatedBinderPeer = new ThreadLocal<>();
    static final class ShellCall implements AutoCloseable {
        private final long previous;
        private boolean closed;
        ShellCall() throws Exception {
            requireApp();
            if(authenticatedBinderPeer.get()!=null)throw new SecurityException("Nested identity scope");
            authenticatedBinderPeer.set(android.os.Binder.getCallingUid());
            previous=android.os.Binder.clearCallingIdentity();
        }
        @Override public void close(){if(!closed){closed=true;android.os.Binder.restoreCallingIdentity(previous);authenticatedBinderPeer.remove();}}
    }
    static ShellCall asShell() throws Exception {return new ShellCall();}
    private static int callerUid(){
        Integer socket=verifiedSocketPeer.get(),binder=authenticatedBinderPeer.get();
        return socket!=null?socket:binder!=null?binder:android.os.Binder.getCallingUid();
    }
    private static String installationDigest;
    static synchronized void pinInstallation() throws Exception {
        if(android.os.Process.myUid()!=2000)throw new SecurityException("Shell backend required");
        String path=NativeLpacBundle.installedApk();
        String cp=System.getenv("CLASSPATH");
        if(cp!=null)for(String entry:cp.split(":"))
            if(entry.contains("/dev.sid.fiveberinspector-")&&!entry.equals(path))
                throw new SecurityException("Stale companion APK");
        String current=NativeLpacBundle.digest(new java.io.File(path));
        if(!current.matches("[a-f0-9]{64}"))throw new SecurityException("Missing installed artifact");
        if(installationDigest==null)installationDigest=current;
        else if(!installationDigest.equals(current))throw new SecurityException("App upgraded; restart companion");
    }
    static synchronized void requireApp() throws Exception {
        if (android.os.Process.myUid() != android.os.Process.SHELL_UID)
            throw new SecurityException("Shell backend required");
        pinInstallation();
        if(callerUid()/100000!=0)throw new SecurityException("Only the owner Android user is supported");
        int appUid = -1;
        {
            java.lang.Process p = new ProcessBuilder("/system/bin/cmd", "package", "list", "packages", "-U",
                    "--user", Integer.toString(callerUid() / 100000),
                    "dev.sid.fiveberinspector")
                .redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null"))).start();
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0)
                    throw new SecurityException("Cannot verify caller");
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line; int count=0;
                    while ((line=in.readLine())!=null) {
                        if (++count>16 || line.length()>512)
                            throw new SecurityException("Package response rejected");
                        java.util.regex.Matcher match=java.util.regex.Pattern.compile(
                            "package:dev\\.sid\\.fiveberinspector uid:([0-9]{5,9})").matcher(line.trim());
                        if (match.matches()) {
                            if (appUid != -1)
                                throw new SecurityException("Ambiguous caller package");
                            appUid=Integer.parseInt(match.group(1));
                        }
                    }
                }
            } finally { if (p.isAlive()) p.destroyForcibly(); }
        }
        // Binder Shizuku still requires real app calling UID. Socket calls may
        // substitute only the verified SO_PEERCRED bound to this thread; every
        // request rechecks the package's current Linux UID to stop stale grants.
        Integer peerUid = verifiedSocketPeer.get();
        if (appUid < 10000 || callerUid() != appUid)
            throw new SecurityException("Unauthorized caller");
    }
    private ServiceCaller() {}
}
