package dev.sid.fiveberinspector;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Locale;

/** Fixed native download operation; credentials exist only for the active process, never in logs. */
public final class PhoneProfileDownload {
    private static final String ROOT="/data/local/tmp/fiveber-lpac-lab";
    private static final int TIMEOUT_SECONDS=240;
    private static final int MAX_MESSAGES=20000;
    private static final int MAX_JSON_LINE=9*1024*1024;

    private PhoneProfileDownload() {}

    private static byte[] decodeHex(String value) {
        if(value==null || (value.length()&1)!=0 ||
            value.length()>2*1024*1024)throw new SecurityException("HTTP request too large");
        byte[] buf=new byte[value.length()/2];
        for(int i=0;i<buf.length;i++)
            buf[i]=(byte)Integer.parseInt(value.substring(i*2,2*i+2),16);
        return buf;
    }
    private static String encodeHex(byte[] bytes) {
        StringBuilder b=new StringBuilder(bytes.length*2);
        for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02X",v&255));
        return b.toString();
    }
    private static JSONObject runNative(AtomicReference<Process> child, ActivationCode activation,
            String confirmation, String reader, String hash, DownloadDiagnostics diagnostics) throws Exception {
        Process proc=null;
        PortableDownloadHalBridge bridge=null;
        String latestStage="launch";
        int latestHttpStatus=0;
        try {
            java.util.List<String> args=new java.util.ArrayList<>();
            java.util.Collections.addAll(args,ROOT+"/lpac","profile","download","-a",activation.nativeValue());
            if(!confirmation.isEmpty()){args.add("-c");args.add(confirmation);}
            ProcessBuilder pb=new ProcessBuilder(args);
            pb.directory(new File(ROOT));
            pb.environment().put("LPAC_APDU","stdio");
            pb.environment().put("LPAC_HTTP","stdio");
            pb.environment().put("LPAC_CUSTOM_ES10X_MSS","63");
            pb.environment().put("LPAC_APDU_DEBUG","false");
            pb.environment().put("LPAC_HTTP_DEBUG","false");
            pb.environment().put("LD_LIBRARY_PATH",ROOT);
            // Diagnostic stderr from native libraries may include card material;
            // suppress it, never upload raw APDU/SM-DP+ payloads.
            pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
            DownloadWorkerSupervisor.checkCancelled();
            proc=pb.start();
            child.set(proc);
            diagnostics.nativeStarted();
            DownloadWorkerSupervisor.checkCancelled();
            bridge=new PortableDownloadHalBridge(reader,hash);
            GsmaEs9Transport network=new GsmaEs9Transport(activation.host);

            BufferedReader read=new BufferedReader(new InputStreamReader(
                proc.getInputStream(),StandardCharsets.UTF_8));
            BufferedWriter write=new BufferedWriter(new OutputStreamWriter(
                proc.getOutputStream(),StandardCharsets.UTF_8));
            JSONObject result=null;
            for(int i=0;i<MAX_MESSAGES;i++){
                String line=readBoundedLine(read);
                if(line==null)break;
                DownloadWorkerSupervisor.checkCancelled();
                if(line.length()>MAX_JSON_LINE)
                    throw new SecurityException("Oversized native protocol envelope");
                JSONObject event=new JSONObject(line);
                String type=event.optString("type");
                if("apdu".equals(type)) {
                    String function=event.getJSONObject("payload").optString("func");
                    if(result!=null && !"disconnect".equals(function) && !"logic_channel_close".equals(function))
                        throw new SecurityException("Card request after terminal result");
                    DownloadWorkerSupervisor.checkCancelled();
                    if(result==null)diagnostics.phase("CARD_"+bridge.stage().name());
                    JSONObject reply=bridge.handle(event);
                    write.write(reply.toString());write.newLine();write.flush();
                } else if("http".equals(type)) {
                    if(result!=null)throw new SecurityException("Network request after terminal result");
                    JSONObject p=event.getJSONObject("payload");
                    String url=p.getString("url");
                    java.net.URI safe=network.validateTransactionEndpoint(url);
                    String path=safe.getRawPath();
                    String endpoint=path.substring(path.lastIndexOf('/')+1);
                    bridge.authorizeHttp(endpoint);
                    latestStage="https-"+endpoint;
                    diagnostics.http(endpoint);
                    byte[] body=decodeHex(p.getString("tx"));
                    if("cancelSession".equals(endpoint))bridge.authorizeCancellationHttp(new JSONObject(new String(body,StandardCharsets.UTF_8)).optString("transactionId"));
                    JSONArray headers=p.getJSONArray("headers");
                    DownloadWorkerSupervisor.checkCancelled();
                    GsmaEs9Transport.Reply reply=network.postEs9(url,body,headers);
                    latestHttpStatus=reply.statusCode;
                    diagnostics.httpStatus(reply.statusCode);

                    bridge.httpCompleted(endpoint,reply.statusCode);
                    if(reply.statusCode>=200 && reply.statusCode<300 && !"cancelSession".equals(endpoint)) {
                        // Retain only standard bounded status codes, never server messages or payloads.
                        JSONObject server=new JSONObject(new String(reply.data,StandardCharsets.UTF_8));
                        JSONObject header=server.optJSONObject("header");
                        JSONObject status=header==null?null:header.optJSONObject("functionExecutionStatus");
                        if(status!=null) {
                            JSONObject detail=status.optJSONObject("statusCodeData");
                            diagnostics.server(status.optString("status"),detail==null?null:detail.optString("subjectCode"),detail==null?null:detail.optString("reasonCode"));
                        }
                        if("initiateAuthentication".equals(endpoint) && server.has("transactionId"))bridge.bindTransaction(server.getString("transactionId"));
                    }
                    JSONObject payload=new JSONObject()
                        .put("rcode",reply.statusCode)
                        .put("rx",encodeHex(reply.data));
                    write.write(new JSONObject()
                        .put("type","http").put("payload",payload).toString());
                    write.newLine();write.flush();
                } else if("progress".equals(type)) {
                    // NEVER forward unfiltered SM-DP+ parameters/activation tokens.
                } else if("lpa".equals(type)) {
                    if(result!=null)throw new SecurityException("Multiple terminal results");
                    result=event.getJSONObject("payload");
                    diagnostics.nativeResult(result.optInt("code",-1),result.optString("message"));
                } else {
                    throw new SecurityException("Unexpected native lpac protocol");
                }
            }

            if(result==null)
                throw new IllegalStateException("Missing lpac terminal result at "+latestStage);
            diagnostics.phase("NATIVE_EXIT");
            if(!proc.waitFor(10,TimeUnit.SECONDS))
                throw new IllegalStateException("Native lpac exit timeout");
            if(proc.exitValue()!=0 || result.optInt("code",-1)!=0)
                throw new IllegalStateException("Card/server refused download at "+
                    latestStage+"; lastHttp="+latestHttpStatus);
            if(bridge.installCommands()<1)
                throw new IllegalStateException("No signed Bound Profile Package installed");
            return new JSONObject().put("nativeSuccess",true)
                .put("installCommands",bridge.installCommands())
                .put("apduFrames",bridge.frames())
                .put("lastHttp",latestHttpStatus)
                .put("installedIccid",result.getJSONObject("data").getString("iccid"))
                .put("notificationSequence",result.getJSONObject("data").optLong("seqNumber",-1));
        } catch(Exception failure) {
            diagnostics.failure(failure);throw failure;
        } finally {
            if(proc!=null) {
                try{proc.getOutputStream().close();}catch(Exception ignored){}
                if(proc.isAlive())proc.destroyForcibly();
                boolean stopped=!proc.isAlive();
                if(!stopped)try{stopped=proc.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                diagnostics.nativeStopped(stopped);
                if(stopped)child.set(null);
            }
            if(bridge!=null)bridge.close();
        }
    }
    private static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line=new StringBuilder();
        for(int c;(c=reader.read())!=-1;) {
            if(c=='\n')return line.toString();
            if(line.length()>=MAX_JSON_LINE)throw new IOException("Native envelope too large");
            line.append((char)c);
        }
        return line.length()==0?null:line.toString();
    }
    static final class Outcome {
        final JSONObject result;
        final boolean workerStopped;
        final java.util.Properties diagnostics;
        Outcome(JSONObject result,boolean stopped,java.util.Properties diagnostics){this.result=result;this.workerStopped=stopped;this.diagnostics=diagnostics;}
    }
    static Outcome run(ActivationCode activation,String confirmation,String reader,String hash) throws Exception {
        NativeLpacBundle.ensureInstalled();
        AtomicReference<Process> child=new AtomicReference<>();
        DownloadDiagnostics diagnostics=new DownloadDiagnostics();
        DownloadWorkerSupervisor.Outcome<JSONObject> outcome=DownloadWorkerSupervisor.run(
            ()->runNative(child,activation,confirmation,reader,hash,diagnostics),
            ()->{Process proc=child.get();if(proc!=null)proc.destroyForcibly();},
            TIMEOUT_SECONDS*1000L,15_000L);
        boolean stopped=outcome.workerStopped&&diagnostics.nativeStopped();
        return new Outcome(outcome.result,stopped,diagnostics.snapshot(outcome.result!=null,stopped));
    }
}
