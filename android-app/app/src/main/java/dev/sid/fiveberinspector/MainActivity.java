package dev.sid.fiveberinspector;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;
import org.json.*;

/** Three-screen local manager. No credential or transaction replay on recreation. */
public final class MainActivity extends Activity {
  static int BG, SURFACE, TEXT, MUTED, ACCENT, LINE, TONAL, ON_TONAL, ERROR;
  private boolean darkTheme;
  private int selectedProfile = -1;
  private String selectedIccid = "", selectedProfileKey = "";
  private JSONObject detailProfile;
  private String connectionCommand = "";
  private long connectionDeadline;
  private boolean connectionExpired;
  private TextView connectionCountdown;
  private String connectionFeedback = "";
  private boolean restoreDetailAfterRead;
  private final java.util.ArrayList<String> recentResults = new java.util.ArrayList<>();
  private static final int QR = 40, IMAGE = 41, DIAGNOSTIC_EXPORT = 42;
  private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
  private static final ExecutorService DIAGNOSTIC_WORK = Executors.newSingleThreadExecutor();
  private static final UiOperationState OPERATIONS = new UiOperationState();
  private IProfileService backend;
  private boolean directManagement;
  private boolean fallback, companionManagement, destroyed, resumed, needsConnectionRead;
  private android.net.Uri pendingImport;
  private int screen;
  private JSONObject inventory = new JSONObject(), cardInfo = new JSONObject(), compatibility = new JSONObject();
  private LinearLayout content, statusBox;
  private TextView statusTitle, statusDetail;
  private EditText activation, confirmation;
  private final AddDraft draft = new AddDraft();
  private final ProfileDetailSession detailSelection = new ProfileDetailSession();
  private final java.util.IdentityHashMap<View,Boolean> controlAvailability = new java.util.IdentityHashMap<>();
  private Dialog installResultDialog;
  private Dialog activeSheet;
  private Dialog bootstrapSheet;
  private boolean showConfirmation;
  private String notificationStatus = "";
  private boolean notificationCleanupAvailable;
  // Pilot-only, in-Activity guard against accidental duplicate read attempts.
  private boolean binderReadRequested;
  private boolean binderDetectionRequested;
  private boolean binderReadOnlyInventory;
  private long navigationSession;
  private final java.util.ArrayList<View> sheetControls = new java.util.ArrayList<>();
  private final java.util.ArrayList<View> taskControls = new java.util.ArrayList<>();
  private ProgressBar busyProgress;
  private String title = "Connect your card", detail = "Connect using your computer to check this card.",
                 notice = "";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable watch = new Runnable() {
    public void run() {
      if (destroyed || !resumed)
        return;
      if (!OPERATIONS.running()) {
        if (backend == null) connectDirect();
        if (needsConnectionRead && backend != null) {
          needsConnectionRead = false;
          checkRecovery();
        } else if (OPERATIONS.claimReconciliation(resumed)) {
          if (OPERATIONS.completedMutation()) {
            notice = "An operation ended while this screen was unavailable. Verify the card before "
                     + "continuing; do not repeat the operation.";
            statusPreservingOutcome("Needs verification", "Reading the card to establish its current state.");
          } else
            status("Check ended", "Refreshing the card after the interrupted screen.");
          render();
          if (backend != null)
            checkRecovery();
          else if (fallback)
            refresh();
        } else if (pendingImport != null) {
          android.net.Uri uri = pendingImport;
          pendingImport = null;
          importImage(uri);
        }
      }
      if(screen==6 && backend==null){
        long remaining=connectionDeadline-android.os.SystemClock.elapsedRealtime();
        if(connectionCountdown!=null)connectionCountdown.setText(ConnectionFeedback.countdown(remaining));
        if(!connectionExpired && remaining<=0){
          connectionExpired=true;
          compatibilityFailure(CompatibilityDiagnostics.Stage.BOOTSTRAP,
              CompatibilityDiagnostics.Failure.BOOTSTRAP_TIMEOUT);
          statusPreservingOutcome("Connection window expired","Tap Start connection again, then rerun the same command.");
          render();
        } else if(!connectionExpired){
          String feedback=CompanionBinderProvider.bootstrapFeedback();
          if(!feedback.isEmpty()&&!feedback.equals(connectionFeedback)){
            connectionFeedback=feedback;
            if (!"STARTING".equals(feedback)) compatibilityFailure(
                CompatibilityDiagnostics.Stage.BOOTSTRAP,
                ("APP_CHANGED".equals(feedback) || "WINDOW_CHANGED".equals(feedback))
                    ? CompatibilityDiagnostics.Failure.BINDER_HANDSHAKE_FAILED
                    : CompatibilityDiagnostics.Failure.BOOTSTRAP_INCOMPLETE);
            statusPreservingOutcome("STARTING".equals(feedback)?"Starting local service":"Connection not completed",ConnectionFeedback.message(feedback));
            if (!"STARTING".equals(feedback)) render();
          }
        }
      }
      handler.postDelayed(this, 1000);
    }
  };
  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    applyPalette();
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    if (saved != null)
      screen = saved.getInt("screen", 0) == 2 ? 2 : 0;
    SafeDiagnosticBundle.mark(SafeDiagnosticBundle.Step.APP_STARTED);
    if (getPreferences(0).getBoolean("pending", false))
      notice = "Card and operation state need verification. Open Settings → Verify card and recovery. "
          + "Do not submit its activation code again.";
    render();
  }
  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    connectExistingCompanion();
    handler.removeCallbacks(watch);
    handler.post(watch);
  }
  @Override
  protected void onPause() {
    resumed = false;
    if(screen==1){clearCredentials();draft.changed();if(activeSheet!=null)activeSheet.dismiss();}
    // Invalidate pending prepare callbacks across any background/foreground boundary.
    navigationSession++;
    clearDetailSelection();
    if(screen==3||screen==4)screen=0;
    handler.removeCallbacks(watch);
    super.onPause();
  }
  @Override
  public void onSaveInstanceState(Bundle out) {
    out.putInt("screen", screen);
    super.onSaveInstanceState(out);
  }
  @Override
  public void onConfigurationChanged(android.content.res.Configuration config) {
    super.onConfigurationChanged(config);
  }
  private String managementUnavailable() { return ManagementAvailability.reason(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS); }
  private void connectDirect() {
    if(destroyed||!resumed||OPERATIONS.running()||backend!=null)return;
    IProfileService service=CompanionBinderProvider.management();
    if(service==null)return;
    try {
      service.asBinder().linkToDeath(()->runOnUiThread(()->{
        if(backend!=service)return;
        backend=null;compatibility=new JSONObject();navigationSession++;clearDetailSelection();
        compatibilityFailure(CompatibilityDiagnostics.Stage.BINDER,
            CompatibilityDiagnostics.Failure.SERVICE_DISCONNECTED);
        statusPreservingOutcome("Connection lost", "Start the computer connection again. Pending operations will not be repeated.");render();
      }),0);
      backend=service;directManagement=true;companionManagement=true;fallback=false;
      CompatibilityDiagnostics.ready(CompatibilityDiagnostics.Transport.ADB_COMPANION);
      if(bootstrapSheet!=null){bootstrapSheet.dismiss();bootstrapSheet=null;}
      if(screen==6)screen=0;
      status("Connected", "Checking card and recovery state…");render();checkRecovery();
    }catch(Exception unavailable){
      backend=null;compatibility=new JSONObject();
      compatibilityFailure(CompatibilityDiagnostics.Stage.BINDER,
          CompatibilityDiagnostics.Failure.BINDER_HANDSHAKE_FAILED);
    }
  }
  private void beginDirectBootstrap() {
    if (!managementUnavailable().isEmpty()) {
      compatibilityFailure(CompatibilityDiagnostics.Stage.BOOTSTRAP,
          CompatibilityDiagnostics.Failure.DEVICE_UNSUPPORTED);
      status("Device not supported by this build", managementUnavailable());render();
      return;
    }
    if(OPERATIONS.running()){status("Operation in progress", "Wait for verification before changing connection.");return;}
    try {CompanionBinderProvider.beginManagementBootstrap();}
    catch(SecurityException active){backend=null;compatibility=new JSONObject();connectDirect();return;}
    backend=null;compatibility=new JSONObject();fallback=false;binderReadOnlyInventory=false;directManagement=true;companionManagement=true;
    navigationSession++;clearDetailSelection();

    try { connectionCommand=ConnectionCommand.forApk(getApplicationInfo().sourceDir); }
    catch(IllegalArgumentException invalid){status("Command unavailable","Reinstall the verified APK before connecting.");render();return;}
    CompatibilityDiagnostics.started(CompatibilityDiagnostics.Transport.ADB_COMPANION);
    connectionDeadline=android.os.SystemClock.elapsedRealtime()+120000;connectionExpired=false;connectionFeedback="";statusPreservingOutcome("Waiting for computer","Copy the command below and run it within two minutes.");screen=6;render();
  }

  private boolean useCompanion() {
    return LabSupport.socketEligible()
        && getPreferences(0).getBoolean("preferCompanion", true);
  }
  private void connectExistingCompanion() {
    if(destroyed||!resumed||binderReadOnlyInventory||!managementUnavailable().isEmpty())return;
    connectDirect();
  }
  private void connect() {
    if (!managementUnavailable().isEmpty()) { status("Device not supported by this build",managementUnavailable());return; }
    connectExistingCompanion();
    if(backend==null)status("Connect your card","Choose Connect using computer and copy and run its ADB command.");
  }
  private interface Job {
    JSONObject run() throws Exception;
  }
  private interface Done {
    void accept(JSONObject value) throws Exception;
  }
  private void run(String label, boolean mutation, Job job, Done done) {
    if (destroyed || !resumed)
      return;
    final long taskNavigation = navigationSession;
    final long taskDraft = draft.revision();
    final boolean contextual = label.equals("Reading QR image") || label.equals("Validating activation code") || label.startsWith("Reviewing ");
    final long ticket = OPERATIONS.begin(label, mutation);
    if (ticket == 0)
      return;
    if (mutation)
      getPreferences(0).edit().putBoolean("pending", true).commit();
    statusPreservingOutcome(label,
        mutation
            ? "One attempt in progress. Keep the phone powered on; the result will be verified."
            : label.equals("Reading QR image") ? "Decoding the image on this phone. No card changes." : "Reading and checking your card. No changes are being made.");
    WORK.execute(() -> {
      JSONObject value = null;
      boolean failed = false, permissionDenied = false;
      try {
        value = job.run();
      } catch (Throwable e) {
        failed = true;
        permissionDenied = e instanceof SecurityException; // never read the exception message
      }
      final JSONObject result = value;
      final boolean error = failed;
      final boolean denied = permissionDenied;
      handler.post(() -> {
        boolean handleResult = !destroyed && resumed;
        OPERATIONS.finish(ticket, handleResult);
        if (handleResult) updateStatusRegion();
        if (!handleResult)
          return;
        if (error) {
          if (contextual && (navigationSession != taskNavigation || (screen == 1 && !draft.isCurrent(taskDraft)))) return;
          String message = mutation
              ? "Result unknown. Reconnect and verify the card. Do not retry automatically."
              : label.equals("Reading QR image")
              ? "No readable QR code was found. Choose a clear image or enter the activation code "
                  + "manually."
              : "Could not complete the check. Reconnect and try a read-only refresh.";
          if (mutation)
            notice = message;
          if (!mutation && label.equals("Reading your eSIMs"))
            compatibilityFailure(CompatibilityDiagnostics.Stage.INVENTORY,
                denied ? CompatibilityDiagnostics.Failure.INVENTORY_ACCESS_DENIED
                       : CompatibilityDiagnostics.Failure.INVENTORY_READ_FAILED);
          status(mutation ? "Needs verification" : "Check unavailable", message);
          render();
          return;
        }
        try {
          if (!mutation)
            statusPreservingOutcome("Check complete", "No changes were made.");
          done.accept(result);
        } catch (Exception e) {
          notice = "The response could not be verified. Read the card again before continuing.";
          render();
        }
      });
    });
  }
  private void probeFallback() {
    if (!LabSupport.socketEligible()) return;
    run("Checking companion", false,
        () -> {
          try { ShellCompanionClient.status(); }
          catch (Exception noSocket) {
            if (BuildConfig.DEBUG) android.util.Log.i("FiveberCompanionPilot",
                "socket_status_unavailable exception=" + noSocket.getClass().getSimpleName());
            return new JSONObject().put("transportUnavailable", true);
          }
          if (BuildConfig.DEBUG) android.util.Log.i("FiveberCompanionPilot", "socket_status_verified");
          boolean generic = false;
          try {
            JSONObject caps = ShellCompanionClient.capabilities();
            generic = caps.optInt("version", 0) == 2
                && caps.optBoolean("genericProfileManagement", false)
                && caps.optBoolean("peerCredentialRequired", false)
                && !caps.optBoolean("arbitraryShellAllowed", true)
                && !caps.optBoolean("arbitraryApduAllowed", true);
          } catch (Exception oldCompanion) {
            // v1 socket remains available only for read-only fallback.
          }
          if (BuildConfig.DEBUG) android.util.Log.i("FiveberCompanionPilot",
              "protocol_v2=" + generic);
          JSONObject profiles = generic
              ? ShellCompanionClient.service("listProfiles",null,null,null,null,null)
              : ShellCompanionClient.list();
          if (!profiles.optBoolean("ok", true) || !profiles.has("profiles"))
            throw new IllegalStateException("Companion inventory not verified");
          // A bounded one-time read-only diagnostics workflow provides a
          // real management PREPARE test even when accessibility UI is
          // unavailable. It never calls execute, and intentionally discards
          // operation tokens rather than logging or storing them.
          if (BuildConfig.DEBUG) android.util.Log.i("FiveberCompanionPilot",
              "profile_list_observed; probeAlreadyAttempted="
              + getPreferences(0).getBoolean("companionV2RollbackPreviewAttempted", false));
          if (generic && BuildConfig.DEBUG
              && LabSupport.socketEligible()
              && !getPreferences(0).getBoolean("companionV2RollbackPreviewAttempted", false)) {
            getPreferences(0).edit().putBoolean("companionV2RollbackPreviewAttempted", true).commit();
            LabSupport.verify(this, profiles);
            if (BuildConfig.DEBUG) android.util.Log.i("FiveberCompanionPilot",
                "rollback_readonly_preflight_finished");
          }
          return new JSONObject().put("inventory", profiles).put("generic", generic);
        },
        r -> {
          if (r.optBoolean("transportUnavailable", false)) {
            // A missing legacy socket must not change connection modes or replay an operation.
            compatibilityFailure(CompatibilityDiagnostics.Stage.SERVICE,
                CompatibilityDiagnostics.Failure.COMPANION_UNAVAILABLE);
            status("Companion unavailable",
                "Use Connect using computer and copy and run the ADB command shown in the app.");
            return;
          }
          CompatibilityDiagnostics.ready(CompatibilityDiagnostics.Transport.ADB_COMPANION);
          fallback = true;
          companionManagement = r.optBoolean("generic", false);
          backend = companionManagement ? new CompanionProfileBackend() : null;
          inventory = r.getJSONObject("inventory");
          needsConnectionRead = companionManagement;
          status("Card connected", companionManagement
              ? "Computer connection ready. Reconnect after a reboot or app update."
              : "Legacy companion is read-only. Use Connect using computer for management.");
          render();
        });
  }
  private void selectLegacyCompanion() {
    if(OPERATIONS.running()){status("Operation in progress","Wait for verification before changing connection.");return;}
    if(!LabSupport.socketEligible())return;
    directManagement=false;backend=null;compatibility=new JSONObject();fallback=false;companionManagement=false;
    binderReadOnlyInventory=false;inventory=new JSONObject();probeFallback();
  }
  private void refresh() {
    final IProfileService s = backend;
    if (s == null && binderReadOnlyInventory) {
      status("Read-only inventory", "Single-use Binder read complete. Reconnect the service before another independent read.");
      return;
    }
    if (s == null && !fallback) {
      connect();
      return;
    }
    run("Reading your eSIMs", false,
        () -> s != null ? new JSONObject(s.listProfiles()) : ShellCompanionClient.list(), r -> {
          compatibility=r.optJSONObject("compatibility")==null?new JSONObject():r.optJSONObject("compatibility");
          if (!r.optBoolean("ok", true)) {
            compatibilityFailure(CompatibilityDiagnostics.Stage.INVENTORY,
                CompatibilityDiagnostics.Failure.INVENTORY_READ_FAILED);
            inventory=new JSONObject();notificationCleanupAvailable=false;clearDetailSelection();
            String limitation=SecureElementFailure.message(r.optString("capabilityError", ""));
            status("Card unavailable", (limitation.isEmpty()
                ? "No verified inventory was returned. Reconnect and refresh." : limitation)+
                  (compatibility.length()>0?" Open Compatibility check for the result of each channel.":""));
            render();return;
          }
          inventory = r;
          if (screen==3 || screen==4 || restoreDetailAfterRead) {
            JSONArray rows=inventory.optJSONArray("profiles");int found=-1;
            if(rows!=null)for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i)!=null&&!selectedProfileKey.isEmpty()&&selectedProfileKey.equals(rows.optJSONObject(i).optString("selectionId"))) {if(found!=-1){found=-1;break;}found=i;}
            if(found>=0){selectedProfile=found;detailProfile=rows.optJSONObject(found);detailSelection.open(inventory,backend,detailProfile,found);screen=3;}
            else {selectedProfile=-1;detailProfile=null;screen=0;}
            restoreDetailAfterRead=false;
          }
          statusPreservingOutcome(
              "Card connected", r.optString("connectionNotice", "Enabled on card does not confirm cellular network registration."));
          render();
        });
  }
  private void checkRecovery() {
    final IProfileService s = backend;
    if (s == null)
      return;
    run("Checking recovery", false, () -> new JSONObject(s.getOperationStatus()), r -> {
      if (!r.optBoolean("ok", false)) {
        if(RecoveryUiPolicy.needsCardChoice(r.optString("capabilityError",""))){
          inventory=new JSONObject();notificationCleanupAvailable=false;clearDetailSelection();
          statusPreservingOutcome("Choose a card","More than one eUICC is readable. Select the card you want to manage.");render();return;
        }
        compatibilityFailure(CompatibilityDiagnostics.Stage.INVENTORY,
            CompatibilityDiagnostics.Failure.INVENTORY_READ_FAILED);
        getPreferences(0).edit().putBoolean("pending", true).commit();
        inventory = new JSONObject();notificationCleanupAvailable = false;clearDetailSelection();
        String limitation=SecureElementFailure.message(r.optString("capabilityError", ""));
        status("Recovery state unavailable", limitation.isEmpty()
            ? "Changes are blocked until the safety record can be checked. Do not clear app data or repeat the previous operation."
            : limitation+" Changes remain blocked until card and recovery verification can complete.");
        render();return;
      }
      String priorNotificationMessage = NotificationPresentation.message(notificationStatus);
      notificationStatus = r.optString("notificationStatus", "");
      if ("none".equals(notificationStatus)) notificationStatus = "";
      if (notice.equals(priorNotificationMessage)) notice = notificationStatus.isEmpty() ? "" : NotificationPresentation.message(notificationStatus);
      notificationCleanupAvailable = r.optBoolean("notificationCleanupAvailable", false);
      String phase = r.optString("status", r.optString("stage", "unknown"));
      boolean recoveryBlocked = RecoveryUiPolicy.blocks(true, r.optBoolean("requiresReconciliation", true), r.optBoolean("reviewRequired", true), phase);
      if (recoveryBlocked) getPreferences(0).edit().putBoolean("pending", true).commit();
      if (recoveryBlocked || getPreferences(0).getBoolean("pending", false))
        notice = "Recovery check: " + safeState(phase)
            + ". Use Verify card and recovery before making more changes.";
      if (r.optBoolean("notificationReviewRequired", false))
        notice += (notice.isEmpty() ? "" : "\n") + NotificationPresentation.message(notificationStatus);
      refresh();
    });
  }
  private void reconcile() {
    final IProfileService s = backend;
    if (s == null) {
      connect();
      return;
    }
    run("Verifying card and recovery", false, () -> new JSONObject(s.reconcileOperation()), r -> {
      boolean review = RecoveryUiPolicy.blocks(r.optBoolean("ok", false), r.optBoolean("requiresReconciliation", false), r.optBoolean("reviewRequired", true), r.optString("status", "unknown"));
      notice = review ? "Card read completed; the previous operation still requires review. Do not "
              + "repeat a download or destructive action."
                      : "reviewed_abandoned".equals(r.optString("status"))
                          ? "The unknown download was reviewed and abandoned. The profile inventory is unchanged. Its activation code may have been consumed; do not reuse it. New operations are available."
                          : "Read-only reconciliation completed. Check the current profile inventory.";
      if (!review)
        getPreferences(0).edit().remove("pending").commit();
      refresh();
    });
  }
  private void prepare(String op, int index, String nickname) {
    final IProfileService s = backend;
    if (s == null || OPERATIONS.running())
      return;
    final long previewSession = navigationSession;
    final JSONObject previewInventory = inventory;
    run("Reviewing " + op, false,
        ()
            -> new JSONObject(op.equals("switch") ? s.prepareSwitch(index)
                    : op.equals("rename")         ? s.prepareRename(index, nickname)
                    : op.equals("delete")         ? s.prepareDelete(index)
                                                  : s.prepareDisable(index)),
        r -> {
          if (previewSession != navigationSession || inventory != previewInventory || backend != s) return;
          if (!r.optBoolean("ok", true)) {
            notice = "The card safety policy did not allow this action. Refresh the inventory and "
                + "review recovery.";
            render();
            return;
          }
          if (!r.optBoolean("writeQualified", false)) {
            notice = "Management is unavailable for this card identity or local test scope. Reconnect and refresh.";
            render();
            return;
          }
          String token = r.optString("token", "");
          if (token.isEmpty()) {
            notice = "No valid confirmation token was returned.";
            render();
            return;
          }
          if (ProfileActionPolicy.executesDirectly(op)) {
            execute(s,token,op);return;
          }
          String warning = "This permanently removes the selected profile from this card. Its activation code may not work again.";
          status("Review deletion", "Check the selected card and profile before deleting.");
          final boolean[] confirmed = {false};
          Sheet preview = new Sheet(op.equals("switch") ? "Activate eSIM?" : op.equals("delete") ? "Delete eSIM?" : op.equals("disable") ? "Disable eSIM?" : "Save nickname?");
          if (op.equals("switch")) preview.fact("Currently enabled", r.optString("from", "").isEmpty() ? "None on this card" : r.optString("from"));
          preview.fact("Selected eSIM", r.optString("to", "Selected eSIM"));preview.copy(warning);
          preview.button(op.equals("delete") ? "Delete permanently" : op.equals("switch") ? "Activate once" : op.equals("disable") ? "Disable once" : "Save nickname", true, () -> {
            confirmed[0] = true;preview.dialog.dismiss();execute(s, token, op);
          });
          preview.button("Cancel", false, preview.dialog::dismiss);
          preview.dialog.setOnDismissListener(d -> { if (!confirmed[0]) status("Review cancelled", "No changes were made."); });preview.show();
        });
  }
  private void execute(IProfileService s, String token, String op) {
    if (backend != s) {
      status("Connection changed", "No action submitted. Reconnect and prepare a new preview.");
      return;
    }
    run(op.equals("download") ? "Installing and verifying" : "Applying " + op, true,
        ()
            -> new JSONObject(op.equals("switch") ? s.executeSwitch(token)
                    : op.equals("download")       ? s.executeDownload(token)
                                                  : s.executeOperation(token)),
        r -> {
          if (r.has("notificationStatus")) {
            notificationStatus = r.optString("notificationStatus", "unavailable");
            notificationCleanupAvailable = r.optBoolean("notificationCleanupAvailable", false);
          }
          boolean verified = r.optBoolean("verified", false) || r.optBoolean("switched", false)
              || "verified".equals(r.optString("status"))
              || (r.optBoolean("installed", false)
                  && "installed_disabled".equals(r.optString("status")));
          boolean review = r.optBoolean("reviewRequired", !verified);
          if (op.equals("download")) {
            verified = InstallResultPolicy.verified(r.optString("status"),
                r.isNull("installed") ? null : r.optBoolean("installed", false),
                r.isNull("enabled") ? null : r.optBoolean("enabled", true),
                r.optBoolean("reviewRequired", true));
            review = review || !verified;
          }
          notice = verified && !review
              ? (op.equals("download")
                        ? "eSIM installed and independently verified. It remains disabled; your "
                            + "active eSIM is unchanged."
                        : "Operation verified by a fresh card read. Review the updated inventory.")
              : "Operation requires review. Verify the card before any further action; no "
                  + "automatic retry was made.";
          if (op.equals("switch") && verified && !review) {
            String system = r.optString("systemSubscriptionStatus", "unavailable");
            notice = "eSIM enabled and verified on the card. "
                + ("matched".equals(system)
                    ? "Android reports the selected eSIM as an active subscription. Network service is not verified."
                    : "mismatch".equals(system)
                    ? "Android has not confirmed the selected subscription yet. Check SIM settings before switching again."
                    : "Android subscription status could not be checked. Check SIM settings; network service is not verified.");
          }
          if (r.has("notificationStatus") && verified && !review)
            notice += "\n" + NotificationPresentation.message(notificationStatus);
          if (verified && !review)
            getPreferences(0).edit().remove("pending").commit();
          if (r.optBoolean("restartRequired", false) || !r.optBoolean("notificationWorkerStopped", true)) {
            getPreferences(0).edit().putBoolean("pending", true).commit();
            notice += "\nReconnect the local service using your computer and verify the card before another operation.";
          }
          recentResults.add(0, (verified && !review ? "Verified: " : "Needs review: ") + op);
          if(recentResults.size()>20)recentResults.remove(20);
          restoreDetailAfterRead=verified && !review && ProfileActionPolicy.executesDirectly(op);
          screen = restoreDetailAfterRead ? 3 : 0;
          render();
          refresh();
          if (op.equals("download") && verified && !review) showInstalledResult();
        });
  }
  private void showInstalledResult() {
    if (destroyed || !resumed) return;
    Sheet result = new Sheet("eSIM installed");installResultDialog = result.dialog;
    result.copy("Installation was independently verified on your card.");
    result.fact("New eSIM", "Disabled — ready to activate later");
    result.fact("Existing profiles", "Unchanged");
    result.fact("Operator notification", NotificationPresentation.message(notificationStatus));
    result.dialog.setCancelable(false);result.dialog.setCanceledOnTouchOutside(false);
    result.button("Done", true, () -> { result.dialog.dismiss();screen = 0;render(); });result.show();
  }
  private void reviewDownload() {
    if (backend == null) {
      status("Connect your card first",
          "Installation requires the authorized on-phone management service.");
      return;
    }
    final IProfileService s = backend;
    String code = activation.getText().toString().trim(), cc = confirmation.getText().toString();
    if (code.isEmpty()) {
      activation.setError("Enter an LPA activation code or scan a QR code");
      return;
    }
    final ActivationCode parsed;
    try {
      parsed = ActivationCode.parse(code);
      if (parsed.confirmationRequired && cc.isEmpty()) {
        showConfirmation = true;render();confirmation.setError("Your operator requires a confirmation code");confirmation.requestFocus();return;
      }
      parsed.confirmation(cc);
    } catch (IllegalArgumentException invalid) {
      activation.setError(invalid.getMessage());
      return;
    }
    final long reviewedSession = draft.revision();
    run("Validating activation code", false,
        () -> {
          JSONObject target = new JSONObject(s.getCardInfo());
          if (!target.optBoolean("ok", false)) throw new IllegalStateException("Card verification unavailable");
          return new JSONObject(s.prepareDownload(code, cc)).put("targetCard", target.optString("eidMasked", "Not reported"));
        }, r -> {
          if (screen != 1 || !draft.isCurrent(reviewedSession) || backend != s) return;
          if (!r.optBoolean("ok", true)) {
            notice = "Activation code could not be accepted. Check the code, confirmation code, "
                + "connection, and card safety status.";
            render();
            return;
          }
          if (!r.optBoolean("writeQualified", false)) {
            notice = "Activation code reviewed. This card is read-only, so installation is unavailable. No download started.";
            render();
            return;
          }
          String token = r.optString("token", "");
          if (token.isEmpty()) {
            status("Review unavailable", "No installation token was returned.");
            return;
          }
          status(
              "Installation review ready", "No changes made. Confirm once to install, or cancel.");
          final boolean[] confirmed = {false};
          Sheet preview = new Sheet("Install this eSIM?");
          preview.fact("Operator", parsed.host);preview.fact("Card", r.optString("targetCard", "Verified connected card"));
          preview.copy("The new eSIM stays disabled. Existing profiles keep their current state.");
          preview.copy("The operator may consume this code. Installation is attempted once, then independently checked. Keep another internet connection available.");
          preview.button("Install once", true, () -> {
            confirmed[0] = true;preview.dialog.dismiss();clearCredentials();execute(s, token, "download");
          });
          preview.button("Cancel", false, preview.dialog::dismiss);
          preview.dialog.setOnDismissListener(d -> { if (!confirmed[0]) status("Installation cancelled", "No download started. No changes were made."); });preview.show();
        });
  }
  private void clearCredentials() {
    draft.clear();
    if (activation != null) activation.setText("");
    if (confirmation != null) confirmation.setText("");
  }
  private void navigate(int destination) {
    if(OPERATIONS.running())return;
    if (screen == 1 && destination != 1) { clearCredentials();pendingImport = null;draft.changed(); }
    navigationSession++;
    if (destination != 1) { draft.form = false;showConfirmation = false; }
    if (!getPreferences(0).getBoolean("pending", false)) { notice = "";title = backend != null ? "Card connected" : "Connect your card";detail = backend != null ? "" : "Connect using your computer."; }
    selectedProfile=-1;selectedIccid="";selectedProfileKey="";detailProfile=null;
    screen = destination;
    render();
  }
  @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (Build.VERSION.SDK_INT < 33 && keyCode == KeyEvent.KEYCODE_BACK) { handleBack(); return true; }
    return super.onKeyUp(keyCode, event);
  }
  private void handleBack() {
    if(OPERATIONS.running())return;
    if(screen==4){screen=3;navigationSession++;render();return;}
    if (screen == 1 && draft.form) { clearCredentials();activation = null;confirmation = null;draft.form = false;showConfirmation = false;pendingImport = null;draft.changed();navigationSession++;render(); }
    else if (screen != 0) navigate(0);
    else finish();
  }
  private void render() {
    if (destroyed) return;
    controlAvailability.clear();
    taskControls.clear();
    if (screen == 1 || screen == 4) {
      if (activation != null) draft.activation = activation.getText().toString();
      if (confirmation != null) draft.confirmation = confirmation.getText().toString();
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    activation = null;
    confirmation = null;
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    PageHeader.applyInsets(root);
    root.addView(new PageHeader(this,
        screen == 0 ? "8bercue" : screen == 1 ? "Add eSIM" : screen == 3 ? "eSIM details" : screen == 4 ? "Rename eSIM" : screen == 5 ? "Operation records" : screen == 6 ? "Connect your phone" : "Settings",
        screen == 1 && draft.form ? "Back to add methods" : "Back to My eSIMs",
        screen == 0 ? null : this::handleBack,
        screen == 0 ? () -> navigate(2) : null));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(24), 0, dp(24), dp(36));
    scroll.addView(content);
    FrameLayout workspace=new FrameLayout(this);
    workspace.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
    root.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));
    if(screen==0&&inventory.has("profiles")&&!binderReadOnlyInventory){
      Button fab=action("+  Add eSIM",false,()->navigate(1));fab.setTextColor(ON_TONAL);fab.setBackground(rounded(TONAL,16));fab.setElevation(dp(2));
      FrameLayout.LayoutParams placement=new FrameLayout.LayoutParams(-2,dp(56),Gravity.BOTTOM|Gravity.END);placement.setMargins(dp(24),dp(16),dp(24),dp(16));workspace.addView(fab,placement);availability(fab,!getPreferences(0).getBoolean("pending",false));
      content.setPadding(dp(24),0,dp(24),dp(100));
    }
    setContentView(root);
    statusBox = box();
    statusBox.setPadding(dp(16), dp(14), dp(16), dp(14));
    statusTitle = text("", 14, TEXT, true);
    statusDetail = text("", 13, MUTED, false);
    statusBox.addView(statusTitle);
    statusBox.addView(statusDetail, margin(4));
    // Progress, outcome and recovery share this one status region.
    {
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      busyProgress = progress;
      progress.setIndeterminate(true);
      progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
      statusBox.addView(progress, margin(8));
    }
    add(statusBox, 18);
    updateStatusRegion();
    if (screen == 0) home();
    else if (screen == 1) addScreen();
    else if(screen==3) profileDetails();
    else if(screen==4) renameScreen();
    else if(screen==5) recordsScreen();
    else if(screen==6) connectionScreen();
    else settings();
    updateStatusRegion();
  }
  private void home() {
    if (!managementUnavailable().isEmpty()) {
      add(text("Installation supported; management unavailable",20,TEXT,true),24);
      add(text(managementUnavailable()),12);return;
    }
    LinearLayout card=box();
    Button choice=button(cardLabel()+" · Choose",false);
    choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    choice.setTextSize(18);choice.setTextColor(TEXT);
    choice.setBackgroundColor(Color.TRANSPARENT);
    choice.setOnClickListener(v->{if(backend!=null)chooseCard();else beginDirectBootstrap();});
    taskControls.add(choice);card.addView(choice,new LinearLayout.LayoutParams(-1,-2));
    card.addView(text("EID "+inventory.optString("eidMasked","Not checked"),12,MUTED,false),margin(4));
    card.addView(text(inventory.has("profiles")?"Card connected · Read verified":"Connect to check this card",12,MUTED,false),margin(14));
    add(card,18);
    LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.addView(text("Your eSIMs",22,TEXT,false),new LinearLayout.LayoutParams(0,-2,1));
    Button refresh=action("Refresh",false,this::refresh);refresh.setTextSize(13);refresh.setBackgroundColor(Color.TRANSPARENT);
    heading.addView(refresh,new LinearLayout.LayoutParams(dp(88),dp(48)));add(heading,22);
    JSONArray profiles=inventory.optJSONArray("profiles");
    if(profiles==null){add(text(backend==null?"Your eSIMs will appear here.":"Choose a card or check compatibility in Settings."),16);add(action(backend==null?"Connect using computer":"Choose card",true,backend==null?this::beginDirectBootstrap:this::chooseCard),20);return;}
    for(int i=0;i<profiles.length();i++){
      final int index=i;JSONObject profile=profiles.optJSONObject(i);if(profile==null)continue;
      LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(96));row.setPadding(0,dp(16),0,dp(16));
      TextView avatar=text(profileName(profile).substring(0,Math.min(2,profileName(profile).length())),16,ON_TONAL,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(rounded(TONAL,14));row.addView(avatar,new LinearLayout.LayoutParams(dp(44),dp(44)));
      LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(14),0,dp(8),0);
      labels.addView(text(profileName(profile),16,TEXT,true));labels.addView(text(profile.optString("iccidMasked","Not reported"),12,MUTED,false),margin(4));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
      boolean on="enabled".equalsIgnoreCase(profile.optString("state"));TextView state=text(on?"Enabled":"disabled".equalsIgnoreCase(profile.optString("state"))?"Disabled":"Unknown",11,on?ACCENT:MUTED,false);state.setPadding(dp(8),dp(4),dp(8),dp(4));state.setBackground(rounded(SURFACE,8));row.addView(state);
      row.setContentDescription(profileName(profile)+", "+state.getText()+". View profile details");row.setFocusable(true);ripple(row);row.setOnClickListener(v->{if(!OPERATIONS.running())showProfile(index,profile,row);});taskControls.add(row);add(row,0);addDivider();
    }
    if(profiles.length()==0)add(text("No profiles yet. Add an eSIM to get started."),20);
    add(text("Enabled describes the card state. Network service has not been checked.",12,MUTED,false),20);

  }
  private String cardLabel(){String reader=inventory.optString("reader","");String description=inventory.optString("description","");return description.isEmpty()?(reader.isEmpty()?"Select an eUICC":reader+" · eUICC"):description;}
  private void selectDetail(int index,JSONObject profile){selectedProfile=index;selectedIccid=profile.optString("iccidMasked","");selectedProfileKey=profile.optString("selectionId","");detailProfile=profile;detailSelection.open(inventory,backend,profile,index);screen=3;navigationSession++;render();}
  private boolean detailCurrent(){JSONArray rows=inventory.optJSONArray("profiles");return backend!=null&&resumed&&!destroyed&&!OPERATIONS.running()&&rows!=null&&selectedProfile>=0&&rows.optJSONObject(selectedProfile)==detailProfile&&detailSelection.matches(inventory,backend,detailProfile,selectedProfile)&&!getPreferences(0).getBoolean("pending",false)&&!selectedIccid.isEmpty()&&!selectedProfileKey.isEmpty();}
  private void profileDetails(){
    JSONObject p=detailProfile;if(p==null){add(text("Refresh the card and select a profile."),24);return;}
    add(text(cardLabel()+" · EID "+inventory.optString("eidMasked","Not reported"),12,MUTED,false),16);
    add(text(profileName(p),28,TEXT,false),28);add(text(selectedIccid),6);
    boolean enabled="enabled".equalsIgnoreCase(p.optString("state"));boolean disabled="disabled".equalsIgnoreCase(p.optString("state"));
    Switch toggle=new Switch(this);toggle.setText("Use this eSIM");toggle.setTextSize(20);toggle.setTextColor(ON_TONAL);toggle.setPadding(dp(20),dp(18),dp(20),dp(18));toggle.setMinHeight(dp(80));toggle.setBackground(rounded(TONAL,24));toggle.setChecked(enabled);toggle.setContentDescription("Use this eSIM");
    toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(darkTheme?0xffffdbcC:Color.WHITE));toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,MUTED}));
    boolean canEdit=backend!=null&&inventory.optBoolean("writeQualified",false)&&!getPreferences(0).getBoolean("pending",false);
    availability(toggle,canEdit&&((enabled&&p.optBoolean("canDisable",false))||(disabled&&p.optBoolean("canEnable",false))));taskControls.add(toggle);
    toggle.setOnClickListener(button->{toggle.setChecked(enabled);if(detailCurrent())prepare(enabled?"disable":"switch",selectedProfile,"");});add(toggle,28);
    add(text(enabled?"Enabled on this card.":disabled?"Off. Turn on to use this eSIM.":"State unknown. Refresh before making changes."),14);
    String active="";JSONArray rows=inventory.optJSONArray("profiles");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject other=rows.optJSONObject(i);if(other!=null&&"enabled".equalsIgnoreCase(other.optString("state")))active=profileName(other);}
    add(text(enabled?"Turning this off leaves no eSIM enabled on this card.":active.isEmpty()?"Turning this on enables this eSIM on this card.":"Turning this on turns off "+active+" on this card.",13,MUTED,false),6);
    Button rename=action("Rename",false,()->{if(detailCurrent()){screen=4;navigationSession++;render();}});availability(rename,canEdit&&p.optBoolean("canRename",false));add(rename,28);
    Button delete=action("Delete eSIM",false,()->{if(detailCurrent())prepare("delete",selectedProfile,"");});delete.setTextColor(ERROR);availability(delete,canEdit&&disabled&&p.optBoolean("canDelete",false));add(delete,12);
    if(enabled)add(text("Turn off this eSIM before deleting it.",12,MUTED,false),4);
    if(!canEdit)add(text("Changes are unavailable until this card and its recovery state are verified.",13,MUTED,false),12);
    add(text("About this eSIM",16,TEXT,true),28);detailFact("Operator",p.optString("provider",p.optString("name","Not reported")));detailFact("ICCID",selectedIccid);detailFact("Card",cardLabel());
    add(text("Card state only. Network service has not been checked.",12,MUTED,false),20);
  }
  private void detailFact(String label,String value){LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(14),0,dp(14));row.addView(text(label,13,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));TextView v=text(mask(value),13,TEXT,false);v.setGravity(Gravity.END);row.addView(v,new LinearLayout.LayoutParams(0,-2,2));add(row,0);addDivider();}
  private void renameScreen(){if(detailProfile==null)return;add(text(cardLabel()+" · EID "+inventory.optString("eidMasked","Not reported"),12,MUTED,false),18);add(text(profileName(detailProfile)+" · "+selectedIccid),22);EditText name=input("Nickname",false);name.setText(detailProfile.optString("nickname",""));add(name,22);add(text("Leave blank to use the operator name.",12,MUTED,false),10);add(action("Save",true,()->{if(!detailCurrent())return;String value=name.getText().toString().trim();if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>64||value.matches("(?s).*[\\p{Cntrl}].*")){name.setError("Use at most 64 UTF-8 bytes without control characters");return;}hideKeyboard();prepare("rename",selectedProfile,value);}),28);add(action("Cancel",false,()->{screen=3;navigationSession++;render();}),8);}
  private void recordsScreen(){add(text("Operation results",22,TEXT,false),24);add(text("This session’s results are shown here. Durable safety records remain in the Shell service; diagnostics omit profile identifiers."),12);for(String value:recentResults)add(text(value,16,TEXT,false),18);if(recentResults.isEmpty())add(text("No operations in this app session."),24);add(action("Verify card and recovery",false,this::reconcile),24);add(action("Export safe diagnostics",false,this::exportDiagnostics),12);}
  private void connectionScreen(){
    add(text("Connect with ADB.",28,TEXT,false),28);
    add(text("One command. No extra script, Shizuku or root."),12);
    connectionCountdown=text(ConnectionFeedback.countdown(connectionDeadline-android.os.SystemClock.elapsedRealtime()),14,connectionExpired?ERROR:ACCENT,true);
    add(connectionCountdown,20);
    add(text("1. Connect this phone",18,TEXT,true),24);
    add(text("Enable USB debugging and authorize your trusted computer. Keep 8bercue open on this page."),10);
    add(text("2. Run this on your computer",18,TEXT,true),24);
    TextView command=text(connectionCommand,12,TEXT,false);
    command.setTypeface(android.graphics.Typeface.MONOSPACE);command.setTextIsSelectable(true);
    command.setPadding(dp(16),dp(16),dp(16),dp(16));command.setBackground(rounded(SURFACE,12));add(command,14);
    Button copy=action("Copy ADB command",true,()->{
      android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(ClipData.newPlainText("8bercue ADB connection",connectionCommand));
      statusPreservingOutcome("Command copied","Run it while the timer is active. Your computer will confirm the connection or explain why it failed.");
    });availability(copy,!connectionExpired);add(copy,18);
    add(action("Start connection again",connectionExpired,this::beginDirectBootstrap),8);
    add(action(connectionExpired || CompatibilityDiagnostics.lastFailure()!=null
        ? "Report this connection failure" : "Trouble connecting? Report issue",
        false, () -> {
          if (CompatibilityDiagnostics.lastFailure()==null)
            compatibilityFailure(CompatibilityDiagnostics.Stage.BOOTSTRAP,
                CompatibilityDiagnostics.Failure.BOOTSTRAP_INCOMPLETE);
          offerCompatibilityReport();
        }),12);
    add(text("The command stays the same after app updates. With several phones attached, replace adb shell with adb -s SERIAL shell for this phone.",12,MUTED,false),16);
    add(text("3. Wait for connection confirmation",18,TEXT,true),24);
    add(text("Your computer must say “8bercue connected” or “already connected”. This page then closes automatically and card access is checked. A running process alone does not confirm a connection."),10);
    add(text("No profiles are changed by connecting. If the timer expires, tap Start connection again and rerun the same command. Reconnect after a reboot, app update or service termination.",12,MUTED,false),20);
  }
  private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  private void ripple(View v){android.util.TypedValue value=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackground,value,true);v.setBackgroundResource(value.resourceId);}
  private void availability(View control,boolean allowed){controlAvailability.put(control,allowed);control.setEnabled(allowed&&!OPERATIONS.running());control.setAlpha(control.isEnabled()?1f:.45f);}
  private void applyPalette(){darkTheme=getPreferences(0).getBoolean("darkTheme",(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);BG=darkTheme?0xff1a120e:0xfffff8f5;SURFACE=darkTheme?0xff261c17:0xfff5eae4;TEXT=darkTheme?0xfff6e5da:0xff292019;MUTED=darkTheme?0xffdbc1b2:0xff6c5b51;ACCENT=darkTheme?0xffffb596:0xffa9431d;LINE=darkTheme?0xff584136:0xffe5d4c9;TONAL=darkTheme?0xff7c2d0c:0xffffdbcc;ON_TONAL=darkTheme?0xffffdbcc:0xff3a0c00;ERROR=darkTheme?0xffffb4aa:0xffa52c2a;getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(darkTheme?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
  private final class Sheet {
    final Dialog dialog = new Dialog(MainActivity.this);
    final LinearLayout body = new LinearLayout(MainActivity.this);
    Sheet(String heading) {
      if (activeSheet != null) activeSheet.dismiss();
      activeSheet = dialog;sheetControls.clear();
      dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
      body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24), dp(24), dp(24), dp(32));
      GradientDrawable bg = new GradientDrawable();bg.setColor(BG);bg.setCornerRadius(dp(24));body.setBackground(bg);
      body.addView(text(heading, 25, TEXT, true));
      ScrollView scroll = new ScrollView(MainActivity.this);scroll.addView(body);
      dialog.setContentView(scroll);
      Window w = dialog.getWindow();
      if (w != null) {
        w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (screen == 1) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      }
    }
    void copy(String value) { body.addView(text(value, 14, MUTED, false), margin(16)); }
    void fact(String label, String value) {
      body.addView(text(label, 12, MUTED, false), margin(16));
      body.addView(text(mask(value), 16, TEXT, false), margin(3));
    }
    void button(String label, boolean primary, Runnable click) {
      Button b = MainActivity.this.button(label, primary);
      boolean dismiss = label.equals("Done") || label.equals("Cancel");
      if (!dismiss) {
        sheetControls.add(b);b.setEnabled(!OPERATIONS.running());b.setAlpha(OPERATIONS.running() ? .45f : 1f);
      }
      if (label.startsWith("Delete")) {
        b.setTextColor(primary ? Color.WHITE : 0xffb42318);
        if (primary) { GradientDrawable red = new GradientDrawable();red.setColor(0xffb42318);red.setCornerRadius(dp(28));b.setBackground(red); }
      }
      b.setOnClickListener(v -> { if (dismiss || !OPERATIONS.running()) click.run(); });
      body.addView(b, margin(16));
    }
    void show() {
      hideKeyboard();dialog.show();Window w = dialog.getWindow();
      if (w != null) { w.setLayout(-1, -2);w.setGravity(Gravity.BOTTOM);
        body.post(() -> { int max = (int)(getResources().getDisplayMetrics().heightPixels * .86f);
          if (body.getHeight() > max) w.setLayout(-1, max); }); }
    }
  }
  private void hideKeyboard() {
    View focused = getCurrentFocus();
    if (focused != null) ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(), 0);
  }
  private String profileName(JSONObject p) {
    String n = p.optString("nickname", "").trim();
    if (n.isEmpty()) n = p.optString("provider", "").trim();
    if (n.isEmpty() || n.equalsIgnoreCase("unknown")) n = p.optString("name", "eSIM");
    return mask(n);
  }
  private String profileSubtitle(JSONObject p) {
    String n = mask(p.optString("name", ""));
    return n.equals(profileName(p)) ? "" : n;
  }
  private void clearDetailSelection(){detailSelection.clear();selectedProfile=-1;selectedIccid="";selectedProfileKey="";detailProfile=null;}
  private void showProfile(int index, JSONObject profile, View row) {
    JSONArray rows=inventory.optJSONArray("profiles");
    if(destroyed||!resumed||OPERATIONS.running()||screen!=0||row.getParent()!=content||rows==null||rows.optJSONObject(index)!=profile)return;
    selectDetail(index,profile);
  }
  private void addDivider() {
    View line = new View(this);line.setBackgroundColor(LINE);content.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
  }
  private void addScreen() {
    add(text(cardLabel()+" · EID "+inventory.optString("eidMasked","Not checked"),12,MUTED,false),18);
    if (backend == null) {
      add(text("Connect your card to review and install. You can enter or scan your code first.", 14, MUTED, false), 18);
      add(action("Open connection settings", false, () -> navigate(2)), 10);
    }
    if (!draft.form) {
      add(text("How would you like to add your eSIM?", 16, MUTED, false), 22);
      add(action("Scan a QR code  ›", true, () -> startActivityForResult(new Intent(this, QrScanActivity.class), QR)), 24);
      add(action("Choose a QR image  ›", false, () -> {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i, IMAGE);
      }), 12);
      add(action("Enter an activation code  ›", false, () -> { draft.form = true;draft.changed();render(); }), 12);
      add(text("Use the code provided by your operator. You’ll review the installation before anything changes.", 14, MUTED, false), 24);
      return;
    }
    add(text("Activation code", 16, TEXT, true), 24);
    activation = input("LPA:1$server$matching-id", true);activation.setText(draft.activation);add(activation, 10);
    boolean required = false;
    try { required = ActivationCode.parse(draft.activation).confirmationRequired; } catch (IllegalArgumentException ignored) {}
    confirmation = input("Operator confirmation code", true);confirmation.setText(draft.confirmation);
    if (showConfirmation || required || !draft.confirmation.isEmpty()) {
      add(text(required ? "Confirmation code (required)" : "Confirmation code", 14, TEXT, true), 18);add(confirmation, 8);
    } else add(action("Have a confirmation code?", false, () -> { showConfirmation = true;render(); }), 12);
    watchInput(activation);watchInput(confirmation);
    confirmation.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    confirmation.setOnEditorActionListener((v, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE && !OPERATIONS.running()) { reviewDownload();return true; }return false; });
    add(action("Review installation", true, this::reviewDownload), 24);
    add(text("Codes stay in memory and are sent only to your operator during installation. The new profile stays disabled. Interrupted downloads are never automatically repeated.", 13, MUTED, false), 18);
  }
  private void watchInput(EditText field) {
    field.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) { draft.changed(); }
      public void afterTextChanged(android.text.Editable value) {}
    });
  }
  private void chooseCard() {
    final IProfileService service=backend;if(service==null||OPERATIONS.running())return;
    clearCredentials();navigationSession++;clearDetailSelection();
    final long choiceSession=navigationSession;
    run("Reading available cards",false,()->new JSONObject(service.prepareCardSelection()),result->{
      if(backend!=service||navigationSession!=choiceSession)return;
      if(!result.optBoolean("ok",false)){
        String explanation=SecureElementFailure.message(result.optString("capabilityError",""));
        status("Card choice unavailable",explanation.isEmpty()?"Reconnect and check recovery. No card was changed.":explanation);render();return;
      }
      inventory=new JSONObject();cardInfo=new JSONObject();
      Sheet choices=new Sheet("Choose a card");
      choices.copy("Choose the eUICC you intend to manage. This checks card identity only; no profile changes happen. Changing cards discards old operation previews.");
      JSONArray rows=result.optJSONArray("choices");
      if(rows!=null)for(int i=0;i<rows.length();i++){
        JSONObject choice=rows.optJSONObject(i);if(choice==null)continue;
        String token=choice.optString("token","");
        choices.button(choice.optString("description","Card")+" · EID "+choice.optString("eidMasked","")+" · "+choice.optString("reader",""),false,()->{
          choices.dialog.dismiss();if(backend!=service||navigationSession!=choiceSession)return;
          run("Verifying selected card",false,()->new JSONObject(service.selectCard(token)),selected->{
            if(backend!=service||navigationSession!=choiceSession)return;
            navigationSession++;clearDetailSelection();inventory=new JSONObject();cardInfo=new JSONObject();compatibility=new JSONObject();notificationStatus="";notificationCleanupAvailable=false;
            if(!selected.optBoolean("ok",false)){String explanation=SecureElementFailure.message(selected.optString("capabilityError",""));status("Card choice needs verification",explanation.isEmpty()?"The choice expired or the card changed. Choose a card again. No alternative was selected automatically.":explanation);render();return;}
            screen=0;status("Card selected","Reading this card and its recovery state…");render();checkRecovery();
          });
        });
      }
      choices.copy("The selection lasts for this Shell service session. A missing or changed selected card stops access; another card is never chosen as a fallback.");
      choices.button("Cancel",false,()->{choices.dialog.dismiss();refresh();});choices.show();
    });
  }
  private void showCompatibility() {
    Sheet check=new Sheet("Compatibility check");
    if(compatibility.length()==0)check.copy("Connect the Shell service, then refresh to check this card. No card has been verified in this connection.");
    else {
      check.fact("Selected card channel",compatibility.optString("route","No verified card connection"));
      check.copy(compatibility.optString("details","No channel observations returned."));
      check.copy(compatibility.optBoolean("profileReadVerified",false)
          ? "Profile reading verified. This read-only check does not test downloads, switching or deletion."
          : "Profile reading has not been verified. No compatibility check changes a profile.");
    }
    check.copy("Order: Secure Element HAL (AIDL, then HIDL), Telephony, then OMAPI. Multiple readable cards require an explicit choice. Unconfirmed channel cleanup stops access. No brand or Android-version list selects a card.");
    if(backend!=null)check.button("Choose card",false,()->{check.dialog.dismiss();chooseCard();});
    check.button("Run read-only check",false,()->{check.dialog.dismiss();if(backend!=null||fallback)refresh();else status("Connect first","Use Connect using computer, then run the compatibility check.");});
    check.button("Done",false,check.dialog::dismiss);check.show();
  }
  private void settings() {
    add(text("Connection", 19, TEXT, true), 24);
    add(text("Card connection: "+compatibility.optString("route","Not checked in this connection"),14,MUTED,false),8);
    add(action("Compatibility check",false,this::showCompatibility),12);
    if(backend!=null)add(action("Choose card",false,this::chooseCard),12);
    add(text(directManagement && backend != null ? "Computer connection active"
          : companionManagement ? "Local companion connected"
          : binderReadOnlyInventory ? "Read-only diagnostic connection"
          : backend != null ? "Computer connection active"
          : fallback ? "Read-only companion connected" : "Not connected", 15, MUTED, false), 8);
    if (LabSupport.socketEligible())
      add(action("Use legacy local companion", useCompanion(), this::selectLegacyCompanion), 12);
    add(action("Connect using computer", backend==null, this::beginDirectBootstrap), 12);
    if (CompatibilityDiagnostics.lastFailure()!=null)
      add(action("Report previous connection failure",false,this::offerCompatibilityReport),12);
    if (BuildConfig.DEBUG) add(action("Check direct Binder Companion (read-only)", false, () -> {
      run("Checking local Binder", false, () -> {
        ICompanionProbe probe = CompanionBinderProvider.service();
        if (probe == null) return new JSONObject().put("ok", false).put("status", "not_connected");
        String greeting = probe.getStatus();
        if (!greeting.startsWith("READY: Companion Binder Shell UID=2000"))
          return new JSONObject().put("ok", false).put("status", "identity_unverified");
        // IPC diagnostics must not inadvertently touch the radio or eUICC.
        return new JSONObject().put("ok", true).put("status", "binder_identity_verified");
      }, reply -> status(reply.optBoolean("ok", false)
            ? "Binder transport verified" : "Binder transport unavailable",
          "IPC identity: " + reply.optString("status", "unknown")
            + ". No card read was performed."));
    }), 12);
    if (BuildConfig.DEBUG) add(action("Auto-detect known 5ber card (read-only)", false, () -> {
      if (binderDetectionRequested) {
        status("Card discovery already attempted", "Do not repeat a card probe automatically.");
        return;
      }
      binderDetectionRequested = true;
      run("Detecting 5ber card", false, () -> {
        ICompanionProbe probe = CompanionBinderProvider.service();
        if (probe == null) return new JSONObject().put("ok",false).put("stage","ipc");
        return new JSONObject(probe.detectKnownCard());
      }, result -> status(result.optBoolean("ok",false) ? "Recognized 5ber card" : "Card not recognized",
          result.optBoolean("ok",false)
              ? result.optString("knownCard","known") + " on " + result.optString("reader","unknown")
                  + ". Discovery only; writes remain restricted."
              : "Stage=" + result.optString("stage","unavailable")));
    }), 12);
    if (BuildConfig.DEBUG) add(action("Read via direct Binder (read-only)", false, () -> {
      if (binderReadRequested) {
        status("Binder read already attempted", "Do not repeat a failed or uncertain card read.");
        return;
      }
      binderReadRequested = true;
      run("Binder card read", false, () -> {
        ICompanionProbe probe = CompanionBinderProvider.service();
        if (probe == null) return new JSONObject().put("ok", false).put("stage", "ipc");
        JSONObject reply = new JSONObject(probe.listProfiles());
        if (!reply.optBoolean("ok", true) || reply.optJSONArray("profiles") == null)
          return new JSONObject().put("ok", false)
              .put("stage", reply.optString("stage", "profile_read_unavailable"));
        // The returned inventory is already masked by the UID2000 service.
        if (!reply.optBoolean("readOnly", false) || reply.optBoolean("writeQualified", true))
          return new JSONObject().put("ok",false).put("stage","unsafe_inventory");
        return reply;
      }, reply -> {
        if (reply.optBoolean("ok",false) && reply.optJSONArray("profiles") != null) {
          backend = null;compatibility=new JSONObject();
          fallback = false;
          companionManagement = false;
          binderReadOnlyInventory = true;
          inventory = reply;
          screen = 0;
          status("Binder card read complete", "Recognized " + reply.optString("knownCard","card")
              + " on " + reply.optString("reader","unknown")
              + "; " + reply.optInt("count",0) + " profiles. Read-only; no editing permission.");
          render();
        } else {
          status("Binder read unavailable", "Stage=" + reply.optString("stage","unknown"));
        }
      });
    }), 12);
    add(action("Setup help  ›", false, () -> {
      Sheet help = new Sheet("Connect your card");
      help.copy("Connect this phone by USB, enable USB debugging and accept the computer authorization prompt. Choose Connect using computer, then copy and run the displayed ADB command. No Python or additional download is required.");
      help.copy("After connection, open Compatibility check. A connected service alone does not prove card access: confirm the selected card channel and a fresh profile list. No card changes are made by the check.");
      help.copy("Reconnect after a phone restart, app update or service termination. Failed operations are never automatically repeated.");help.button("Done", false, help.dialog::dismiss);help.show();
    }), 12);
    add(text("Appearance",19,TEXT,true),28);
    add(action(darkTheme?"Theme: Dark":"Theme: Light",false,()->{getPreferences(0).edit().putBoolean("darkTheme",!darkTheme).apply();applyPalette();render();}),12);
    add(text("Support",19,TEXT,true),28);
    add(action("Operation records",false,()->{screen=5;navigationSession++;render();}),12);
    add(text("Card", 19, TEXT, true), 28);
    add(text("EID  " + mask(cardInfo.optString("eidMasked", inventory.optString("eidMasked", "Not checked")))), 10);
    add(text("Profiles  " + (inventory.optJSONArray("profiles") == null ? "Not checked" : inventory.optJSONArray("profiles").length())), 8);
    add(text("Storage  " + (cardInfo.length() == 0 ? "Not checked" : storage())), 8);
    add(action(cardInfo.length() == 0 ? "Read card information" : "Refresh card information", false, () -> {
      IProfileService service = backend;
      if (service == null) { status("Connect your card first", "Card information needs the authorized service.");return; }
      run("Reading card information", false, () -> new JSONObject(service.getCardInfo()), r -> {
        if (!r.optBoolean("ok", false)) { status("Card information unavailable", "Reconnect and refresh the card.");return; }
        cardInfo = r;status("Card information checked", "Only details reported by your card are shown.");render();
      });
    }), 16);
    if (cardInfo.length() > 0) add(action("Capability details  ›", false, () -> {
      Sheet info = new Sheet("Card capabilities");
      info.copy(cardInfo.optBoolean("capabilitiesSupported", false) ? capabilitySummary() : "Optional capabilities were not reported by this reader.");
      info.button("Done", false, info.dialog::dismiss);info.show();
    }), 10);
    if (!notificationStatus.isEmpty()) {
      add(text("Operator notification", 19, TEXT, true), 24);
      add(text(NotificationPresentation.message(notificationStatus), 14, MUTED, false), 8);
      if (notificationCleanupAvailable) add(action("Review notification cleanup", false, this::prepareNotificationCleanup), 12);
    }
    if (getPreferences(0).getBoolean("pending", false)) add(action("Verify card and recovery", true, this::reconcile), 24);
    add(action("Recovery & advanced  ›", false, () -> {
      Sheet advanced = new Sheet("Recovery & advanced");
      advanced.copy("An interrupted operation may have an unknown outcome. Reconnect and verify the card before making changes. Do not reuse an activation code until the operator confirms it is safe.");
      advanced.button("Verify card and recovery", false, () -> { advanced.dialog.dismiss();reconcile(); });
      if (LabSupport.socketEligible()) advanced.button("Recover local companion", false, () -> { advanced.dialog.dismiss();recoverFallback(); });
      advanced.button("Export safe diagnostics", false, this::exportDiagnostics);
      advanced.copy("Diagnostics exclude activation codes, profile names, identifiers and raw protocol logs.");
      advanced.button("Done", false, advanced.dialog::dismiss);advanced.show();
    }), 24);
    add(action("Report a problem  ›",false,this::reportProblem),16);
    add(action("Save diagnostic ZIP  ›",false,this::saveDiagnosticZip),12);
    add(action("Optional crash reports: " + (SafeTelemetry.allowed(this) ? "On" : "Off") + "  ›",
        false, this::chooseCrashReporting), 12);
    if (BuildConfig.DEBUG) add(action("Test crash-report connection",false,
        () -> status(SafeTelemetry.smoke(this) ? "Test event queued" : "Crash reporting disabled",
            "Check the GlitchTip project for receipt.")), 12);
    add(action("Privacy information", false, () -> showBundledText("Privacy information", "legal/privacy.txt")), 20);
    add(action("Third-party notices", false, () -> showBundledText("Third-party notices", "legal/third-party-notices.txt")), 12);
    add(text("Independent project. Not an official 5ber product.", 12, MUTED, false), 20);
    add(text("8bercue " + BuildConfig.VERSION_NAME, 12, MUTED, false), 12);
  }
  private void showBundledText(String title, String asset) {
    try (java.io.InputStream input = getAssets().open(asset)) {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];int count;
      while ((count = input.read(buffer)) != -1) {
        if (bytes.size() + count > 262144) throw new java.io.IOException("Document too large");
        bytes.write(buffer, 0, count);
      }
      Sheet document = new Sheet(title);
      document.copy(bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
      document.button("Done", false, document.dialog::dismiss);document.show();
    } catch (java.io.IOException unavailable) { status("Document unavailable", "Reinstall the verified application package."); }
  }
  private void prepareNotificationCleanup() {
    final IProfileService service = backend;
    if (service == null) { connect();return; }
    final long session = navigationSession;
    run("Checking notification cleanup", false, () -> new JSONObject(service.prepareNotificationCleanup()), r -> {
      if (backend != service || navigationSession != session) return;
      String token = r.optString("token", "");
      if (!r.optBoolean("ok", false) || token.isEmpty()) {
        status("Cleanup unavailable", "Reconnect and check notification status. No message was resent.");return;
      }
      Sheet sheet = new Sheet("Clean up acknowledged notification?");
      sheet.copy("The operator already acknowledged this notification. This removes only that notification from the verified card. It does not resend it or change your eSIM profiles.");
      sheet.button("Clean up", true, () -> {
        sheet.dialog.dismiss();
        if (backend != service) { status("Connection changed", "Reconnect and review cleanup again.");return; }
        run("Cleaning up notification", true, () -> new JSONObject(service.executeNotificationCleanup(token)), result -> {
          notificationStatus = result.optString("notificationStatus", "unavailable");
          notificationCleanupAvailable = result.optBoolean("notificationCleanupAvailable", false);
          notice = NotificationPresentation.message(notificationStatus);
          if (!result.optBoolean("notificationWorkerStopped", true))
            notice += "\nReconnect the local service using your computer and verify the card before another operation.";
          checkRecovery();
        });
      });
      sheet.button("Cancel", false, sheet.dialog::dismiss);sheet.show();
    });
  }
  private TextView text(String value) { return text(value, 14, MUTED, false); }
  private void recoverFallback() {
    Sheet recovery = new Sheet("Authorize local ADB recovery?");
    recovery.copy("This requests full ADB Shell debugging authority, not just eSIM access. It uses the existing local ADB setup and does not change network settings. TCP debugging may be reachable on your network; use a trusted network. Android will ask separately for RSA authorization.");
    recovery.button("Continue", true, () -> {
      recovery.dialog.dismiss();run("Recovering local companion", false, () -> {
        LocalAdbBootstrap.Result r = LocalAdbBootstrap.start(getApplicationContext());
        return new JSONObject().put("ready", r.ready).put("authorization", r.needsAuthorization);
      }, r -> { if (r.optBoolean("ready")) probeFallback();else status("Recovery not complete", r.optBoolean("authorization") ? "Approve Android’s RSA prompt, then try recovery again." : "Check the supported ADB setup and reconnect."); });
    });
    recovery.button("Cancel", false, recovery.dialog::dismiss);recovery.show();
  }
  private String capabilitySummary() {
    StringBuilder result = new StringBuilder();
    String[][] fields = {{"profileVersion", "Profile spec"}, {"gsmaVersion", "GSMA spec"},
        {"firmwareVersion", "Firmware"}};
    for (String[] field : fields)
      if (cardInfo.has(field[0])) {
        if (result.length() > 0)
          result.append("; ");
        result.append(field[1]).append(" ").append(mask(cardInfo.optString(field[0])));
      }
    if (cardInfo.has("additionalProfileSupported")) {
      if (result.length() > 0)
        result.append("; ");
      result.append(cardInfo.optBoolean("additionalProfileSupported")
              ? "Additional profile supported"
              : "Additional profile not supported");
    }
    return result.length() == 0 ? "No optional details reported" : result.toString();
  }
  private String storage() {
    long bytes = cardInfo.optLong(
        "freeNonvolatileMemoryBytes", cardInfo.optLong("freeNonvolatileBytes", -1));
    return bytes >= 0 ? (bytes / 1024) + " KB available" : "Not reported";
  }
  private void compatibilityFailure(CompatibilityDiagnostics.Stage stage,
      CompatibilityDiagnostics.Failure reason) {
    CompatibilityDiagnostics.failed(CompatibilityDiagnostics.Transport.ADB_COMPANION,stage,reason);
    SafeDiagnosticBundle.mark(SafeDiagnosticBundle.Step.CONNECTION_UNAVAILABLE);
  }

  private void chooseCrashReporting() {
    boolean enabled = SafeTelemetry.allowed(this);
    new AlertDialog.Builder(this)
        .setTitle("Optional crash reports")
        .setMessage("If enabled, 8bercue sends only the error class and application code location to GlitchTip. Raw exception messages, logs, eUICC details, and personal identifiers are not uploaded. The receiving service may process your connection IP. This setting is off by default.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton(enabled ? "Turn off" : "Enable", (dialog, which) -> {
          SafeTelemetry.setAllowed(this, !enabled);
          render();
        }).show();
  }

  private void reportProblem() { reportProblem(null); }

  private void reportProblem(CompatibilityDiagnostics.Snapshot context) {
    SafeDiagnosticBundle.mark(SafeDiagnosticBundle.Step.REPORT_OPENED);
    final EditText message = new EditText(this);
    message.setHint("What happened? What did you expect?");
    message.setMinLines(3);
    message.setMaxLines(7);
    message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    new AlertDialog.Builder(this).setTitle("Report a problem")
        .setMessage("Describe the issue. A short, allowlisted device summary will be included. Do not paste activation codes, EID or personal identifiers.")
        .setView(message).setNegativeButton("Cancel", null)
        .setPositiveButton("Share report", (dialog, ignored) -> {
          String body = message.getText().toString().trim();
          if (body.length() == 0) {
            status("Nothing sent", "Add a description to report the problem.");return;
          }
          if (body.length() > 4000) body = body.substring(0, 4000);
          Intent share = new Intent(Intent.ACTION_SEND);
          share.setType("text/plain");
          share.putExtra(Intent.EXTRA_SUBJECT, "8bercue bug report");
          share.putExtra(Intent.EXTRA_TEXT, "Issue description (user-supplied; review before sending):\n" + body
             + "\n\n" + SafeDiagnosticBundle.summary(BuildConfig.VERSION_NAME,
                 BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, Build.MODEL,
                 backend != null, getPreferences(0).getBoolean("pending", false))
             + (context == null ? "" : "\nCompatibility diagnosis (reviewed by user):\n" + context.summary()));
          Intent email = new Intent(Intent.ACTION_SENDTO);
          email.setData(android.net.Uri.parse("mailto:support@sidnvy.com"));
          email.putExtra(Intent.EXTRA_SUBJECT, "8bercue bug report");
          email.putExtra(Intent.EXTRA_TEXT, share.getStringExtra(Intent.EXTRA_TEXT));
          try {
            startActivity(email);
          } catch (android.content.ActivityNotFoundException noEmailApp) {
            startActivity(Intent.createChooser(share, "Share bug report with support"));
          }
        }).show();
  }
  private void offerCompatibilityReport() {
    final CompatibilityDiagnostics.Snapshot result=CompatibilityDiagnostics.lastFailure();
    if (result == null) {
      reportProblem();
      return;
    }
    new AlertDialog.Builder(this).setTitle("Report connection issue")
        .setMessage("This report contains only the following device and failure details. It does not include EID, activation codes, profiles, APDU, logs or a device ID.\n\n"
            + result.summary()
            + "\nSending to GlitchTip also exposes network connection metadata to that provider.")
        .setNeutralButton("Email support", (d,w) -> reportProblem(result))
        .setNegativeButton("Not now", null)
        .setPositiveButton("Send diagnostic report", (d,w) -> {
          if (!SafeTelemetry.configured(this)) {
            status("Remote reporting unavailable", "You can email support or save a diagnostic ZIP.");
            return;
          }
          DIAGNOSTIC_WORK.execute(() -> {
            boolean queued=SafeTelemetry.submitCompatibility(getApplicationContext(),result);
            runOnUiThread(() -> {
              if (destroyed) return;
              status(queued ? "Diagnostic report submitted" : "Unable to submit report",
                  queued ? "The report was handed to GlitchTip for delivery. This does not confirm it was indexed in the dashboard."
                      : "Try the support email option or export a diagnostic ZIP.");
            });
          });
        }).show();
  }

  private void saveDiagnosticZip() {
    SafeDiagnosticBundle.mark(SafeDiagnosticBundle.Step.ZIP_REQUESTED);
    new AlertDialog.Builder(this).setTitle("Export safe diagnostic ZIP")
        .setMessage("The ZIP includes only allowlisted app and device information. It excludes card IDs, profiles, activation codes, APDU, logcat and private files. Review it before sharing.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save ZIP", (dialog, which) -> {
          Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
          create.addCategory(Intent.CATEGORY_OPENABLE);
          create.setType("application/zip");
          create.putExtra(Intent.EXTRA_TITLE, "8bercue-diagnostics.zip");
          startActivityForResult(create, DIAGNOSTIC_EXPORT);
        }).show();
  }
  private void writeDiagnosticZip(android.net.Uri uri) {
    try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri)) {
      if (stream == null) throw new java.io.IOException("No document output");
      SafeDiagnosticBundle.write(stream, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
          Build.VERSION.SDK_INT, Build.MODEL, backend != null,
          getPreferences(0).getBoolean("pending", false));
      SafeDiagnosticBundle.mark(SafeDiagnosticBundle.Step.ZIP_SAVED);
      status("Diagnostic ZIP saved", "Inspect the ZIP before sharing it with support.");
    } catch (java.io.IOException error) {
      status("Export failed", "Could not write the selected document.");
    }
  }
  private void exportDiagnostics() {
    String report = "8bercue " + BuildConfig.VERSION_NAME + "\nAndroid API "
        + Build.VERSION.SDK_INT + "\nManagement connection: " + (backend == null ? "none" : directManagement ? "independent Binder" : companionManagement ? "Shell socket" : "local Binder")
        + "\nLocal fallback: " + fallback + "\nProfile count: "
        + (inventory.optJSONArray("profiles") == null ? "unknown"
                                                      : inventory.optJSONArray("profiles").length())
        + "\nOperation pending verification: " + getPreferences(0).getBoolean("pending", false)
        + "\nCard channel: " + compatibility.optString("route","not checked")
        + "\nRead-only compatibility observations:\n" + compatibility.optString("details","none")
        + "\nEngine: lpac/libeuicc 2.3.0\n";
    Intent send = new Intent(Intent.ACTION_SEND);
    send.setType("text/plain");
    send.putExtra(Intent.EXTRA_SUBJECT, "8bercue diagnostics");
    send.putExtra(Intent.EXTRA_TEXT, report);
    startActivity(Intent.createChooser(send, "Export safe diagnostics"));
  }
  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null)
      return;
    if (request == DIAGNOSTIC_EXPORT && data.getData()!=null) {
      writeDiagnosticZip(data.getData());
    } else if (request == QR) {
      setCode(data.getStringExtra("activation"));
    } else if (request == IMAGE && data.getData() != null) {
      pendingImport = data.getData();
    }
  }
  private void importImage(android.net.Uri uri) {
    final long importedSession = draft.revision();
    run("Reading QR image", false, () -> {
      ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
      Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
        float ratio = Math.min(1f, 1800f / Math.max(w, h));
        decoder.setTargetSize(Math.max(1, (int) (w * ratio)), Math.max(1, (int) (h * ratio)));
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
      });
      try {
        return new JSONObject().put("code", QrScanActivity.decode(bitmap));
      } finally {
        bitmap.recycle();
      }
    }, r -> { if (screen == 1 && draft.isCurrent(importedSession)) setCode(r.optString("code")); });
  }
  private void setCode(String code) {
    if (code == null || code.isEmpty()) {
      status("No QR code found", "Choose a clear QR image or enter the activation code manually.");
      return;
    }
    draft.acceptCode(code);
    confirmation = null;
    draft.changed();
    screen = 1;
    activation = null;
    render();
    activation.setText(code);
    status("QR code read", "Review the code, then validate it before installation.");
  }
  private String safeState(String s) {
    return s.matches("[A-Za-z_ -]{1,60}") ? s : "requires verification";
  }
  private String mask(String value) {
    return value.replaceAll("[0-9]{12,}", "••••");
  }
  private void updateStatusRegion() {
    if (statusTitle == null) return;
    for (View control : sheetControls) { control.setEnabled(!OPERATIONS.running());control.setAlpha(OPERATIONS.running() ? .45f : 1f); }
    for (View control : taskControls) { control.setEnabled(!OPERATIONS.running()&&controlAvailability.getOrDefault(control,true));control.setAlpha(control.isEnabled()?1f:.45f); }
    boolean running = OPERATIONS.running();
    boolean pending = getPreferences(0).getBoolean("pending", false);
    if (busyProgress != null) busyProgress.setVisibility(running ? View.VISIBLE : View.GONE);
    statusBox.setVisibility(UiStatusPolicy.visible(running,pending,!notice.isEmpty(),backend!=null,title)?View.VISIBLE:View.GONE);
    statusBox.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    statusTitle.setText(running ? OPERATIONS.title() : !notice.isEmpty() ? pending ? "Needs verification" : "Update" : title);
    statusDetail.setText(running ? detail : !notice.isEmpty() ? notice : detail + (pending ? "\nSafety verification is required. Use Settings → Verify card and recovery before further changes." : ""));
  }
  private void status(String a, String b) {
    notice = "";title = a;detail = b;updateStatusRegion();
  }
  private void statusPreservingOutcome(String a, String b) {
    title = a;detail = b;updateStatusRegion();
  }
  private EditText input(String hint, boolean secret) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setContentDescription(hint);
    e.setId(View.generateViewId());
    e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
    taskControls.add(e);
    e.setTextColor(TEXT);
    e.setHintTextColor(MUTED);
    e.setSingleLine(true);
    e.setMinHeight(dp(56));
    e.setPadding(dp(12), dp(12), dp(12), dp(12));
    GradientDrawable field = new GradientDrawable();
    field.setColor(BG);field.setStroke(dp(1), LINE);field.setCornerRadius(dp(10));e.setBackground(field);
    e.setSaveEnabled(false);
    e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    e.setInputType(InputType.TYPE_CLASS_TEXT
        | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                  : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
    return e;
  }
  private Button action(String label, boolean primary, Runnable click) {
    Button b = button(label, primary);
    taskControls.add(b);
    b.setEnabled(!OPERATIONS.running());
    b.setAlpha(OPERATIONS.running() ? .45f : 1f);
    b.setOnClickListener(v -> {
      if (!OPERATIONS.running()) {
        // Clear a resolved outcome only for a new user action. Automatic inventory
        // and recovery reads must preserve the independently verified result.
        if (!getPreferences(0).getBoolean("pending", false)) {
          notice = "";
          updateStatusRegion();
        }
        click.run();
      } else
        status(OPERATIONS.title(), "Please wait for the current operation to finish.");
    });
    return b;
  }
  private Button button(String label, boolean primary) {
    Button b = new Button(this);
    b.setStateListAnimator(null);
    b.setElevation(0);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTextColor(primary ? (darkTheme?0xff5c1b00:Color.WHITE) : TEXT);
    b.setMinHeight(dp(52));
    b.setPadding(dp(12), dp(10), dp(12), dp(10));
    GradientDrawable d = new GradientDrawable();
    d.setColor(primary ? ACCENT : BG);
    if (!primary) d.setStroke(dp(1), LINE);
    d.setCornerRadius(dp(28));
    b.setBackground(d);
    return b;
  }
  private TextView text(String value, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(3), 1);
    if (bold)
      t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return t;
  }
  private LinearLayout box() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(18), dp(18), dp(18), dp(18));
    GradientDrawable d = new GradientDrawable();
    d.setColor(SURFACE);
    d.setCornerRadius(dp(28));
    l.setBackground(d);
    return l;
  }
  private void add(View v, int top) {
    content.addView(v, margin(top));
  }
  private LinearLayout.LayoutParams margin(int top) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(top);
    return p;
  }
  private int dp(float x) {
    return Math.round(getResources().getDisplayMetrics().density * x);
  }
  private void releaseWhenIdle() {
    if (OPERATIONS.running()) {
      handler.postDelayed(this::releaseWhenIdle, 1000);
      return;
    }
    // Detach the Activity only; never terminate a Shell worker during a transaction.
    backend=null;
  }
  @Override
  protected void onDestroy() {
    clearCredentials();
    clearDetailSelection();
    if (installResultDialog != null) installResultDialog.dismiss();
    if (activeSheet != null) activeSheet.dismiss();
    destroyed = true;
    handler.removeCallbacks(watch);
    releaseWhenIdle();
    super.onDestroy();
  }
}
