package dev.sid.fiveberinspector;

import android.os.Process;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a PINNED, signed-APK-bundled ARM64 lpac runtime, within Shell-owned
 * /data/local/tmp. No shell commands, filesystem-wide access or external URLs.
 *
 * The native libeuicc/protocol files are obtained only from the currently
 * installed Android APK's fixed assets. SHA-256 from its signed manifest is
 * checked before installing AND before every use, without exposing raw APDUs.
 *
 * Requires UID 2000. Does not modify SIM, firmware, system partitions or root.
 */
public final class NativeLpacBundle {
    private static final String ROOT = "/data/local/tmp/fiveber-lpac-lab";
    private static final String ASSET_DIR = "assets/fiveber-lpac/";
    private static final String[] FILES = {
        "lpac",
        "libeuicc-driver-loader.so",
        "libeuicc-drivers.so",
        "liblpac-utils.so",
        "driver/driver_apdu_stdio.so",
        "driver/driver_http_stdio.so"
    };
    private static final int MAX_FILE_BYTES = 2_000_000;
    private static final String EXPECTED_REVISION = "82ada9e53251c7f0cb08bfd9060b17019a30242c";
    private NativeLpacBundle() {}

    private static String toHex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for(byte b:bytes)s.append(String.format("%02x", b & 255));
        return s.toString();
    }
    static String digest(File file) throws Exception {
        if(java.nio.file.Files.isSymbolicLink(file.toPath()))throw new SecurityException("Unsafe native file");
        if(!file.isFile()) return "";
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while((n=in.read(buffer))>=0) {
                if(n>0)sha.update(buffer,0,n);
            }
        }
        return toHex(sha.digest());
    }
    private static boolean isInstalledPackagePath(String path) {
        return path != null && path.startsWith("/data/app/")
            && path.endsWith("/base.apk")
            && path.contains("/dev.sid.fiveberinspector-")
            && path.matches("/data/app/[A-Za-z0-9_~./+=-]+/base\\.apk")
            && new File(path).isFile();
    }

    static String installedApk() throws Exception {
        // For the original ADB Shell Companion the APK is the first
        // CLASSPATH entry. For Shizuku UserService this environment variable
        // is not part of the public API, so do not assume it exists.
        // Fixed package lookup, NOT caller input or a general command shell.
        // The package manager itself returns the registered signed APK path.
        java.lang.Process lookup = new ProcessBuilder(
            "/system/bin/pm", "path", "dev.sid.fiveberinspector")
            .redirectErrorStream(true).start();
        try {
            if (!lookup.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                lookup.destroyForcibly();
                throw new SecurityException("Timed out resolving installed APK");
            }
            if (lookup.exitValue() != 0)
                throw new SecurityException("Cannot resolve installed APK");
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    lookup.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                String resolved = null;
                int seen = 0;
                while ((line = input.readLine()) != null) {
                    if (line.length() > 2048 || ++seen > 10)
                        throw new SecurityException("Unexpected package resolver output");
                    if (line.startsWith("package:") && line.endsWith("/base.apk")) {
                        String path = line.substring("package:".length());
                        if (!isInstalledPackagePath(path) || resolved != null)
                            throw new SecurityException("Untrusted or duplicate APK path");
                        resolved = path;
                    }
                }
                if (resolved != null) return resolved;
            }
        } finally {
            if (lookup.isAlive()) lookup.destroyForcibly();
        }
        throw new SecurityException("Current signed APK could not be found");
    }
    private static void secureDir(File dir) throws Exception {
        if(!dir.getCanonicalFile().equals(dir.getAbsoluteFile()) || java.nio.file.Files.isSymbolicLink(dir.toPath()))
            throw new SecurityException("Unsafe native directory");
        if(!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Native runtime directory unavailable");
        Os.chmod(dir.getAbsolutePath(), 0700);
    }
    public static synchronized void ensureInstalled() throws Exception {
        if(Process.myUid()!=Process.SHELL_UID)
            throw new SecurityException("Shell-only native installation");
        if(!java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a"))
            throw new SecurityException("Native management requires ARM64");
        File root=new File(ROOT);
        secureDir(root);
        secureDir(new File(root,"driver"));
        try (ZipFile apk=new ZipFile(installedApk())) {
            ZipEntry jsonEntry=apk.getEntry(ASSET_DIR+"manifest.json");
            if(jsonEntry==null || jsonEntry.getSize()>8192 || jsonEntry.getSize()<0)
                throw new SecurityException("Missing bounded native payload manifest");
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            try(InputStream input=apk.getInputStream(jsonEntry)) {
                byte[] chunk=new byte[2048];
                int n;
                while((n=input.read(chunk))!=-1) {
                    if(data.size()+n>8192)throw new SecurityException("Native manifest too large");
                    data.write(chunk,0,n);
                }
            }
            JSONObject manifest=new JSONObject(new String(data.toByteArray(),StandardCharsets.UTF_8));
            if(manifest.getInt("format")!=1 || !"arm64-v8a".equals(manifest.getString("abi"))
                || !EXPECTED_REVISION.equals(manifest.getString("lpacRevision")))
                throw new SecurityException("Unknown native binary version or ABI");
            JSONObject files=manifest.getJSONObject("files");
            if(files.length()!=FILES.length)
                throw new SecurityException("Unexpected number of native assets");
            int updated=0;
            for(String name:FILES) {
                JSONObject description=files.getJSONObject(name);
                String expected=description.getString("sha256");
                long length=description.getLong("size");
                if(!expected.matches("[0-9a-f]{64}") || length<=0 || length>MAX_FILE_BYTES)
                    throw new SecurityException("Invalid native asset specification");
                File output=new File(root,name);
                String rootCanonical=root.getCanonicalPath()+"/";
                if(!output.getCanonicalPath().startsWith(rootCanonical))
                    throw new SecurityException("Native output outside sandbox");
                if(output.isFile() && output.length()==length && expected.equals(digest(output)))
                    continue;
                ZipEntry entry=apk.getEntry(ASSET_DIR+name);
                if(entry==null || entry.getSize()!=length)
                    throw new SecurityException("Missing signed native asset");
                File temp=new File(output.getParentFile(), "."+output.getName()+".new");
                if(java.nio.file.Files.isSymbolicLink(temp.toPath()) || java.nio.file.Files.isSymbolicLink(output.toPath()))
                    throw new SecurityException("Unsafe native extraction path");
                Os.chmod(output.getParentFile().getAbsolutePath(),0700);
                MessageDigest sha=MessageDigest.getInstance("SHA-256");
                try(InputStream in=apk.getInputStream(entry);
                    OutputStream out=java.nio.file.Files.newOutputStream(temp.toPath(),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer=new byte[8192];
                    long size=0;
                    int n;
                    while((n=in.read(buffer))!=-1) {
                        size+=n;
                        if(size>MAX_FILE_BYTES)throw new SecurityException("Oversized native component");
                        if(n>0) {out.write(buffer,0,n); sha.update(buffer,0,n);}
                    }
                    if(size!=length || !expected.equals(toHex(sha.digest())))
                        throw new SecurityException("Native APK asset checksum mismatch");
                } catch(Exception e) {
                    temp.delete();
                    throw e;
                }
                Os.chmod(temp.getAbsolutePath(),name.equals("lpac")?0700:0600);
                if(!temp.renameTo(output)) {
                    temp.delete();
                    throw new IOException("Native asset atomically replacing failed");
                }
                updated++;
            }
            if(updated>0)System.err.println("AUDIT native_lpac_assets_installed="+updated);
        }
    }
}
