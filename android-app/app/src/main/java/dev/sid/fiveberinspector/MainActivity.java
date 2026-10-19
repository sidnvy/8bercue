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
import rikka.shizuku.Shizuku;

/** Three-screen local manager. No credential or transaction replay on recreation. */
public final class MainActivity extends Activity {
  static final int BG = 0xffffffff, SURFACE = 0xfff7f7f5, TEXT = 0xff262626, MUTED = 0xff686868,
                   ACCENT = 0xffc84b14;
  private static final int LINE = 0xffe8e8e5;
  private static final int QR = 40, IMAGE = 41;
  private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
  private static final UiOperationState OPERATIONS = new UiOperationState();
  private IProfileService backend;
  private Shizuku.UserServiceArgs serviceArgs;
  private boolean directManagement;
  private boolean binding, bound, fallback, companionManagement, destroyed, resumed, needsConnectionRead;
  private android.net.Uri pendingImport;
  private int screen;
  private JSONObject inventory = new JSONObject(), cardInfo = new JSONObject();
  private LinearLayout content, statusBox;
  private TextView statusTitle, statusDetail;
  private EditText activation, confirmation;
  private final AddDraft draft = new AddDraft();
  // Profile UI stays in the Activity accessibility tree, not a separate Dialog.
  private final ProfileDetailSession profileSelection = new ProfileDetailSession();
  private View profilePanel;
  private boolean profilePreview;
  private final java.util.ArrayList<View> profilePanelControls = new java.util.ArrayList<>();
  private Dialog installResultDialog;
  private Dialog activeSheet;
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
  private String title = "Connect your card", detail = "Connect using your computer, or choose Shizuku in Settings.",
                 notice = "";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable watch = new Runnable() {
    public void run() {
      if (destroyed || !resumed)
        return;
      if (!OPERATIONS.running()) {
        if (preferDirect() && backend == null) connectDirect();
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
      handler.postDelayed(this, 1000);
    }
  };
  private final Shizuku.OnBinderReceivedListener received =
      () -> runOnUiThread(this::connectIfGranted);
  private final Shizuku.OnBinderDeadListener dead = () -> runOnUiThread(() -> {
    if (companionManagement || directManagement) return; // Shizuku Binder death does not kill local Shell IPC.
    backend = null;
    bound = false;
    binding = false;
    status("Connection lost",
        OPERATIONS.running() ? "Result unknown. Reconnect and verify; do not repeat the operation."
                             : "Restart Shizuku and reconnect.");
  });
  private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> {
    if (code == 901 && result == PackageManager.PERMISSION_GRANTED)
      runOnUiThread(this::connect);
  };
  private final ServiceConnection connection = new ServiceConnection() {
    public void onServiceConnected(ComponentName n, IBinder b) {
      if (useCompanion() || preferDirect()) { binding = false; return; }
      backend = IProfileService.Stub.asInterface(b);
      companionManagement = false; fallback = false;
      bound = true;
      binding = false;
      runOnUiThread(() -> {
        if (destroyed) {
          releaseWhenIdle();
          return;
        }
        needsConnectionRead = true;
        if (resumed && !OPERATIONS.running()) {
          needsConnectionRead = false;
          status("Connected", "Reading your card…");
          checkRecovery();
        }
      });
    }
    public void onServiceDisconnected(ComponentName n) {
      dead.onBinderDead();
    }
  };
  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (saved != null)
      screen = saved.getInt("screen", 0);
    // Owner-authorized Pixel-only internal beta migration. A previous
    // transient fallback had incorrectly persisted preferCompanion=false.
    // One-time explicit preference choices made AFTER this migration remain
    // user-controlled; never rewrite the preference on future launches.
    if (BuildConfig.DEBUG && LabSupport.socketEligible()
        && !getPreferences(0).getBoolean("companionV2StrictPilotMigrated", false)) {
      getPreferences(0).edit().putBoolean("preferCompanion", true)
          .putBoolean("companionV2StrictPilotMigrated", true).commit();
    }
    if (getPreferences(0).getBoolean("pending", false))
      notice = "A previous operation needs verification. Open Settings → Verify card and recovery. "
          + "Do not submit its activation code again.";
    serviceArgs = new Shizuku
                      .UserServiceArgs(
                          new ComponentName(getPackageName(), ProfileUserService.class.getName()))
                      .daemon(false)
                      .processNameSuffix("fiveber-reader")
                      .debuggable(BuildConfig.DEBUG)
                      .version(BuildConfig.VERSION_CODE);
    render();
    Shizuku.addBinderReceivedListenerSticky(received);
    Shizuku.addBinderDeadListener(dead);
    Shizuku.addRequestPermissionResultListener(permission);
  }
  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    connectIfGranted();
    handler.removeCallbacks(watch);
    handler.post(watch);
  }
  @Override
  protected void onPause() {
    resumed = false;
    if(screen==1){clearCredentials();draft.changed();if(activeSheet!=null)activeSheet.dismiss();}
    // Never carry a confirmation token or selection across backgrounding.
    boolean wasPreview = profilePreview;
    dismissProfilePanel();
    if (wasPreview && !OPERATIONS.running())
      statusPreservingOutcome("Review closed", "No nickname write submitted. Review again to proceed.");
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
  private boolean preferDirect() {return getPreferences(0).getBoolean("preferDirectBinder", true);}
  private String managementUnavailable() { return ManagementAvailability.reason(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS); }
  private void connectDirect() {
    if(destroyed||!resumed||OPERATIONS.running()||backend!=null)return;
    IProfileService service=CompanionBinderProvider.management();
    if(service==null)return;
    try {
      service.asBinder().linkToDeath(()->runOnUiThread(()->{
        if(backend!=service)return;
        backend=null;bound=false;navigationSession++;dismissProfilePanel();
        statusPreservingOutcome("Connection lost", "Start the computer connection again. Pending operations will not be repeated.");render();
      }),0);
      backend=service;directManagement=true;companionManagement=true;fallback=false;bound=true;binding=false;
      status("Connected", "Checking card and recovery state…");render();checkRecovery();
    }catch(Exception unavailable){backend=null;bound=false;}
  }
  private void beginDirectBootstrap() {
    if (!managementUnavailable().isEmpty()) { status("Device not supported by this build", managementUnavailable());return; }
    if(OPERATIONS.running()){status("Operation in progress", "Wait for verification before changing connection.");return;}
    try {CompanionBinderProvider.beginManagementBootstrap();}
    catch(SecurityException active){getPreferences(0).edit().putBoolean("preferDirectBinder",true).apply();backend=null;connectDirect();return;}
    backend=null;bound=false;binding=false;fallback=false;binderReadOnlyInventory=false;directManagement=true;companionManagement=true;
    navigationSession++;dismissProfilePanel();
    getPreferences(0).edit().putBoolean("preferDirectBinder",true).apply();
    Sheet help=new Sheet("Connect using your computer");
    help.copy("Connect and authorize this phone with Android platform tools. Within two minutes, run the supplied bootstrap_companion.py with this phone's serial. Keep 8bercue open. No card changes happen during connection.");
    help.copy("A new connection is needed after a reboot or app update. This connection uses no Shizuku.");
    help.button("Waiting for computer",false,help.dialog::dismiss);help.show();
  }
  private boolean useCompanion() {
    return LabSupport.socketEligible()
        && getPreferences(0).getBoolean("preferCompanion", true);
  }
  private void connectIfGranted() {
    if (destroyed || !resumed || binderReadOnlyInventory) return;
    if (!managementUnavailable().isEmpty()) return;
    if (preferDirect()) { connectDirect(); return; }
    if (useCompanion()) {
      if (BuildConfig.DEBUG) android.util.Log.i("FiveberCompanionPilot",
          "connect_companion activeFallback=" + fallback
          + " operationsRunning=" + OPERATIONS.running());
      if (fallback) return;
      if (!OPERATIONS.running()) probeFallback();
      return;
    }
    try {
      if (Shizuku.pingBinder()
          && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        connect();
        return;
      }
    } catch (Throwable ignored) {
    }
    if (LabSupport.socketEligible() && !fallback && !OPERATIONS.running())
      probeFallback();
  }
  private void connect() {
    if (!managementUnavailable().isEmpty()) { status("Device not supported by this build", managementUnavailable());return; }
    if (destroyed || !resumed)
      return;
    try {
      if (!Shizuku.pingBinder()) {
        status("Shizuku is not running",
            "Open Shizuku and start it using wireless debugging or the documented ADB setup. It "
                + "may need restarting after a reboot.");
        return;
      }
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        Shizuku.requestPermission(901);
        return;
      }
      if (bound || binding)
        return;
      binding = true;
      Shizuku.bindUserService(serviceArgs, connection);
    } catch (Throwable e) {
      binding = false;
      status("Connection unavailable", "Open Shizuku, check authorization, then reconnect.");
    }
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
      boolean failed = false;
      try {
        value = job.run();
      } catch (Throwable e) {
        failed = true;
      }
      final JSONObject result = value;
      final boolean error = failed;
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
            // Only implicit Pixel-first discovery falls back automatically.
            // An explicitly selected companion must never silently switch
            // transports after an uncertain or interrupted operation.
            // A temporarily unavailable companion must NOT silently and
            // persistently change the owner's selected transport.
            status("Companion unavailable",
                "Start the Shell Companion with ADB or select Shizuku in Settings.");
            return;
          }
          fallback = true;
          companionManagement = r.optBoolean("generic", false);
          backend = companionManagement ? new CompanionProfileBackend() : null;
          inventory = r.getJSONObject("inventory");
          needsConnectionRead = companionManagement;
          status("Card connected", companionManagement
              ? "Shell Companion management ready (no Shizuku). ADB setup may be needed after reboot."
              : "Legacy companion is read-only. Connect Shizuku for management.");
          render();
        });
  }
  private void selectTransport(boolean companion) {
    if(OPERATIONS.running())return;
    getPreferences(0).edit().putBoolean("preferDirectBinder",false).apply();
    directManagement=false;
    if (OPERATIONS.running()) {
      status("Operation in progress", "Finish verification before changing transport.");
      return;
    }
    if (companion && !LabSupport.socketEligible()) {
      status("Unavailable", "Shell Companion is validated only on Pixel.");
      return;
    }
    getPreferences(0).edit().putBoolean("preferCompanion", companion).apply();
    if (bound || binding) {
      try { Shizuku.unbindUserService(serviceArgs, connection, false); }
      catch (Throwable ignored) {}
      bound = false; binding = false;
    }
    backend = null; fallback = false; companionManagement = false;
    if (binderReadOnlyInventory) {
      binderReadOnlyInventory = false;
      inventory = new JSONObject(); // Never treat a prior read-only card view as a write preview.
    }
    if (companion) probeFallback();
    else connectIfGranted();
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
          if (!r.optBoolean("ok", true)) {
            status(
                "Card unavailable", "No verified inventory was returned. Reconnect and refresh.");
            return;
          }
          inventory = r;
          statusPreservingOutcome(
              "Card connected", "Enabled on card does not confirm cellular network registration.");
          render();
        });
  }
  private void checkRecovery() {
    final IProfileService s = backend;
    if (s == null)
      return;
    run("Checking recovery", false, () -> new JSONObject(s.getOperationStatus()), r -> {
      if (!r.optBoolean("ok", false)) {
        getPreferences(0).edit().putBoolean("pending", true).commit();
        inventory = new JSONObject();notificationCleanupAvailable = false;dismissProfilePanel();
        status("Recovery state unavailable", "Changes are blocked until the safety record can be checked. Do not clear app data or repeat the previous operation.");
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
          String warning = op.equals("delete")
              ? "Permanently delete this eSIM? Its activation code may not be reusable. The "
                  + "service will recheck the exact card and profile before deletion."
              : op.equals("rename") ? "Save nickname: " + mask(nickname) + "?"
              : op.equals("disable")
              ? "Disable this eSIM and leave this card with no enabled profile? This stops service for this eSIM. Keep another connection available. Other cards are not part of this operation."
              : "This changes the active eSIM and can interrupt cellular service. Have another way "
                  + "to connect. Recovery is not guaranteed.";
          status("Preview ready", "No changes made. Review the action, then confirm or cancel.");
          if ("rename".equals(op)) {
            showInlineRenamePreview(index, nickname, r, s, token);
            return;
          }
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
          if (op.equals("download")) {
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
          if (op.equals("download") && verified && !review)
            notice += "\n" + NotificationPresentation.message(notificationStatus);
          if (verified && !review)
            getPreferences(0).edit().remove("pending").commit();
          if (r.optBoolean("restartRequired", false) || !r.optBoolean("notificationWorkerStopped", true)) {
            getPreferences(0).edit().putBoolean("pending", true).commit();
            notice += companionManagement
                ? "\nRestart the Shell Companion, reconnect and verify the card before another operation."
                : "\nRestart Shizuku, reconnect and verify the card before another operation.";
          }
          screen = 0;
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
    if (screen == 1 && destination != 1) { clearCredentials();pendingImport = null;draft.changed(); }
    navigationSession++;
    if (destination != 1) { draft.form = false;showConfirmation = false; }
    if (!getPreferences(0).getBoolean("pending", false)) { notice = "";title = backend != null ? "Card connected" : "Connect your card";detail = backend != null ? "" : "Choose a connection in Settings."; }
    screen = destination;
    render();
  }
  @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (Build.VERSION.SDK_INT < 33 && keyCode == KeyEvent.KEYCODE_BACK) { handleBack(); return true; }
    return super.onKeyUp(keyCode, event);
  }
  private void handleBack() {
    if (profilePanel != null) {
      boolean wasPreview = profilePreview;
      dismissProfilePanel();
      if (wasPreview) status("Review cancelled", "No nickname write submitted.");
      return;
    }
    if (screen == 1 && draft.form) { clearCredentials();activation = null;confirmation = null;draft.form = false;showConfirmation = false;pendingImport = null;draft.changed();navigationSession++;render(); }
    else if (screen != 0) navigate(0);
    else finish();
  }
  private void render() {
    if (destroyed) return;
    dismissProfilePanel(); // A fresh render discards stale Profile actions.
    taskControls.clear();
    if (screen == 1) {
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
        screen == 0 ? "My eSIMs" : screen == 1 ? "Add eSIM" : "Settings",
        screen == 1 && draft.form ? "Back to add methods" : "Back to My eSIMs",
        screen == 0 ? null : this::handleBack,
        screen == 0 ? () -> navigate(2) : null));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(24), 0, dp(24), dp(36));
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
    else settings();
    updateStatusRegion();
  }
  private void home() {
    if (!managementUnavailable().isEmpty()) {
      add(text("Device not supported by this build", 20, TEXT, true), 28);
      add(text(managementUnavailable(), 15, MUTED, false), 10);
      add(text("You can still read setup, privacy and licensing information in Settings.", 14, MUTED, false), 16);return;
    }
    if (binderReadOnlyInventory)
      add(text("Read-only Binder inventory · profile editing requires a separately authorized management connection.", 13, MUTED, false), 18);
    else add(action("+  Add eSIM", true, () -> navigate(1)), 18);
    JSONArray profiles = inventory.optJSONArray("profiles");
    LinearLayout heading = new LinearLayout(this);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.addView(text("Your profiles", 18, TEXT, true), new LinearLayout.LayoutParams(0, dp(52), 1));
    Button refresh = action("Refresh", false, this::refresh);
    refresh.setBackgroundColor(BG);
    refresh.setTextColor(MUTED);
    heading.addView(refresh, new LinearLayout.LayoutParams(dp(90), dp(48)));
    add(heading, 22);
    if (profiles == null) {
      add(text("Your eSIMs will appear here", 20, TEXT, true), 28);
      add(text("Connect your card using a computer, or use Shizuku if you already have it set up.", 15, MUTED, false), 10);
      add(action("Connect using computer", true, this::beginDirectBootstrap), 20);
      add(action("Use Shizuku", false, () -> selectTransport(false)), 12);
      return;
    }
    for (int i = 0; i < profiles.length(); i++) {
      final int index = i;
      JSONObject profile = profiles.optJSONObject(i);
      if (profile == null) continue;
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(0, dp(21), 0, dp(21));
      row.setMinimumHeight(dp(106));
      LinearLayout labels = new LinearLayout(this);
      labels.setOrientation(LinearLayout.VERTICAL);
      String nickname = profile.optString("nickname", "");
      String name = profileName(profile);
      labels.addView(text(mask(name), 19, TEXT, true));
      String subtitle = profileSubtitle(profile);
      if (!subtitle.isEmpty()) labels.addView(text(subtitle, 14, MUTED, false), margin(3));
      boolean enabled = "enabled".equalsIgnoreCase(profile.optString("state"));
      String state = enabled ? "Enabled on card" : "disabled".equalsIgnoreCase(profile.optString("state")) ? "Disabled" : "State unknown";
      labels.addView(text(state + "  ·  " + mask(profile.optString("iccidMasked", "Unavailable")), 12, enabled ? ACCENT : MUTED, false), margin(7));
      row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      TextView arrow = text("›", 29, MUTED, false);
      arrow.setGravity(Gravity.CENTER);
      row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(48)));
      row.setContentDescription(name + ", " + state + ". View profile details");
      row.setFocusable(true);
      android.util.TypedValue ripple = new android.util.TypedValue();
      getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
      row.setBackgroundResource(ripple.resourceId);
      row.setOnClickListener(v -> { if (!OPERATIONS.running()) showProfile(index, profile, row); });
      taskControls.add(row);
      add(row, 0);
      addDivider();
    }
    if (profiles.length() == 0) add(text("No profiles yet. Add an eSIM to get started.", 16, MUTED, false), 22);
    add(text("Network registration not checked. Enabled on card describes the eSIM's card state.", 12, MUTED, false), 20);
    if (backend == null && (fallback || binderReadOnlyInventory)) add(text("Read-only connection. Connect an authorized management transport in Settings to edit profiles.", 13, MUTED, false), 12);
  }
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
        if (primary) { GradientDrawable red = new GradientDrawable();red.setColor(0xffb42318);red.setCornerRadius(dp(12));b.setBackground(red); }
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
  private boolean profileSelectionCurrent(int index, JSONObject profile) {
    JSONArray profiles = inventory.optJSONArray("profiles");
    return !destroyed && resumed && screen == 0 && !OPERATIONS.running()
        && profiles != null && profiles.optJSONObject(index) == profile
        && profileSelection.matches(inventory, backend, profile, index);
  }
  private void dismissProfilePanel() {
    if (profilePanel != null && profilePanel.getParent() instanceof ViewGroup)
      ((ViewGroup) profilePanel.getParent()).removeView(profilePanel);
    profilePanel = null;
    profilePreview = false;
    taskControls.removeAll(profilePanelControls);
    profilePanelControls.clear();
    profileSelection.clear();
  }
  private LinearLayout newProfilePanel(String heading) {
    LinearLayout panel = box();
    panel.setAccessibilityPaneTitle(heading);
    panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    TextView header = text(heading, 20, TEXT, true);
    header.setAccessibilityHeading(true);
    panel.addView(header);
    return panel;
  }
  private void profileCopy(LinearLayout panel, String value) {
    panel.addView(text(value, 14, MUTED, false), margin(12));
  }
  private void profileFact(LinearLayout panel, String label, String value) {
    panel.addView(text(label, 12, MUTED, false), margin(12));
    panel.addView(text(mask(value), 16, TEXT, false), margin(3));
  }
  private void profileAction(LinearLayout panel, String label, boolean primary, Runnable click) {
    Button b = button(label, primary);
    b.setEnabled(!OPERATIONS.running());
    b.setAlpha(OPERATIONS.running() ? .45f : 1f);
    profilePanelControls.add(b);
    taskControls.add(b);
    b.setOnClickListener(v -> {
      if (profilePanel == panel && !destroyed && resumed && !OPERATIONS.running())
        click.run();
    });
    panel.addView(b, margin(12));
  }
  private void attachProfilePanel(LinearLayout panel, View anchor, boolean preview) {
    if (destroyed || !resumed || screen != 0) {
      profileSelection.clear();
      return;
    }
    int position = anchor != null && anchor.getParent() == content
        ? content.indexOfChild(anchor) + 1 : Math.min(1, content.getChildCount());
    content.addView(panel, position, margin(12));
    profilePanel = panel;
    profilePreview = preview;
    // Accessibility services observe the actions in the same Activity window
    // as their Profile row; no separate Dialog window is required.
    panel.post(() -> {
      if (profilePanel == panel && !destroyed && resumed) {
        panel.sendAccessibilityEvent(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        if (content.getParent() instanceof ScrollView)
          ((ScrollView) content.getParent()).smoothScrollTo(0, Math.max(0, panel.getTop() - dp(24)));
      }
    });
  }
  private void showProfile(int index, JSONObject profile, View row) {
    JSONArray profiles = inventory.optJSONArray("profiles");
    if (destroyed || !resumed || OPERATIONS.running() || screen != 0
        || row.getParent() != content || profiles == null || profiles.optJSONObject(index) != profile)
      return;
    if (profilePanel != null && profileSelection.matches(inventory, backend, profile, index)) {
      dismissProfilePanel();
      return;
    }
    dismissProfilePanel();
    profileSelection.open(inventory, backend, profile, index);
    LinearLayout panel = newProfilePanel("Profile details: " + profileName(profile));
    String secondary = profileSubtitle(profile);
    if (!secondary.isEmpty()) profileCopy(panel, secondary);
    String nickname = profile.optString("nickname", "");
    boolean enabled = "enabled".equalsIgnoreCase(profile.optString("state"));
    boolean disabled = "disabled".equalsIgnoreCase(profile.optString("state"));
    profileFact(panel, "Card state",
        enabled ? "Enabled" : disabled ? "Disabled" : "Unknown — refresh the card");
    profileFact(panel, "ICCID", profile.optString("iccidMasked", "Not reported"));
    profileFact(panel, "Nickname", nickname.isEmpty() ? "Not set" : nickname);
    profileCopy(panel, "Card state does not confirm network registration.");
    if (backend != null && inventory.optBoolean("writeQualified",false) && (enabled || disabled)) {
      if (disabled && profile.optBoolean("canEnable",false)) profileAction(panel, "Activate", true, () -> {
        if (!profileSelectionCurrent(index, profile)) return;
        dismissProfilePanel();
        prepare("switch", index, "");
      });
      if(profile.optBoolean("canRename",false)) profileAction(panel, "Rename", false, () -> showProfileRename(index, profile, row));
      if (disabled && profile.optBoolean("canDelete",false)) profileAction(panel, "Delete eSIM", false, () -> {
        if (!profileSelectionCurrent(index, profile)) return;
        dismissProfilePanel();
        prepare("delete", index, "");
      });
      else if(enabled && profile.optBoolean("canDisable",false)) profileAction(panel, "Disable eSIM", false, () -> {
        if (!profileSelectionCurrent(index, profile)) return;
        dismissProfilePanel();prepare("disable", index, "");
      });
    } else if (backend == null) profileCopy(panel, "Connect your card and refresh before editing this profile.");
    else if (!inventory.optBoolean("writeQualified",false))
      profileCopy(panel, "Management is unavailable for this card identity or local test scope. Reconnect and refresh.");
    else profileCopy(panel, "The profile state is unknown. Refresh the card before making changes.");
    profileAction(panel, "Done", false, this::dismissProfilePanel);
    attachProfilePanel(panel, row, false);
  }
  private void showProfileRename(int index, JSONObject profile, View row) {
    if (backend == null || !profileSelectionCurrent(index, profile) || row.getParent() != content)
      return;
    String original = profile.optString("nickname", "");
    dismissProfilePanel();
    profileSelection.open(inventory, backend, profile, index);
    LinearLayout panel = newProfilePanel("Rename eSIM");
    EditText name = input("Nickname", false);
    name.setText(original);
    name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    profilePanelControls.add(name);
    panel.addView(name, margin(16));
    profileCopy(panel, "Keep it short. Leave blank to remove the nickname.");
    profileAction(panel, "Review nickname", true, () -> {
      if (!profileSelectionCurrent(index, profile)) {
        dismissProfilePanel();
        status("Selection changed", "Refresh and select the profile again.");
        return;
      }
      String value = name.getText().toString();
      if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64
          || value.matches("(?s).*[\\p{Cntrl}].*")) {
        name.setError("Use a shorter nickname without control characters");
        return;
      }
      dismissProfilePanel();
      prepare("rename", index, value);
    });
    profileAction(panel, "Cancel", false, () -> {
      dismissProfilePanel();
      showProfile(index, profile, row);
    });
    attachProfilePanel(panel, row, false);
  }
  private void showInlineRenamePreview(int index, String nickname, JSONObject result,
                                       IProfileService preparedBackend, String token) {
    JSONArray profiles = inventory.optJSONArray("profiles");
    JSONObject profile = profiles == null ? null : profiles.optJSONObject(index);
    if (screen != 0 || profile == null || backend != preparedBackend || profilePanel != null) {
      status("Review expired", "No nickname write submitted. Refresh and review again.");
      return;
    }
    profileSelection.open(inventory, backend, profile, index);
    LinearLayout panel = newProfilePanel("Save nickname?");
    profileFact(panel, "Selected eSIM", result.optString("to", profileName(profile)));
    profileCopy(panel, nickname.isEmpty()
        ? "Remove this nickname (set it to blank)? No changes have been made."
        : "Save nickname: " + mask(nickname) + "? No changes have been made.");
    profileAction(panel, "Save nickname", true, () -> {
      if (backend != preparedBackend || !profileSelectionCurrent(index, profile)) {
        dismissProfilePanel();
        status("Selection changed", "No action submitted. Refresh and review again.");
        return;
      }
      dismissProfilePanel();
      execute(preparedBackend, token, "rename");
    });
    profileAction(panel, "Cancel", false, () -> {
      dismissProfilePanel();
      status("Review cancelled", "No nickname write submitted.");
    });
    attachProfilePanel(panel, null, true);
  }
  private void addDivider() {
    View line = new View(this);line.setBackgroundColor(LINE);content.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
  }
  private void addScreen() {
    if (backend == null) {
      add(text("Connect your card to review and install. You can enter or scan your code first.", 14, MUTED, false), 18);
      add(action("Open connection settings", false, () -> navigate(2)), 10);
    }
    if (!draft.form) {
      add(text("How would you like to add your eSIM?", 16, MUTED, false), 22);
      add(action("Scan a QR code  ›", false, () -> startActivityForResult(new Intent(this, QrScanActivity.class), QR)), 24);
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
  private void settings() {
    add(text("Connection", 19, TEXT, true), 24);
    add(text(directManagement && backend != null ? "Independent Binder connected — no Shizuku"
          : companionManagement ? "Shell Companion connected — no Shizuku"
          : binderReadOnlyInventory ? "Standalone Binder read-only inventory — no Shizuku"
          : backend != null ? "Shizuku connected"
          : fallback ? "Read-only companion connected" : "Not connected", 15, MUTED, false), 8);
    if (LabSupport.socketEligible())
      add(action("Use Shell Companion (no Shizuku)", useCompanion(), () -> selectTransport(true)), 12);
    add(action("Connect using computer (no Shizuku)", preferDirect(), this::beginDirectBootstrap), 12);
    add(action("Use Shizuku", !useCompanion() && !preferDirect(), () -> selectTransport(false)), 12);
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
          backend = null;
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
      help.copy("With a computer: connect this phone, accept its debugging prompt, then choose Connect using computer and follow the supplied setup instructions. Repeat connection after an app update or phone restart.");
      help.copy("With Shizuku: install the official app, start its service, then choose Use Shizuku and authorize 8bercue. Connections are selected explicitly; failed operations are not replayed.");help.button("Done", false, help.dialog::dismiss);help.show();
    }), 12);
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
            notice += companionManagement
                ? "\nRestart the Shell Companion, reconnect and verify the card before another operation."
                : "\nRestart Shizuku, reconnect and verify the card before another operation.";
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
  private void exportDiagnostics() {
    String report = "8bercue " + BuildConfig.VERSION_NAME + "\nAndroid API "
        + Build.VERSION.SDK_INT + "\nManagement connection: " + (backend == null ? "none" : directManagement ? "independent Binder" : companionManagement ? "Shell socket" : "Shizuku")
        + "\nLocal fallback: " + fallback + "\nProfile count: "
        + (inventory.optJSONArray("profiles") == null ? "unknown"
                                                      : inventory.optJSONArray("profiles").length())
        + "\nOperation pending verification: " + getPreferences(0).getBoolean("pending", false)
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
    if (request == QR) {
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
    for (View control : taskControls) { control.setEnabled(!OPERATIONS.running());control.setAlpha(OPERATIONS.running() ? .45f : 1f); }
    boolean running = OPERATIONS.running();
    boolean pending = getPreferences(0).getBoolean("pending", false);
    if (busyProgress != null) busyProgress.setVisibility(running ? View.VISIBLE : View.GONE);
    statusBox.setVisibility(screen != 0 && !running && !pending && notice.isEmpty() && (title.equals("Card connected") || title.equals("Check complete")) ? View.GONE : View.VISIBLE);
    statusBox.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    statusTitle.setText(running ? OPERATIONS.title() : !notice.isEmpty() ? pending ? "Needs verification" : "Update" : title);
    statusDetail.setText(running ? detail : !notice.isEmpty() ? notice : detail + (pending ? "\nA previous operation still needs verification. Use Settings → Verify card and recovery before further changes." : ""));
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
    b.setTextColor(primary ? Color.WHITE : TEXT);
    b.setMinHeight(dp(52));
    b.setPadding(dp(12), dp(10), dp(12), dp(10));
    GradientDrawable d = new GradientDrawable();
    d.setColor(primary ? ACCENT : BG);
    if (!primary) d.setStroke(dp(1), LINE);
    d.setCornerRadius(dp(12));
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
    d.setCornerRadius(dp(12));
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
    if (bound || binding) {
      try {
        Shizuku.unbindUserService(serviceArgs, connection, false);
      } catch (Throwable ignored) {
      }
      bound = false;
      binding = false;
    }
  }
  @Override
  protected void onDestroy() {
    clearCredentials();
    dismissProfilePanel();
    if (installResultDialog != null) installResultDialog.dismiss();
    if (activeSheet != null) activeSheet.dismiss();
    destroyed = true;
    handler.removeCallbacks(watch);
    Shizuku.removeBinderReceivedListener(received);
    Shizuku.removeBinderDeadListener(dead);
    Shizuku.removeRequestPermissionResultListener(permission);
    releaseWhenIdle();
    super.onDestroy();
  }
}
