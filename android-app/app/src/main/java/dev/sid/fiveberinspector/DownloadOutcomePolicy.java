package dev.sid.fiveberinspector;

import java.util.Properties;

/** A terminal operator refusal before any package command is not a lost installation.
 * Transport failures, later refusals and changed/unreadable inventories remain unknown.
 * This says nothing about whether the activation code can be reused.
 */
final class DownloadOutcomePolicy {
    private DownloadOutcomePolicy() {}

    static boolean notInstalled(boolean nativeSuccess, boolean workerStopped,
            boolean inventoryUnchanged, Properties diagnostics) {
        if(nativeSuccess || !workerStopped || !inventoryUnchanged || diagnostics==null)
            return false;
        String phase=diagnostics.getProperty("downloadPhase","");
        return (phase.equals("INITIATE_AUTHENTICATION") || phase.equals("AUTHENTICATE_CLIENT"))
            && "SERVER_REJECTED".equals(diagnostics.getProperty("downloadFailure"))
            && diagnostics.getProperty("downloadServerStatus","").matches("Failed|Expired")
            && diagnostics.getProperty("downloadHttpStatus","").matches("2[0-9]{2}")
            && diagnostics.getProperty("downloadNativeCode","").matches("-?[1-9][0-9]{0,3}")
            && "0".equals(diagnostics.getProperty("downloadInstallCommands"))
            && "true".equals(diagnostics.getProperty("downloadWorkerStopped"))
            && "true".equals(diagnostics.getProperty("downloadNativeStopped"));
    }
}
