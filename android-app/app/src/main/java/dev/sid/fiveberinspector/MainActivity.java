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
  private boolean recoveryReconnectNeeded,recoveryBootstrap,recoveryReviewEligible;
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
  private String title = "", detail = "",
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
            notice = s(R.string.ui_an_operation_ended_while_this_screen_was_unavailable_verify_the);
            statusPreservingOutcome(s(R.string.ui_needs_verification), s(R.string.ui_reading_the_card_to_establish_its_current_state));
          } else
            status(s(R.string.ui_check_ended), s(R.string.ui_refreshing_the_card_after_the_interrupted_screen));
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
      if(screen==6 && (backend==null||recoveryBootstrap)){
        long remaining=connectionDeadline-android.os.SystemClock.elapsedRealtime();
        if(connectionCountdown!=null)connectionCountdown.setText(countdown(remaining));
        if(!connectionExpired && remaining<=0){
          connectionExpired=true;
          compatibilityFailure(CompatibilityDiagnostics.Stage.BOOTSTRAP,
              CompatibilityDiagnostics.Failure.BOOTSTRAP_TIMEOUT);
          statusPreservingOutcome(s(R.string.ui_connection_window_expired),s(R.string.ui_tap_start_connection_again_then_rerun_the_same_command));
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
            statusPreservingOutcome("STARTING".equals(feedback)?s(R.string.ui_starting_local_service):s(R.string.ui_connection_not_completed),UiMessages.known(MainActivity.this,ConnectionFeedback.message(feedback)));
            if (!"STARTING".equals(feedback)) render();
          }
        }
      }
      handler.postDelayed(this, 1000);
    }
  };
  @Override protected void attachBaseContext(Context base){super.attachBaseContext(AppLanguage.wrap(base));}
  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    title = s(R.string.ui_connect_your_card); detail = s(R.string.ui_connect_using_your_computer_to_check_this_card);
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
      notice = s(R.string.ui_card_and_operation_state_need_verification_open_settings_verify);
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
  private String canonicalTitle(){
    if(title.equals(s(R.string.ui_card_connected)))return "Card connected";
    if(title.equals(s(R.string.ui_check_complete)))return "Check complete";
    if(title.equals(s(R.string.ui_card_selected)))return "Card selected";
    if(title.equals(s(R.string.ui_connected)))return "Connected";
    return title;
  }
  private String operationLabel(String op){return switch(op){
    case "switch", "enable" -> s(R.string.operation_switch);case "disable" -> s(R.string.operation_disable);
    case "rename" -> s(R.string.operation_rename);case "delete" -> s(R.string.operation_delete);
    case "download" -> s(R.string.operation_download);case "cleanup" -> s(R.string.operation_cleanup);
    default -> op;
  };}
  private String countdown(long millis){long seconds=Math.max(0,(millis+999)/1000);return seconds==0?s(R.string.ui_connection_window_expired):s(R.string.connection_countdown,String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60));}
  private String connectionNotice(JSONObject result){
    String reader=result.optString("reader","");
    String explanation=s(reader.startsWith("telephony.")?R.string.connection_telephony_notice:reader.startsWith("hidl.")?R.string.connection_hidl_notice:R.string.connection_verified_notice);
    return s(R.string.connection_route_notice,compatibility.optString("route",s(R.string.ui_no_verified_card_connection)),explanation);
  }
  private String readerDescription(String reader,String original){
    if(!reader.startsWith("telephony."))return reader.isEmpty()?original:s(R.string.secure_reader,reader);
    try{int[] slot=ReaderDescriptor.telephonyIndices(reader);String address=s(R.string.physical_slot,slot[0],slot[1]);
      return original.startsWith("Removable eUICC")?s(R.string.removable_card,address):original.startsWith("Built-in eUICC")?s(R.string.built_in_card,address):address;
    }catch(SecurityException invalid){return original;}
  }
  private void chooseLanguage(){
    Sheet language=new Sheet(s(R.string.language_title));language.copy(s(R.string.language_help));
    String[] tags={"","zh-Hans","en"};int[] labels={R.string.language_system,R.string.language_chinese,R.string.language_english};
    for(int i=0;i<tags.length;i++){final String tag=tags[i];language.button(s(labels[i]),false,()->{language.dialog.dismiss();AppLanguage.select(this,tag);});}
    language.button(s(R.string.ui_cancel),false,language.dialog::dismiss);language.show();
  }
  private String managementUnavailable() { return UiMessages.known(MainActivity.this,ManagementAvailability.reason(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)); }
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
        statusPreservingOutcome(s(R.string.ui_connection_lost), s(R.string.ui_start_the_computer_connection_again_pending_operations_will_not));render();
      }),0);
      backend=service;directManagement=true;companionManagement=true;fallback=false;
      recoveryBootstrap=false;
      CompatibilityDiagnostics.ready(CompatibilityDiagnostics.Transport.ADB_COMPANION);
      if(bootstrapSheet!=null){bootstrapSheet.dismiss();bootstrapSheet=null;}
      if(screen==6)screen=0;
      status(s(R.string.ui_connected), s(R.string.ui_checking_card_and_recovery_state));render();checkRecovery();
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
      status(s(R.string.ui_device_not_supported_by_this_build), managementUnavailable());render();
      return;
    }
    if(OPERATIONS.running()){status(s(R.string.ui_operation_in_progress), s(R.string.ui_wait_for_verification_before_changing_connection));return;}
    boolean recovering=recoveryReconnectNeeded&&backend!=null;
    try {if(recovering)CompanionBinderProvider.beginManagementRecoveryBootstrap();else CompanionBinderProvider.beginManagementBootstrap();}
    catch(SecurityException active){backend=null;compatibility=new JSONObject();connectDirect();return;}
    recoveryBootstrap=recovering;
    if(!recovering)backend=null;
    compatibility=new JSONObject();fallback=false;binderReadOnlyInventory=false;directManagement=true;companionManagement=true;
    navigationSession++;clearDetailSelection();

    try { connectionCommand=ConnectionCommand.forApk(getApplicationInfo().sourceDir); }
    catch(IllegalArgumentException invalid){status(s(R.string.ui_command_unavailable),s(R.string.ui_reinstall_the_verified_apk_before_connecting));render();return;}
    CompatibilityDiagnostics.started(CompatibilityDiagnostics.Transport.ADB_COMPANION);
    connectionDeadline=android.os.SystemClock.elapsedRealtime()+120000;connectionExpired=false;connectionFeedback="";statusPreservingOutcome(s(R.string.ui_waiting_for_computer),s(R.string.ui_copy_the_command_below_and_run_it_within_two_minutes));screen=6;render();
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
    if (!managementUnavailable().isEmpty()) { status(s(R.string.ui_device_not_supported_by_this_build),managementUnavailable());return; }
    connectExistingCompanion();
    if(backend==null)status(s(R.string.ui_connect_your_card),s(R.string.ui_choose_connect_using_computer_and_copy_and_run_its_adb_command));
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
    final boolean contextual = label.equals(s(R.string.ui_reading_qr_image)) || label.equals(s(R.string.ui_validating_activation_code)) || label.startsWith(s(R.string.ui_reviewing));
    final long ticket = OPERATIONS.begin(label, mutation);
    if (ticket == 0)
      return;
    if (mutation)
      getPreferences(0).edit().putBoolean("pending", true).commit();
    statusPreservingOutcome(label,
        mutation
            ? s(R.string.ui_one_attempt_in_progress_keep_the_phone_powered_on_the_result_wil)
            : label.equals(s(R.string.ui_reading_qr_image)) ? s(R.string.ui_decoding_the_image_on_this_phone_no_card_changes) : s(R.string.ui_reading_and_checking_your_card_no_changes_are_being_made));
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
              ? s(R.string.ui_result_unknown_reconnect_and_verify_the_card_do_not_retry_automa)
              : label.equals(s(R.string.ui_reading_qr_image))
              ? s(R.string.ui_no_readable_qr_code_was_found_choose_a_clear_image_or_enter_the)
              : s(R.string.ui_could_not_complete_the_check_reconnect_and_try_a_read_only_refre);
          if (mutation)
            notice = message;
          if (!mutation && label.equals(s(R.string.ui_reading_your_esims)))
            compatibilityFailure(CompatibilityDiagnostics.Stage.INVENTORY,
                denied ? CompatibilityDiagnostics.Failure.INVENTORY_ACCESS_DENIED
                       : CompatibilityDiagnostics.Failure.INVENTORY_READ_FAILED);
          status(mutation ? s(R.string.ui_needs_verification) : s(R.string.ui_check_unavailable), message);
          render();
          return;
        }
        try {
          if (!mutation)
            statusPreservingOutcome(s(R.string.ui_check_complete), s(R.string.ui_no_changes_were_made));
          done.accept(result);
        } catch (Exception e) {
          notice = s(R.string.ui_the_response_could_not_be_verified_read_the_card_again_before_co);
          render();
        }
      });
    });
  }
  private void probeFallback() {
    if (!LabSupport.socketEligible()) return;
    run(s(R.string.ui_checking_companion), false,
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
            throw new IllegalStateException(s(R.string.ui_companion_inventory_not_verified));
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
            status(s(R.string.ui_companion_unavailable),
                s(R.string.ui_use_connect_using_computer_and_copy_and_run_the_adb_command_show));
            return;
          }
          CompatibilityDiagnostics.ready(CompatibilityDiagnostics.Transport.ADB_COMPANION);
          fallback = true;
          companionManagement = r.optBoolean("generic", false);
          backend = companionManagement ? new CompanionProfileBackend() : null;
          inventory = r.getJSONObject("inventory");
          needsConnectionRead = companionManagement;
          status(s(R.string.ui_card_connected), companionManagement
              ? s(R.string.ui_computer_connection_ready_reconnect_after_a_reboot_or_app_update)
              : s(R.string.ui_legacy_companion_is_read_only_use_connect_using_computer_for_man));
          render();
        });
  }
  private void selectLegacyCompanion() {
    if(OPERATIONS.running()){status(s(R.string.ui_operation_in_progress),s(R.string.ui_wait_for_verification_before_changing_connection));return;}
    if(!LabSupport.socketEligible())return;
    directManagement=false;backend=null;compatibility=new JSONObject();fallback=false;companionManagement=false;
    binderReadOnlyInventory=false;inventory=new JSONObject();probeFallback();
  }
  private void refresh() {
    final IProfileService s = backend;
    if (s == null && binderReadOnlyInventory) {
      status(s(R.string.ui_read_only_inventory), s(R.string.ui_single_use_binder_read_complete_reconnect_the_service_before_ano));
      return;
    }
    if (s == null && !fallback) {
      connect();
      return;
    }
    run(s(R.string.ui_reading_your_esims), false,
        () -> s != null ? new JSONObject(s.listProfiles()) : ShellCompanionClient.list(), r -> {
          compatibility=r.optJSONObject("compatibility")==null?new JSONObject():r.optJSONObject("compatibility");
          if (!r.optBoolean("ok", true)) {
            compatibilityFailure(CompatibilityDiagnostics.Stage.INVENTORY,
                CompatibilityDiagnostics.Failure.INVENTORY_READ_FAILED);
            inventory=new JSONObject();notificationCleanupAvailable=false;clearDetailSelection();
            if(!r.optString("recoveryError").isEmpty()){
              recoveryReconnectNeeded=true;
              getPreferences(0).edit().putBoolean("pending",true).commit();
              status(s(R.string.recovery_access_paused),recoveryFailure(r.optString("recoveryError")));render();return;
            }
            String limitation=UiMessages.known(MainActivity.this,SecureElementFailure.message(r.optString("capabilityError", "")));
            status(s(R.string.ui_card_unavailable), (limitation.isEmpty()
                ? s(R.string.ui_no_verified_inventory_was_returned_reconnect_and_refresh) : limitation)+
                  (compatibility.length()>0?s(R.string.ui_open_compatibility_check_for_the_result_of_each_channel):""));
            render();return;
          }
          inventory = r;
          recoveryReconnectNeeded=false;
          if("READ_ONLY_RECOVERED".equals(r.optString("recoveryState"))){
            getPreferences(0).edit().putBoolean("pending",true).commit();
            notice=s(R.string.recovery_observed_unknown,operationLabel(r.optString("previousOperation")));
          }
          if (screen==3 || screen==4 || restoreDetailAfterRead) {
            JSONArray rows=inventory.optJSONArray("profiles");int found=-1;
            if(rows!=null)for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i)!=null&&!selectedProfileKey.isEmpty()&&selectedProfileKey.equals(rows.optJSONObject(i).optString("selectionId"))) {if(found!=-1){found=-1;break;}found=i;}
            if(found>=0){selectedProfile=found;detailProfile=rows.optJSONObject(found);detailSelection.open(inventory,backend,detailProfile,found);screen=3;}
            else {selectedProfile=-1;detailProfile=null;screen=0;}
            restoreDetailAfterRead=false;
          }
          statusPreservingOutcome(
              s(R.string.ui_card_connected), connectionNotice(r));
          render();
        });
  }
  private void checkRecovery() {
    final IProfileService s = backend;
    if (s == null)
      return;
    run(s(R.string.ui_checking_recovery), false, () -> new JSONObject(s.getOperationStatus()), r -> {
      if (!r.optBoolean("ok", false)) {
        if(RecoveryUiPolicy.needsCardChoice(r.optString("capabilityError",""))){
          inventory=new JSONObject();notificationCleanupAvailable=false;clearDetailSelection();
          statusPreservingOutcome(s(R.string.ui_choose_a_card),s(R.string.ui_more_than_one_euicc_is_readable_select_the_card_you_want_to_mana));render();return;
        }
        compatibilityFailure(CompatibilityDiagnostics.Stage.INVENTORY,
            CompatibilityDiagnostics.Failure.INVENTORY_READ_FAILED);
        getPreferences(0).edit().putBoolean("pending", true).commit();
        inventory = new JSONObject();notificationCleanupAvailable = false;clearDetailSelection();
        String limitation=UiMessages.known(MainActivity.this,SecureElementFailure.message(r.optString("capabilityError", "")));
        status(s(R.string.ui_recovery_state_unavailable), limitation.isEmpty()
            ? s(R.string.ui_changes_are_blocked_until_the_safety_record_can_be_checked_do_no)
            : limitation+s(R.string.ui_changes_remain_blocked_until_card_and_recovery_verification_can));
        render();return;
      }
      recoveryReconnectNeeded="SAFETY_PAUSED".equals(r.optString("recoveryState"));
      recoveryReviewEligible=r.optString("operation","").matches("enable|disable|rename|switch")
          &&r.optString("originalOutcome","").matches("running|unknown|observed_unknown")
          &&r.optBoolean("requiresReconciliation",false);
      String priorNotificationMessage = UiMessages.known(MainActivity.this,NotificationPresentation.message(notificationStatus));
      notificationStatus = r.optString("notificationStatus", "");
      if ("none".equals(notificationStatus)) notificationStatus = "";
      if (notice.equals(priorNotificationMessage)) notice = notificationStatus.isEmpty() ? "" : UiMessages.known(MainActivity.this,NotificationPresentation.message(notificationStatus));
      notificationCleanupAvailable = r.optBoolean("notificationCleanupAvailable", false);
      String phase = r.optString("status", r.optString("stage", "unknown"));
      boolean recoveryBlocked = RecoveryUiPolicy.blocks(true, r.optBoolean("requiresReconciliation", true), r.optBoolean("reviewRequired", true), phase);
      boolean notificationReviewRequired = r.optBoolean("notificationReviewRequired", false);
      if(RecoveryUiPolicy.keepsPending(recoveryBlocked,notificationReviewRequired)){
        getPreferences(0).edit().putBoolean("pending",true).commit();
      }else{
        // Both durable journal and notification delivery must be reconciled before
        // dismissing the local pending state. Card-specific write policy still applies.
        getPreferences(0).edit().remove("pending").commit();
      }
      if(recoveryBlocked)
        notice=s(R.string.ui_recovery_check)+safeState(phase)
            +s(R.string.ui_use_verify_card_and_recovery_before_making_more_changes);
      if (notificationReviewRequired)
        notice += (notice.isEmpty() ? "" : "\n") + UiMessages.known(MainActivity.this,NotificationPresentation.message(notificationStatus));
      refresh();
    });
  }
  private void reconcile() {
    final IProfileService s = backend;
    if (s == null) {
      connect();
      return;
    }
    run(s(R.string.ui_verifying_card_and_recovery), false, () -> new JSONObject(s.reconcileOperation()), r -> {
      if(!r.optBoolean("ok",false)){
        getPreferences(0).edit().putBoolean("pending",true).commit();
        status(s(R.string.recovery_access_paused),recoveryFailure(r.optString("recoveryError")));render();return;
      }
      boolean review = RecoveryUiPolicy.blocks(r.optBoolean("ok", false), r.optBoolean("requiresReconciliation", false), r.optBoolean("reviewRequired", true), r.optString("status", "unknown"));
      notice = review ? s(R.string.ui_card_read_completed_the_previous_operation_still_requires_review)
                      : "reviewed_abandoned".equals(r.optString("status"))
                          ? s(R.string.ui_the_unknown_download_was_reviewed_and_abandoned_the_profile_inve)
                          : s(R.string.ui_read_only_reconciliation_completed_check_the_current_profile_inv);
      if (!review)
        getPreferences(0).edit().remove("pending").commit();
      refresh();
    });
  }
  /**
   * Separate from read-only verification: a one-use, explicit consent to accept
   * the currently observed inventory without claiming the previous write succeeded.
   */
  private void reviewUnknownLifecycle() {
    final IProfileService service=backend;
    if(service==null||OPERATIONS.running())return;
    final long reviewNavigation=navigationSession;
    run(s(R.string.recovery_review_checking),false,
        ()->new JSONObject(service.prepareRecoveryReview()),proposal->{
      if(backend!=service||navigationSession!=reviewNavigation)return;
      if(!proposal.optBoolean("ok",false)){
        notice=s(R.string.recovery_review_failed);render();return;
      }
      String token=proposal.optString("token","");
      if(!OperationTokens.isValid(token)){
        notice=s(R.string.recovery_review_failed);render();return;
      }
      Sheet review=new Sheet(s(R.string.recovery_review_title));
      review.copy(s(R.string.recovery_review_warning));
      review.copy(proposal.optBoolean("inventoryChanged",true)
          ?s(R.string.recovery_review_changed):s(R.string.recovery_review_unchanged));
      review.button(s(R.string.recovery_review_confirm),true,()->{
        review.dialog.dismiss();
        if(backend!=service||navigationSession!=reviewNavigation)return;
        run(s(R.string.recovery_review_checking),false,
            ()->new JSONObject(service.confirmRecoveryReview(token)),result->{
          // The asynchronous result belongs only to the session that started it.
          // A detached Binder or a new navigation must never clear pending.
          if(backend!=service||navigationSession!=reviewNavigation){
            getPreferences(0).edit().putBoolean("pending",true).commit();
            return;
          }
          if(!result.optBoolean("ok",false)||!"reviewed_observed".equals(result.optString("status"))){
            getPreferences(0).edit().putBoolean("pending",true).commit();
            notice=s(R.string.recovery_review_failed);render();return;
          }
          recoveryReviewEligible=false;
          getPreferences(0).edit().remove("pending").commit();
          notice=s(R.string.recovery_review_completed);
          checkRecovery();
        });
      });
      review.button(s(R.string.ui_cancel),false,review.dialog::dismiss);
      review.show();
    });
  }
  private String recoveryFailure(String code){
    if("RECOVERY_CARD_CHANGED".equals(code))return s(R.string.recovery_card_changed);
    if("RECOVERY_INVENTORY_UNSTABLE".equals(code))return s(R.string.recovery_inventory_unstable);
    if("RECOVERY_EVIDENCE_INVALID".equals(code)||"RECOVERY_IDENTITY_UNVERIFIED".equals(code))return s(R.string.recovery_evidence_unverified);
    return s(R.string.recovery_reconnect_for_read);
  }
  private void prepare(String op, int index, String nickname) {
    final IProfileService s = backend;
    if (s == null || OPERATIONS.running())
      return;
    final long previewSession = navigationSession;
    final JSONObject previewInventory = inventory;
    run(s(R.string.ui_reviewing) + operationLabel(op), false,
        ()
            -> new JSONObject(op.equals("switch") ? s.prepareSwitch(index)
                    : op.equals("rename")         ? s.prepareRename(index, nickname)
                    : op.equals("delete")         ? s.prepareDelete(index)
                                                  : s.prepareDisable(index)),
        r -> {
          if (previewSession != navigationSession || inventory != previewInventory || backend != s) return;
          if (!r.optBoolean("ok", true)) {
            notice = s(R.string.ui_the_card_safety_policy_did_not_allow_this_action_refresh_the_inv);
            render();
            return;
          }
          if (!r.optBoolean("writeQualified", false)) {
            notice = s(R.string.ui_management_is_unavailable_for_this_card_identity_or_local_test_s);
            render();
            return;
          }
          String token = r.optString("token", "");
          if (token.isEmpty()) {
            notice = s(R.string.ui_no_valid_confirmation_token_was_returned);
            render();
            return;
          }
          if (ProfileActionPolicy.executesDirectly(op)) {
            execute(s,token,op);return;
          }
          String warning = s(R.string.ui_this_permanently_removes_the_selected_profile_from_this_card_its);
          status(s(R.string.ui_review_deletion), s(R.string.ui_check_the_selected_card_and_profile_before_deleting));
          final boolean[] confirmed = {false};
          Sheet preview = new Sheet(op.equals("switch") ? s(R.string.ui_activate_esim) : op.equals("delete") ? s(R.string.ui_delete_esim) : op.equals("disable") ? s(R.string.ui_disable_esim) : s(R.string.ui_save_nickname));
          if (op.equals("switch")) preview.fact(s(R.string.ui_currently_enabled), r.optString("from", "").isEmpty() ? s(R.string.ui_none_on_this_card) : r.optString("from"));
          preview.fact(s(R.string.ui_selected_esim), r.optString("to", s(R.string.ui_selected_esim)));preview.copy(warning);
          preview.button(op.equals("delete") ? s(R.string.ui_delete_permanently) : op.equals("switch") ? s(R.string.ui_activate_once) : op.equals("disable") ? s(R.string.ui_disable_once) : s(R.string.ui_save_nickname_79), true, () -> {
            confirmed[0] = true;preview.dialog.dismiss();execute(s, token, op);
          });
          preview.button(s(R.string.ui_cancel), false, preview.dialog::dismiss);
          preview.dialog.setOnDismissListener(d -> { if (!confirmed[0]) status(s(R.string.ui_review_cancelled), s(R.string.ui_no_changes_were_made)); });preview.show();
        });
  }
  private void execute(IProfileService s, String token, String op) {
    if (backend != s) {
      status(s(R.string.ui_connection_changed), s(R.string.ui_no_action_submitted_reconnect_and_prepare_a_new_preview));
      return;
    }
    run(op.equals("download") ? s(R.string.ui_installing_and_verifying) : s(R.string.ui_applying) + operationLabel(op), true,
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
                        ? s(R.string.ui_esim_installed_and_independently_verified_it_remains_disabled_yo)
                        : s(R.string.ui_operation_verified_by_a_fresh_card_read_review_the_updated_inven))
              : s(R.string.ui_operation_requires_review_verify_the_card_before_any_further_act);
          if (op.equals("switch") && verified && !review) {
            String system = r.optString("systemSubscriptionStatus", "unavailable");
            notice = s(R.string.ui_esim_enabled_and_verified_on_the_card)
                + ("matched".equals(system)
                    ? s(R.string.ui_android_reports_the_selected_esim_as_an_active_subscription_netw)
                    : "mismatch".equals(system)
                    ? s(R.string.ui_android_has_not_confirmed_the_selected_subscription_yet_check_si)
                    : s(R.string.ui_android_subscription_status_could_not_be_checked_check_sim_setti));
          }
          if (r.has("notificationStatus") && verified && !review)
            notice += "\n" + UiMessages.known(MainActivity.this,NotificationPresentation.message(notificationStatus));
          if (verified && !review)
            getPreferences(0).edit().remove("pending").commit();
          if (r.optBoolean("restartRequired", false) || !r.optBoolean("notificationWorkerStopped", true)) {
            getPreferences(0).edit().putBoolean("pending", true).commit();
            notice += s(R.string.ui_reconnect_the_local_service_using_your_computer_and_verify_the_c);
          }
          recentResults.add(0, (verified && !review ? s(R.string.ui_verified) : s(R.string.ui_needs_review)) + operationLabel(op));
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
    Sheet result = new Sheet(s(R.string.ui_esim_installed));installResultDialog = result.dialog;
    result.copy(s(R.string.ui_installation_was_independently_verified_on_your_card));
    result.fact(s(R.string.ui_new_esim), s(R.string.ui_disabled_ready_to_activate_later));
    result.fact(s(R.string.ui_existing_profiles), s(R.string.ui_unchanged));
    result.fact(s(R.string.ui_operator_notification), UiMessages.known(MainActivity.this,NotificationPresentation.message(notificationStatus)));
    result.dialog.setCancelable(false);result.dialog.setCanceledOnTouchOutside(false);
    result.button(s(R.string.ui_done), true, () -> { result.dialog.dismiss();screen = 0;render(); });result.show();
  }
  private void reviewDownload() {
    if (backend == null) {
      status(s(R.string.ui_connect_your_card_first),
          s(R.string.ui_installation_requires_the_authorized_on_phone_management_service));
      return;
    }
    final IProfileService s = backend;
    String code = activation.getText().toString().trim(), cc = confirmation.getText().toString();
    if (code.isEmpty()) {
      activation.setError(s(R.string.ui_enter_an_lpa_activation_code_or_scan_a_qr_code));
      return;
    }
    final ActivationCode parsed;
    try {
      parsed = ActivationCode.parse(code);
      if (parsed.confirmationRequired && cc.isEmpty()) {
        showConfirmation = true;render();confirmation.setError(s(R.string.ui_your_operator_requires_a_confirmation_code));confirmation.requestFocus();return;
      }
      parsed.confirmation(cc);
    } catch (IllegalArgumentException invalid) {
      activation.setError(UiMessages.known(MainActivity.this,invalid.getMessage()));
      return;
    }
    final long reviewedSession = draft.revision();
    run(s(R.string.ui_validating_activation_code), false,
        () -> {
          JSONObject target = new JSONObject(s.getCardInfo());
          if (!target.optBoolean("ok", false)) throw new IllegalStateException(s(R.string.ui_card_verification_unavailable));
          return new JSONObject(s.prepareDownload(code, cc)).put("targetCard", target.optString("eidMasked", s(R.string.ui_not_reported)));
        }, r -> {
          if (screen != 1 || !draft.isCurrent(reviewedSession) || backend != s) return;
          if (!r.optBoolean("ok", true)) {
            notice = s(R.string.ui_activation_code_could_not_be_accepted_check_the_code_confirmatio);
            render();
            return;
          }
          if (!r.optBoolean("writeQualified", false)) {
            notice = s(R.string.ui_activation_code_reviewed_this_card_is_read_only_so_installation);
            render();
            return;
          }
          String token = r.optString("token", "");
          if (token.isEmpty()) {
            status(s(R.string.ui_review_unavailable), s(R.string.ui_no_installation_token_was_returned));
            return;
          }
          status(
              s(R.string.ui_installation_review_ready), s(R.string.ui_no_changes_made_confirm_once_to_install_or_cancel));
          final boolean[] confirmed = {false};
          Sheet preview = new Sheet(s(R.string.ui_install_this_esim));
          preview.fact(s(R.string.ui_operator), parsed.host);preview.fact(s(R.string.ui_card), r.optString("targetCard", s(R.string.ui_verified_connected_card)));
          preview.copy(s(R.string.ui_the_new_esim_stays_disabled_existing_profiles_keep_their_current));
          preview.copy(s(R.string.ui_the_operator_may_consume_this_code_installation_is_attempted_onc));
          preview.button(s(R.string.ui_install_once), true, () -> {
            confirmed[0] = true;preview.dialog.dismiss();clearCredentials();execute(s, token, "download");
          });
          preview.button(s(R.string.ui_cancel), false, preview.dialog::dismiss);
          preview.dialog.setOnDismissListener(d -> { if (!confirmed[0]) status(s(R.string.ui_installation_cancelled), s(R.string.ui_no_download_started_no_changes_were_made)); });preview.show();
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
    if (!getPreferences(0).getBoolean("pending", false)) { notice = "";title = backend != null ? s(R.string.ui_card_connected) : s(R.string.ui_connect_your_card);detail = backend != null ? "" : s(R.string.ui_connect_using_your_computer); }
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
        screen == 0 ? "8bercue" : screen == 1 ? s(R.string.ui_add_esim) : screen == 3 ? s(R.string.ui_esim_details) : screen == 4 ? s(R.string.ui_rename_esim) : screen == 5 ? s(R.string.ui_operation_records) : screen == 6 ? s(R.string.ui_connect_your_phone) : s(R.string.ui_settings),
        screen == 1 && draft.form ? s(R.string.ui_back_to_add_methods) : s(R.string.ui_back_to_my_esims),
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
      Button fab=action(s(R.string.ui_add_esim_134),false,()->navigate(1));fab.setTextColor(ON_TONAL);fab.setBackground(rounded(TONAL,16));fab.setElevation(dp(2));
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
  private boolean wirelessOnboardingAvailable() {
    return Build.VERSION.SDK_INT >= 31 && LabSupport.wirelessEligible()
        && OnDeviceAdbNativeCrypto.available();
  }
  private String primaryConnectionLabel() {
    if (!wirelessOnboardingAvailable()) return s(R.string.ui_connect_using_computer);
    return OnDeviceAdbWirelessManager.hasPaired(this)
        ? s(R.string.wireless_adb_live_connect) : s(R.string.wireless_adb_live_entry);
  }
  private void primaryConnect() {
    if (!wirelessOnboardingAvailable()) { beginDirectBootstrap();return; }
    if (!OnDeviceAdbWirelessManager.hasPaired(this)) {showWirelessDiscoveryPreview();return;}
    run(s(R.string.wireless_adb_live_reconnecting),false,()->{
      LocalAdbBootstrap.Result attempt=LocalAdbBootstrap.start(this);
      return new JSONObject().put("ready",attempt.ready)
          .put("description",attempt.description);
    },r->{
      if(r.optBoolean("ready")) connectDirect();
      else status(s(R.string.wireless_adb_live_unavailable),
                  r.optString("description",s(R.string.wireless_adb_live_unavailable)));
    });
  }

  private void home() {
    if (!managementUnavailable().isEmpty()) {
      add(text(s(R.string.ui_installation_supported_management_unavailable),20,TEXT,true),24);
      add(text(managementUnavailable()),12);return;
    }
    LinearLayout card=box();
    Button choice=button(cardLabel()+s(R.string.ui_choose),false);
    choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    choice.setTextSize(18);choice.setTextColor(TEXT);
    choice.setBackgroundColor(Color.TRANSPARENT);
    choice.setOnClickListener(v->{if(backend!=null)chooseCard();else primaryConnect();});
    taskControls.add(choice);card.addView(choice,new LinearLayout.LayoutParams(-1,-2));
    card.addView(text(s(R.string.ui_eid)+inventory.optString("eidMasked",s(R.string.ui_not_checked)),12,MUTED,false),margin(4));
    card.addView(text(inventory.has("profiles")?s(R.string.ui_card_connected_read_verified):s(R.string.ui_connect_to_check_this_card),12,MUTED,false),margin(14));
    add(card,18);
    LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.addView(text(s(R.string.ui_your_esims),22,TEXT,false),new LinearLayout.LayoutParams(0,-2,1));
    Button refresh=action(s(R.string.ui_refresh),false,this::refresh);refresh.setTextSize(13);refresh.setBackgroundColor(Color.TRANSPARENT);
    heading.addView(refresh,new LinearLayout.LayoutParams(dp(88),dp(48)));add(heading,22);
    JSONArray profiles=inventory.optJSONArray("profiles");
    if(profiles==null){add(text(backend==null?s(R.string.ui_your_esims_will_appear_here):s(R.string.ui_choose_a_card_or_check_compatibility_in_settings)),16);add(action(backend==null?primaryConnectionLabel():s(R.string.ui_choose_card),true,backend==null?this::primaryConnect:this::chooseCard),20);return;}
    for(int i=0;i<profiles.length();i++){
      final int index=i;JSONObject profile=profiles.optJSONObject(i);if(profile==null)continue;
      LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(96));row.setPadding(0,dp(16),0,dp(16));
      TextView avatar=text(profileName(profile).substring(0,Math.min(2,profileName(profile).length())),16,ON_TONAL,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(rounded(TONAL,14));row.addView(avatar,new LinearLayout.LayoutParams(dp(44),dp(44)));
      LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(14),0,dp(8),0);
      labels.addView(text(profileName(profile),16,TEXT,true));labels.addView(text(profile.optString("iccidMasked",s(R.string.ui_not_reported)),12,MUTED,false),margin(4));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
      boolean on="enabled".equalsIgnoreCase(profile.optString("state"));TextView state=text(on?s(R.string.ui_enabled):"disabled".equalsIgnoreCase(profile.optString("state"))?s(R.string.ui_disabled):s(R.string.ui_unknown),11,on?ACCENT:MUTED,false);state.setPadding(dp(8),dp(4),dp(8),dp(4));state.setBackground(rounded(SURFACE,8));row.addView(state);
      row.setContentDescription(s(R.string.profile_accessibility,profileName(profile),state.getText()));row.setFocusable(true);ripple(row);row.setOnClickListener(v->{if(!OPERATIONS.running())showProfile(index,profile,row);});taskControls.add(row);add(row,0);addDivider();
    }
    if(profiles.length()==0)add(text(s(R.string.ui_no_profiles_yet_add_an_esim_to_get_started)),20);
    add(text(s(R.string.ui_enabled_describes_the_card_state_network_service_has_not_been_ch),12,MUTED,false),20);

  }
  private String cardLabel(){String reader=inventory.optString("reader","");String description=inventory.optString("description","");return description.isEmpty()?(reader.isEmpty()?s(R.string.ui_select_an_euicc):reader+s(R.string.ui_euicc)):readerDescription(reader,description);}
  private void selectDetail(int index,JSONObject profile){selectedProfile=index;selectedIccid=profile.optString("iccidMasked","");selectedProfileKey=profile.optString("selectionId","");detailProfile=profile;detailSelection.open(inventory,backend,profile,index);screen=3;navigationSession++;render();}
  private boolean detailCurrent(){JSONArray rows=inventory.optJSONArray("profiles");return backend!=null&&resumed&&!destroyed&&!OPERATIONS.running()&&rows!=null&&selectedProfile>=0&&rows.optJSONObject(selectedProfile)==detailProfile&&detailSelection.matches(inventory,backend,detailProfile,selectedProfile)&&!getPreferences(0).getBoolean("pending",false)&&!selectedIccid.isEmpty()&&!selectedProfileKey.isEmpty();}
  private void profileDetails(){
    JSONObject p=detailProfile;if(p==null){add(text(s(R.string.ui_refresh_the_card_and_select_a_profile)),24);return;}
    add(text(cardLabel()+s(R.string.ui_eid_156)+inventory.optString("eidMasked",s(R.string.ui_not_reported)),12,MUTED,false),16);
    add(text(profileName(p),28,TEXT,false),28);add(text(selectedIccid),6);
    boolean enabled="enabled".equalsIgnoreCase(p.optString("state"));boolean disabled="disabled".equalsIgnoreCase(p.optString("state"));
    Switch toggle=new Switch(this);toggle.setText(s(R.string.ui_use_this_esim));toggle.setTextSize(20);toggle.setTextColor(ON_TONAL);toggle.setPadding(dp(20),dp(18),dp(20),dp(18));toggle.setMinHeight(dp(80));toggle.setBackground(rounded(TONAL,24));toggle.setChecked(enabled);toggle.setContentDescription(s(R.string.ui_use_this_esim));
    toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(darkTheme?0xffffdbcC:Color.WHITE));toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,MUTED}));
    boolean canEdit=backend!=null&&inventory.optBoolean("writeQualified",false)&&!getPreferences(0).getBoolean("pending",false);
    availability(toggle,canEdit&&((enabled&&p.optBoolean("canDisable",false))||(disabled&&p.optBoolean("canEnable",false))));taskControls.add(toggle);
    toggle.setOnClickListener(button->{toggle.setChecked(enabled);if(detailCurrent())prepare(enabled?"disable":"switch",selectedProfile,"");});add(toggle,28);
    add(text(enabled?s(R.string.ui_enabled_on_this_card):disabled?s(R.string.ui_off_turn_on_to_use_this_esim):s(R.string.ui_state_unknown_refresh_before_making_changes)),14);
    String active="";JSONArray rows=inventory.optJSONArray("profiles");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject other=rows.optJSONObject(i);if(other!=null&&"enabled".equalsIgnoreCase(other.optString("state")))active=profileName(other);}
    add(text(enabled?s(R.string.ui_turning_this_off_leaves_no_esim_enabled_on_this_card):active.isEmpty()?s(R.string.ui_turning_this_on_enables_this_esim_on_this_card):s(R.string.profile_switch_replaces,active),13,MUTED,false),6);
    Button rename=action(s(R.string.ui_rename),false,()->{if(detailCurrent()){screen=4;navigationSession++;render();}});availability(rename,canEdit&&p.optBoolean("canRename",false));add(rename,28);
    Button delete=action(s(R.string.ui_delete_esim_166),false,()->{if(detailCurrent())prepare("delete",selectedProfile,"");});delete.setTextColor(ERROR);availability(delete,canEdit&&disabled&&p.optBoolean("canDelete",false));add(delete,12);
    if(enabled)add(text(s(R.string.ui_turn_off_this_esim_before_deleting_it),12,MUTED,false),4);
    if(!canEdit)add(text(s(R.string.ui_changes_are_unavailable_until_this_card_and_its_recovery_state_a),13,MUTED,false),12);
    add(text(s(R.string.ui_about_this_esim),16,TEXT,true),28);detailFact(s(R.string.ui_operator),p.optString("provider",p.optString("name",s(R.string.ui_not_reported))));detailFact("ICCID",selectedIccid);detailFact(s(R.string.ui_card),cardLabel());
    add(text(s(R.string.ui_card_state_only_network_service_has_not_been_checked),12,MUTED,false),20);
  }
  private void detailFact(String label,String value){LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(14),0,dp(14));row.addView(text(label,13,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));TextView v=text(mask(value),13,TEXT,false);v.setGravity(Gravity.END);row.addView(v,new LinearLayout.LayoutParams(0,-2,2));add(row,0);addDivider();}
  private void renameScreen(){if(detailProfile==null)return;add(text(cardLabel()+s(R.string.ui_eid_156)+inventory.optString("eidMasked",s(R.string.ui_not_reported)),12,MUTED,false),18);add(text(profileName(detailProfile)+" · "+selectedIccid),22);EditText name=input(s(R.string.ui_nickname),false);name.setText(detailProfile.optString("nickname",""));add(name,22);add(text(s(R.string.ui_leave_blank_to_use_the_operator_name),12,MUTED,false),10);add(action(s(R.string.ui_save),true,()->{if(!detailCurrent())return;String value=name.getText().toString().trim();if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>64||value.matches("(?s).*[\\p{Cntrl}].*")){name.setError(s(R.string.ui_use_at_most_64_utf_8_bytes_without_control_characters));return;}hideKeyboard();prepare("rename",selectedProfile,value);}),28);add(action(s(R.string.ui_cancel),false,()->{screen=3;navigationSession++;render();}),8);}
  private void recordsScreen(){add(text(s(R.string.ui_operation_results),22,TEXT,false),24);add(text(s(R.string.ui_this_session_s_results_are_shown_here_durable_safety_records_rem)),12);for(String value:recentResults)add(text(value,16,TEXT,false),18);if(recentResults.isEmpty())add(text(s(R.string.ui_no_operations_in_this_app_session)),24);add(action(s(R.string.ui_verify_card_and_recovery),false,this::reconcile),24);if(recoveryReviewEligible)add(action(s(R.string.recovery_review_button),false,this::reviewUnknownLifecycle),12);add(action(s(R.string.ui_export_safe_diagnostics),false,this::exportDiagnostics),12); }
  private void connectionScreen(){
    add(text(s(R.string.ui_connect_with_adb),28,TEXT,false),28);
    add(text(s(R.string.ui_one_command_no_extra_script_shizuku_or_root)),12);
    connectionCountdown=text(countdown(connectionDeadline-android.os.SystemClock.elapsedRealtime()),14,connectionExpired?ERROR:ACCENT,true);
    add(connectionCountdown,20);
    add(text(s(R.string.ui_1_connect_this_phone),18,TEXT,true),24);
    add(text(s(R.string.ui_enable_usb_debugging_and_authorize_your_trusted_computer_keep_8b)),10);
    add(text(s(R.string.ui_2_run_this_on_your_computer),18,TEXT,true),24);
    TextView command=text(connectionCommand,12,TEXT,false);
    command.setTypeface(android.graphics.Typeface.MONOSPACE);command.setTextIsSelectable(true);
    command.setPadding(dp(16),dp(16),dp(16),dp(16));command.setBackground(rounded(SURFACE,12));add(command,14);
    Button copy=action(s(R.string.ui_copy_adb_command),true,()->{
      android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(ClipData.newPlainText(s(R.string.adb_clipboard_label),connectionCommand));
      statusPreservingOutcome(s(R.string.ui_command_copied),s(R.string.ui_run_it_while_the_timer_is_active_your_computer_will_confirm_the));
    });availability(copy,!connectionExpired);add(copy,18);
    add(action(s(R.string.ui_start_connection_again),connectionExpired,this::beginDirectBootstrap),8);
    add(action(connectionExpired || CompatibilityDiagnostics.lastFailure()!=null
        ? s(R.string.ui_feedback_0) : s(R.string.ui_feedback_1),
        false, () -> {
          if (CompatibilityDiagnostics.lastFailure()==null)
            compatibilityFailure(CompatibilityDiagnostics.Stage.BOOTSTRAP,
                CompatibilityDiagnostics.Failure.BOOTSTRAP_INCOMPLETE);
          offerCompatibilityReport();
        }),12);
    add(text(s(R.string.ui_the_command_stays_the_same_after_app_updates_with_several_phones),12,MUTED,false),16);
    add(text(s(R.string.ui_3_wait_for_connection_confirmation),18,TEXT,true),24);
    add(text(s(R.string.ui_your_computer_must_say_8bercue_connected_or_already_connected_th)),10);
    add(text(s(R.string.ui_no_profiles_are_changed_by_connecting_if_the_timer_expires_tap_s),12,MUTED,false),20);
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
      boolean dismiss = label.equals(s(R.string.ui_done)) || label.equals(s(R.string.ui_cancel));
      if (!dismiss) {
        sheetControls.add(b);b.setEnabled(!OPERATIONS.running());b.setAlpha(OPERATIONS.running() ? .45f : 1f);
      }
      if (label.equals(s(R.string.ui_delete_permanently)) || label.startsWith(s(R.string.ui_delete))) {
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
    add(text(cardLabel()+s(R.string.ui_eid_156)+inventory.optString("eidMasked",s(R.string.ui_not_checked)),12,MUTED,false),18);
    if (backend == null) {
      add(text(s(R.string.ui_connect_your_card_to_review_and_install_you_can_enter_or_scan_yo), 14, MUTED, false), 18);
      add(action(s(R.string.ui_open_connection_settings), false, () -> navigate(2)), 10);
    }
    if (!draft.form) {
      add(text(s(R.string.ui_how_would_you_like_to_add_your_esim), 16, MUTED, false), 22);
      add(action(s(R.string.ui_scan_a_qr_code), true, () -> startActivityForResult(new Intent(this, QrScanActivity.class), QR)), 24);
      add(action(s(R.string.ui_choose_a_qr_image), false, () -> {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i, IMAGE);
      }), 12);
      add(action(s(R.string.ui_enter_an_activation_code), false, () -> { draft.form = true;draft.changed();render(); }), 12);
      add(text(s(R.string.ui_use_the_code_provided_by_your_operator_you_ll_review_the_install), 14, MUTED, false), 24);
      return;
    }
    add(text(s(R.string.ui_activation_code), 16, TEXT, true), 24);
    activation = input("LPA:1$server$matching-id", true);activation.setText(draft.activation);add(activation, 10);
    boolean required = false;
    try { required = ActivationCode.parse(draft.activation).confirmationRequired; } catch (IllegalArgumentException ignored) {}
    confirmation = input(s(R.string.ui_operator_confirmation_code), true);confirmation.setText(draft.confirmation);
    if (showConfirmation || required || !draft.confirmation.isEmpty()) {
      add(text(required ? s(R.string.ui_confirmation_code_required) : s(R.string.ui_confirmation_code), 14, TEXT, true), 18);add(confirmation, 8);
    } else add(action(s(R.string.ui_have_a_confirmation_code), false, () -> { showConfirmation = true;render(); }), 12);
    watchInput(activation);watchInput(confirmation);
    confirmation.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    confirmation.setOnEditorActionListener((v, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE && !OPERATIONS.running()) { reviewDownload();return true; }return false; });
    add(action(s(R.string.ui_review_installation), true, this::reviewDownload), 24);
    add(text(s(R.string.ui_codes_stay_in_memory_and_are_sent_only_to_your_operator_during_i), 13, MUTED, false), 18);
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
    run(s(R.string.ui_reading_available_cards),false,()->new JSONObject(service.prepareCardSelection()),result->{
      if(backend!=service||navigationSession!=choiceSession)return;
      if(!result.optBoolean("ok",false)){
        String explanation=UiMessages.known(MainActivity.this,SecureElementFailure.message(result.optString("capabilityError","")));
        status(s(R.string.ui_card_choice_unavailable),explanation.isEmpty()?s(R.string.ui_reconnect_and_check_recovery_no_card_was_changed):explanation);render();return;
      }
      inventory=new JSONObject();cardInfo=new JSONObject();
      Sheet choices=new Sheet(s(R.string.ui_choose_a_card));
      choices.copy(s(R.string.ui_choose_the_euicc_you_intend_to_manage_this_checks_card_identity));
      JSONArray rows=result.optJSONArray("choices");
      if(rows!=null)for(int i=0;i<rows.length();i++){
        JSONObject choice=rows.optJSONObject(i);if(choice==null)continue;
        String token=choice.optString("token","");
        choices.button(readerDescription(choice.optString("reader",""),choice.optString("description",s(R.string.ui_card)))+s(R.string.ui_eid_156)+choice.optString("eidMasked","")+" · "+choice.optString("reader",""),false,()->{
          choices.dialog.dismiss();if(backend!=service||navigationSession!=choiceSession)return;
          run(s(R.string.ui_verifying_selected_card),false,()->new JSONObject(service.selectCard(token)),selected->{
            if(backend!=service||navigationSession!=choiceSession)return;
            navigationSession++;clearDetailSelection();inventory=new JSONObject();cardInfo=new JSONObject();compatibility=new JSONObject();notificationStatus="";notificationCleanupAvailable=false;
            if(!selected.optBoolean("ok",false)){String explanation=UiMessages.known(MainActivity.this,SecureElementFailure.message(selected.optString("capabilityError","")));status(s(R.string.ui_card_choice_needs_verification),explanation.isEmpty()?s(R.string.ui_the_choice_expired_or_the_card_changed_choose_a_card_again_no_al):explanation);render();return;}
            screen=0;status(s(R.string.ui_card_selected),s(R.string.ui_reading_this_card_and_its_recovery_state));render();checkRecovery();
          });
        });
      }
      choices.copy(s(R.string.ui_the_selection_lasts_for_this_shell_service_session_a_missing_or));
      choices.button(s(R.string.ui_cancel),false,()->{choices.dialog.dismiss();refresh();});choices.show();
    });
  }
  private void showCompatibility() {
    Sheet check=new Sheet(s(R.string.ui_compatibility_check));
    if(compatibility.length()==0)check.copy(s(R.string.ui_connect_the_shell_service_then_refresh_to_check_this_card_no_car));
    else {
      check.fact(s(R.string.ui_selected_card_channel),compatibility.optString("route",s(R.string.ui_no_verified_card_connection)));
      check.copy(UiMessages.compatibility(this,compatibility.optString("details",s(R.string.ui_no_channel_observations_returned))));
      check.copy(compatibility.optBoolean("profileReadVerified",false)
          ? s(R.string.ui_profile_reading_verified_this_read_only_check_does_not_test_down)
          : s(R.string.ui_profile_reading_has_not_been_verified_no_compatibility_check_cha));
    }
    check.copy(s(R.string.ui_order_secure_element_hal_aidl_then_hidl_telephony_then_omapi_mul));
    if(backend!=null)check.button(s(R.string.ui_choose_card),false,()->{check.dialog.dismiss();chooseCard();});
    check.button(s(R.string.ui_run_read_only_check),false,()->{check.dialog.dismiss();if(backend!=null||fallback)refresh();else status(s(R.string.ui_connect_first),s(R.string.ui_use_connect_using_computer_then_run_the_compatibility_check));});
    check.button(s(R.string.ui_done),false,check.dialog::dismiss);check.show();
  }
  private void settings() {
    add(text(s(R.string.ui_connection), 19, TEXT, true), 24);
    add(text(s(R.string.ui_card_connection)+compatibility.optString("route",s(R.string.ui_not_checked_in_this_connection)),14,MUTED,false),8);
    add(action(s(R.string.ui_compatibility_check),false,this::showCompatibility),12);
    if(backend!=null)add(action(s(R.string.ui_choose_card),false,this::chooseCard),12);
    add(text(directManagement && backend != null ? s(R.string.ui_computer_connection_active)
          : companionManagement ? s(R.string.ui_local_companion_connected)
          : binderReadOnlyInventory ? s(R.string.ui_read_only_diagnostic_connection)
          : backend != null ? s(R.string.ui_computer_connection_active)
          : fallback ? s(R.string.ui_read_only_companion_connected) : s(R.string.ui_not_connected), 15, MUTED, false), 8);
    if (LabSupport.socketEligible())
      add(action(s(R.string.ui_use_legacy_local_companion), useCompanion(), this::selectLegacyCompanion), 12);
    add(action(s(R.string.ui_connect_using_computer), backend==null, this::beginDirectBootstrap), 12);
    if (CompatibilityDiagnostics.lastFailure()!=null)
      add(action(s(R.string.ui_feedback_2),false,this::offerCompatibilityReport),12);
    if (BuildConfig.DEBUG) add(action(s(R.string.ui_check_direct_binder_companion_read_only), false, () -> {
      run(s(R.string.ui_checking_local_binder), false, () -> {
        ICompanionProbe probe = CompanionBinderProvider.service();
        if (probe == null) return new JSONObject().put("ok", false).put("status", "not_connected");
        String greeting = probe.getStatus();
        if (!greeting.startsWith("READY: Companion Binder Shell UID=2000"))
          return new JSONObject().put("ok", false).put("status", "identity_unverified");
        // IPC diagnostics must not inadvertently touch the radio or eUICC.
        return new JSONObject().put("ok", true).put("status", "binder_identity_verified");
      }, reply -> status(reply.optBoolean("ok", false)
            ? s(R.string.ui_binder_transport_verified) : s(R.string.ui_binder_transport_unavailable),
          s(R.string.ui_ipc_identity) + reply.optString("status", "unknown")
            + s(R.string.ui_no_card_read_was_performed)));
    }), 12);
    if (BuildConfig.DEBUG) add(action(s(R.string.ui_auto_detect_known_5ber_card_read_only), false, () -> {
      if (binderDetectionRequested) {
        status(s(R.string.ui_card_discovery_already_attempted), s(R.string.ui_do_not_repeat_a_card_probe_automatically));
        return;
      }
      binderDetectionRequested = true;
      run(s(R.string.ui_detecting_5ber_card), false, () -> {
        ICompanionProbe probe = CompanionBinderProvider.service();
        if (probe == null) return new JSONObject().put("ok",false).put("stage","ipc");
        return new JSONObject(probe.detectKnownCard());
      }, result -> status(result.optBoolean("ok",false) ? s(R.string.ui_recognized_5ber_card) : s(R.string.ui_card_not_recognized),
          result.optBoolean("ok",false)
              ? result.optString("knownCard","known") + s(R.string.ui_on) + result.optString("reader","unknown")
                  + s(R.string.ui_discovery_only_writes_remain_restricted)
              : s(R.string.ui_stage) + result.optString("stage","unavailable")));
    }), 12);
    if (BuildConfig.DEBUG) add(action(s(R.string.ui_read_via_direct_binder_read_only), false, () -> {
      if (binderReadRequested) {
        status(s(R.string.ui_binder_read_already_attempted), s(R.string.ui_do_not_repeat_a_failed_or_uncertain_card_read));
        return;
      }
      binderReadRequested = true;
      run(s(R.string.ui_binder_card_read), false, () -> {
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
          status(s(R.string.ui_binder_card_read_complete), s(R.string.ui_recognized) + reply.optString("knownCard","card")
              + s(R.string.ui_on) + reply.optString("reader","unknown")
              + "; " + reply.optInt("count",0) + s(R.string.ui_profiles_read_only_no_editing_permission));
          render();
        } else {
          status(s(R.string.ui_binder_read_unavailable), s(R.string.ui_stage) + reply.optString("stage","unknown"));
        }
      });
    }), 12);
    if (LabSupport.wirelessEligible() && Build.VERSION.SDK_INT >= 30)
      add(action(s(OnDeviceAdbNativeCrypto.available()
              ? R.string.wireless_adb_live_entry : R.string.wireless_adb_preview_entry),
              false, this::showWirelessDiscoveryPreview), 12);
    add(action(s(R.string.ui_setup_help), false, () -> {
      Sheet help = new Sheet(s(R.string.ui_connect_your_card));
      help.copy(s(R.string.ui_connect_this_phone_by_usb_enable_usb_debugging_and_accept_the_co));
      help.copy(s(R.string.ui_after_connection_open_compatibility_check_a_connected_service_al));
      help.copy(s(R.string.ui_reconnect_after_a_phone_restart_app_update_or_service_terminatio));help.button(s(R.string.ui_done), false, help.dialog::dismiss);help.show();
    }), 12);
    add(text(s(R.string.language_title),19,TEXT,true),28);
    add(action(s(R.string.language_current,AppLanguage.label(this)),false,this::chooseLanguage),12);
    add(text(s(R.string.ui_appearance),19,TEXT,true),28);
    add(action(darkTheme?s(R.string.ui_theme_dark):s(R.string.ui_theme_light),false,()->{getPreferences(0).edit().putBoolean("darkTheme",!darkTheme).apply();applyPalette();render();}),12);
    add(text(s(R.string.ui_support),19,TEXT,true),28);
    add(action(s(R.string.ui_operation_records),false,()->{screen=5;navigationSession++;render();}),12);
    add(text(s(R.string.ui_card), 19, TEXT, true), 28);
    add(text(s(R.string.ui_eid_269) + mask(cardInfo.optString("eidMasked", inventory.optString("eidMasked", s(R.string.ui_not_checked))))), 10);
    add(text(s(R.string.ui_profiles) + (inventory.optJSONArray("profiles") == null ? s(R.string.ui_not_checked) : inventory.optJSONArray("profiles").length())), 8);
    add(text(s(R.string.ui_storage) + (cardInfo.length() == 0 ? s(R.string.ui_not_checked) : storage())), 8);
    add(action(cardInfo.length() == 0 ? s(R.string.ui_read_card_information) : s(R.string.ui_refresh_card_information), false, () -> {
      IProfileService service = backend;
      if (service == null) { status(s(R.string.ui_connect_your_card_first), s(R.string.ui_card_information_needs_the_authorized_service));return; }
      run(s(R.string.ui_reading_card_information), false, () -> new JSONObject(service.getCardInfo()), r -> {
        if (!r.optBoolean("ok", false)) { status(s(R.string.ui_card_information_unavailable), s(R.string.ui_reconnect_and_refresh_the_card));return; }
        cardInfo = r;status(s(R.string.ui_card_information_checked), s(R.string.ui_only_details_reported_by_your_card_are_shown));render();
      });
    }), 16);
    if (cardInfo.length() > 0) add(action(s(R.string.ui_capability_details), false, () -> {
      Sheet info = new Sheet(s(R.string.ui_card_capabilities));
      info.copy(cardInfo.optBoolean("capabilitiesSupported", false) ? capabilitySummary() : s(R.string.ui_optional_capabilities_were_not_reported_by_this_reader));
      info.button(s(R.string.ui_done), false, info.dialog::dismiss);info.show();
    }), 10);
    if (!notificationStatus.isEmpty()) {
      add(text(s(R.string.ui_operator_notification), 19, TEXT, true), 24);
      add(text(UiMessages.known(MainActivity.this,NotificationPresentation.message(notificationStatus)), 14, MUTED, false), 8);
      if (notificationCleanupAvailable) add(action(s(R.string.ui_review_notification_cleanup), false, this::prepareNotificationCleanup), 12);
    }
    if (getPreferences(0).getBoolean("pending", false)) add(action(s(R.string.ui_verify_card_and_recovery), true, this::reconcile), 24);
    if(recoveryReviewEligible&&getPreferences(0).getBoolean("pending",false))
      add(action(s(R.string.recovery_review_button),false,this::reviewUnknownLifecycle),12);
    add(action(s(R.string.ui_recovery_advanced), false, () -> {
      Sheet advanced = new Sheet(s(R.string.ui_recovery_advanced_285));
      advanced.copy(s(R.string.ui_an_interrupted_operation_may_have_an_unknown_outcome_reconnect_a));
      advanced.button(s(R.string.ui_verify_card_and_recovery), false, () -> { advanced.dialog.dismiss();reconcile(); });
      if(recoveryReviewEligible)advanced.button(s(R.string.recovery_review_button),false,()->{advanced.dialog.dismiss();reviewUnknownLifecycle();});
      if (LabSupport.socketEligible()) advanced.button(s(R.string.ui_recover_local_companion), false, () -> { advanced.dialog.dismiss();recoverFallback(); });
      advanced.button(s(R.string.ui_export_safe_diagnostics), false, this::exportDiagnostics);
      advanced.copy(s(R.string.ui_diagnostics_exclude_activation_codes_profile_names_identifiers_a));
      advanced.button(s(R.string.ui_done), false, advanced.dialog::dismiss);advanced.show();
    }), 24);
    add(action(s(R.string.ui_feedback_3),false,this::reportProblem),16);
    add(action(s(R.string.ui_feedback_4),false,this::saveDiagnosticZip),12);
    add(action(s(R.string.ui_feedback_5) + (SafeTelemetry.allowed(this) ? s(R.string.ui_feedback_6) : s(R.string.ui_feedback_7)) + "  ›",
        false, this::chooseCrashReporting), 12);
    if (BuildConfig.DEBUG) add(action(s(R.string.ui_feedback_8),false,
        () -> status(SafeTelemetry.smoke(this) ? s(R.string.ui_feedback_9) : s(R.string.ui_feedback_10),
            s(R.string.ui_feedback_11))), 12);
    add(action(s(R.string.ui_privacy_information), false, () -> showBundledText(s(R.string.ui_privacy_information), "legal/privacy.txt")), 20);
    add(action(s(R.string.ui_third_party_notices), false, () -> showBundledText(s(R.string.ui_third_party_notices), "legal/third-party-notices.txt")), 12);
    add(text(s(R.string.ui_independent_project_not_an_official_5ber_product), 12, MUTED, false), 20);
    add(text("8bercue " + BuildConfig.VERSION_NAME, 12, MUTED, false), 12);
  }
  private void showBundledText(String title, String asset) {
    if(AppLanguage.isChinese(this)&&asset.equals("legal/privacy.txt"))asset="legal/privacy-zh-Hans.txt";
    try (java.io.InputStream input = getAssets().open(asset)) {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];int count;
      while ((count = input.read(buffer)) != -1) {
        if (bytes.size() + count > 262144) throw new java.io.IOException(s(R.string.ui_document_too_large));
        bytes.write(buffer, 0, count);
      }
      Sheet document = new Sheet(title);
      if(asset.equals("legal/third-party-notices.txt"))document.copy(s(R.string.privacy_original));
      document.copy(bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
      document.button(s(R.string.ui_done), false, document.dialog::dismiss);document.show();
    } catch (java.io.IOException unavailable) { status(s(R.string.ui_document_unavailable), s(R.string.ui_reinstall_the_verified_application_package)); }
  }
  private void prepareNotificationCleanup() {
    final IProfileService service = backend;
    if (service == null) { connect();return; }
    final long session = navigationSession;
    run(s(R.string.ui_checking_notification_cleanup), false, () -> new JSONObject(service.prepareNotificationCleanup()), r -> {
      if (backend != service || navigationSession != session) return;
      String token = r.optString("token", "");
      if (!r.optBoolean("ok", false) || token.isEmpty()) {
        status(s(R.string.ui_cleanup_unavailable), s(R.string.ui_reconnect_and_check_notification_status_no_message_was_resent));return;
      }
      Sheet sheet = new Sheet(s(R.string.ui_clean_up_acknowledged_notification));
      sheet.copy(s(R.string.ui_the_operator_already_acknowledged_this_notification_this_removes));
      sheet.button(s(R.string.ui_clean_up), true, () -> {
        sheet.dialog.dismiss();
        if (backend != service) { status(s(R.string.ui_connection_changed), s(R.string.ui_reconnect_and_review_cleanup_again));return; }
        run(s(R.string.ui_cleaning_up_notification), true, () -> new JSONObject(service.executeNotificationCleanup(token)), result -> {
          notificationStatus = result.optString("notificationStatus", "unavailable");
          notificationCleanupAvailable = result.optBoolean("notificationCleanupAvailable", false);
          notice = UiMessages.known(MainActivity.this,NotificationPresentation.message(notificationStatus));
          if (!result.optBoolean("notificationWorkerStopped", true))
            notice += s(R.string.ui_reconnect_the_local_service_using_your_computer_and_verify_the_c);
          checkRecovery();
        });
      });
      sheet.button(s(R.string.ui_cancel), false, sheet.dialog::dismiss);sheet.show();
    });
  }
  private TextView text(String value) { return text(value, 14, MUTED, false); }
  private void recoverFallback() {
    Sheet recovery = new Sheet(s(R.string.ui_authorize_local_adb_recovery));
    recovery.copy(s(R.string.ui_this_requests_full_adb_shell_debugging_authority_not_just_esim_a));
    recovery.button(s(R.string.ui_continue), true, () -> {
      recovery.dialog.dismiss();run(s(R.string.ui_recovering_local_companion), false, () -> {
        LocalAdbBootstrap.Result r = LocalAdbBootstrap.start(getApplicationContext());
        return new JSONObject().put("ready", r.ready).put("authorization", r.needsAuthorization).put("description", r.description);
      }, r -> { if (r.optBoolean("ready")) {
        connectDirect();
        if (backend == null) status(s(R.string.ui_recovery_not_complete), s(R.string.wireless_adb_attach_pending));
      } else status(s(R.string.ui_recovery_not_complete), r.optString("description", s(R.string.ui_check_the_supported_adb_setup_and_reconnect))); });
    });
    recovery.button(s(R.string.ui_cancel), false, recovery.dialog::dismiss);recovery.show();
  }
  private void launchUserApprovedPairingFlow() {
    try {
      startForegroundService(OnDeviceAdbPairingService.startIntent(this));
      startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    } catch (Exception unavailable) {
      status(s(R.string.ui_check_unavailable), s(R.string.wireless_adb_start_unavailable));
    }
  }

  @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
    super.onRequestPermissionsResult(requestCode,permissions,grants);
    if(requestCode==7312) {
      if(grants.length>0 && grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)
        launchUserApprovedPairingFlow();
      else status(s(R.string.ui_check_unavailable),
              s(R.string.wireless_adb_notifications_required));
    }
  }

  /** Preview only: discover local TLS ports without pairing or touching adbd. */
  private void showWirelessDiscoveryPreview() {
    if (Build.VERSION.SDK_INT < 30 || !LabSupport.wirelessEligible()) return;
    Sheet sheet = new Sheet(s(OnDeviceAdbNativeCrypto.available()
            ? R.string.wireless_adb_live_title : R.string.wireless_adb_preview_title));
    boolean realPairing=Build.VERSION.SDK_INT>=31 && OnDeviceAdbNativeCrypto.available();
    sheet.copy(s(realPairing ? R.string.wireless_adb_live_help : R.string.wireless_adb_preview_help));
    final TextView pairing = text(s(R.string.wireless_adb_preview_searching), 14, MUTED, false);
    final TextView connecting = text(s(R.string.wireless_adb_preview_searching), 14, MUTED, false);
    sheet.body.addView(pairing, margin(14));
    sheet.body.addView(connecting, margin(6));
    sheet.copy(s(R.string.wireless_adb_preview_not_auth));
    final java.util.ArrayList<OnDeviceAdbDiscovery> searches = new java.util.ArrayList<>();
    OnDeviceAdbDiscovery.Observer observer = new OnDeviceAdbDiscovery.Observer() {
      @Override public void found(OnDeviceAdbDiscovery.Endpoint e) {
        handler.post(() -> {
          if (!sheet.dialog.isShowing() || destroyed) return;
          TextView label = e.purpose == OnDeviceAdbDiscovery.Purpose.PAIRING ? pairing : connecting;
          label.setText(s(e.purpose == OnDeviceAdbDiscovery.Purpose.PAIRING
              ? R.string.wireless_adb_preview_pair : R.string.wireless_adb_preview_connect, e.port));
        });
      }
      @Override public void lost(OnDeviceAdbDiscovery.Purpose purpose) {
        handler.post(() -> {
          if (sheet.dialog.isShowing() && !destroyed)
            (purpose == OnDeviceAdbDiscovery.Purpose.PAIRING ? pairing : connecting)
                .setText(s(R.string.wireless_adb_preview_lost));
        });
      }
      @Override public void failed(OnDeviceAdbDiscovery.Purpose purpose) {
        handler.post(() -> {
          if (sheet.dialog.isShowing() && !destroyed)
            (purpose == OnDeviceAdbDiscovery.Purpose.PAIRING ? pairing : connecting)
                .setText(s(R.string.wireless_adb_preview_error));
        });
      }
    };
    if (realPairing) {
      sheet.button(s(R.string.wireless_adb_identity_prepare), false, () -> {
        sheet.dialog.dismiss();
        run(s(R.string.wireless_adb_identity_prepare), false, () -> {
          OnDeviceAdbIdentity identity=OnDeviceAdbIdentity.getOrCreate(this);
          if(identity.pairingPublicKey().length < 600)
            throw new java.security.GeneralSecurityException("Invalid ADB public-key format");
          return new JSONObject().put("ready",true);
        }, result -> status(s(R.string.wireless_adb_identity_ready),s(R.string.ui_no_changes_were_made)));
      });
      sheet.button(s(R.string.wireless_adb_exporter_verify), false, () -> {
        sheet.dialog.dismiss();
        run(s(R.string.wireless_adb_exporter_checking), false, () -> {
          try {
            OnDeviceAdbIdentity identity=OnDeviceAdbIdentity.getOrCreate(this);
            return new JSONObject().put("ok",OnDeviceAdbTlsContext.syntheticTlsSelfTest(identity));
          } catch (Exception problem) {
            if (BuildConfig.DEBUG)
              android.util.Log.w("EightberTlsSmoke","Synthetic TLS exporter failed",problem);
            return new JSONObject().put("ok",false)
                    .put("stage",problem.getClass().getSimpleName());
          }
        }, reply -> status(reply.optBoolean("ok")
                ? s(R.string.wireless_adb_exporter_verified) : s(R.string.wireless_adb_exporter_unavailable),
                reply.optBoolean("ok") ? s(R.string.wireless_adb_exporter_synthetic)
                    : s(R.string.wireless_adb_failure_stage,reply.optString("stage","Unknown"))));
      });
      sheet.button(s(R.string.wireless_adb_pairing_start), true, () -> {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          sheet.dialog.dismiss();
          requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7312);
          return;
        }
        sheet.dialog.dismiss();
        launchUserApprovedPairingFlow();
      });
    }
    sheet.button(s(R.string.wireless_adb_preview_scan), true, () -> {
      for (OnDeviceAdbDiscovery search : searches) search.close();
      searches.clear();
      pairing.setText(s(R.string.wireless_adb_preview_searching));
      connecting.setText(s(R.string.wireless_adb_preview_searching));
      for (OnDeviceAdbDiscovery.Purpose purpose : OnDeviceAdbDiscovery.Purpose.values()) {
        OnDeviceAdbDiscovery discovery = new OnDeviceAdbDiscovery(this, purpose, observer);
        searches.add(discovery);
        discovery.start();
      }
    });
    sheet.button(s(R.string.wireless_adb_preview_settings), false, () -> {
      sheet.dialog.dismiss();
      Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
      try { startActivity(intent); }
      catch (android.content.ActivityNotFoundException unavailable) {
        status(s(R.string.ui_check_unavailable), s(R.string.wireless_adb_preview_error));
      }
    });
    sheet.button(s(R.string.ui_done), false, sheet.dialog::dismiss);
    sheet.dialog.setOnDismissListener(ignored -> {
      for (OnDeviceAdbDiscovery search : searches) search.close();
      searches.clear();
    });
    sheet.show();
  }

  private String capabilitySummary() {
    StringBuilder result = new StringBuilder();
    String[][] fields = {{"profileVersion", s(R.string.ui_profile_spec)}, {"gsmaVersion", s(R.string.ui_gsma_spec)},
        {"firmwareVersion", s(R.string.ui_firmware)}};
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
              ? s(R.string.ui_additional_profile_supported)
              : s(R.string.ui_additional_profile_not_supported));
    }
    return result.length() == 0 ? s(R.string.ui_no_optional_details_reported) : result.toString();
  }
  private String storage() {
    long bytes = cardInfo.optLong(
        "freeNonvolatileMemoryBytes", cardInfo.optLong("freeNonvolatileBytes", -1));
    return bytes >= 0 ? (bytes / 1024) + s(R.string.ui_kb_available) : s(R.string.ui_not_reported);
  }
  private void compatibilityFailure(CompatibilityDiagnostics.Stage stage,
      CompatibilityDiagnostics.Failure reason) {
    CompatibilityDiagnostics.failed(CompatibilityDiagnostics.Transport.ADB_COMPANION,stage,reason);
    SafeDiagnosticBundle.mark(SafeDiagnosticBundle.Step.CONNECTION_UNAVAILABLE);
  }

  private void chooseCrashReporting() {
    if (!SafeTelemetry.configured(this)) {status(s(R.string.ui_feedback_30),s(R.string.ui_feedback_31));return;}
    boolean enabled = SafeTelemetry.allowed(this);
    new AlertDialog.Builder(this)
        .setTitle(s(R.string.ui_feedback_12))
        .setMessage(s(R.string.ui_feedback_13))
        .setNegativeButton(s(R.string.ui_feedback_14), null)
        .setPositiveButton(enabled ? s(R.string.ui_feedback_15) : s(R.string.ui_feedback_16), (dialog, which) -> {
          SafeTelemetry.setAllowed(this, !enabled);
          render();
        }).show();
  }

  private void reportProblem() { reportProblem(null); }

  private void reportProblem(CompatibilityDiagnostics.Snapshot context) {
    SafeDiagnosticBundle.mark(SafeDiagnosticBundle.Step.REPORT_OPENED);
    final EditText message = new EditText(this);
    message.setHint(s(R.string.ui_feedback_17));
    message.setMinLines(3);
    message.setMaxLines(7);
    message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    new AlertDialog.Builder(this).setTitle(s(R.string.ui_feedback_18))
        .setMessage(s(R.string.ui_feedback_19))
        .setView(message).setNegativeButton(s(R.string.ui_feedback_14), null)
        .setPositiveButton(s(R.string.ui_feedback_20), (dialog, ignored) -> {
          String body = message.getText().toString().trim();
          if (body.length() == 0) {
            status(s(R.string.ui_feedback_21), s(R.string.ui_feedback_22));return;
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
            startActivity(Intent.createChooser(share, s(R.string.ui_feedback_23)));
          }
        }).show();
  }
  private void offerCompatibilityReport() {
    final CompatibilityDiagnostics.Snapshot result=CompatibilityDiagnostics.lastFailure();
    if (result == null) {
      reportProblem();
      return;
    }
    new AlertDialog.Builder(this).setTitle(s(R.string.ui_feedback_24))
        .setMessage(s(R.string.ui_feedback_25)
            + result.summary()
            + s(R.string.ui_feedback_26))
        .setNeutralButton(s(R.string.ui_feedback_27), (d,w) -> reportProblem(result))
        .setNegativeButton(s(R.string.ui_feedback_28), null)
        .setPositiveButton(s(R.string.ui_feedback_29), (d,w) -> {
          if (!SafeTelemetry.configured(this)) {
            status(s(R.string.ui_feedback_30), s(R.string.ui_feedback_31));
            return;
          }
          DIAGNOSTIC_WORK.execute(() -> {
            boolean queued=SafeTelemetry.submitCompatibility(getApplicationContext(),result);
            runOnUiThread(() -> {
              if (destroyed) return;
              status(queued ? s(R.string.ui_feedback_32) : s(R.string.ui_feedback_33),
                  queued ? s(R.string.ui_feedback_34)
                      : s(R.string.ui_feedback_35));
            });
          });
        }).show();
  }

  private void saveDiagnosticZip() {
    SafeDiagnosticBundle.mark(SafeDiagnosticBundle.Step.ZIP_REQUESTED);
    new AlertDialog.Builder(this).setTitle(s(R.string.ui_feedback_36))
        .setMessage(s(R.string.ui_feedback_37))
        .setNegativeButton(s(R.string.ui_feedback_14), null)
        .setPositiveButton(s(R.string.ui_feedback_38), (dialog, which) -> {
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
      status(s(R.string.ui_feedback_39), s(R.string.ui_feedback_40));
    } catch (java.io.IOException error) {
      status(s(R.string.ui_feedback_41), s(R.string.ui_feedback_42));
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
    startActivity(Intent.createChooser(send, s(R.string.ui_export_safe_diagnostics)));
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
    run(s(R.string.ui_reading_qr_image), false, () -> {
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
      status(s(R.string.ui_no_qr_code_found), s(R.string.ui_choose_a_clear_qr_image_or_enter_the_activation_code_manually));
      return;
    }
    draft.acceptCode(code);
    confirmation = null;
    draft.changed();
    screen = 1;
    activation = null;
    render();
    activation.setText(code);
    status(s(R.string.ui_qr_code_read), s(R.string.ui_review_the_code_then_validate_it_before_installation));
  }
  private String safeState(String s) {
    return s.matches("[A-Za-z_ -]{1,60}") ? s : s(R.string.ui_requires_verification);
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
    statusBox.setVisibility(UiStatusPolicy.visible(running,pending,!notice.isEmpty(),backend!=null,canonicalTitle())?View.VISIBLE:View.GONE);
    statusBox.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    statusTitle.setText(running ? OPERATIONS.title() : !notice.isEmpty() ? pending ? s(R.string.ui_needs_verification) : s(R.string.ui_update) : title);
    statusDetail.setText(running ? detail : !notice.isEmpty() ? notice : detail + (pending ? s(R.string.ui_safety_verification_is_required_use_settings_verify_card_and_rec) : ""));
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
        status(OPERATIONS.title(), s(R.string.ui_please_wait_for_the_current_operation_to_finish));
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
  private String s(int id) { return getResources().getString(id); }
  private String s(int id, Object... args) { return getResources().getString(id, args); }
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