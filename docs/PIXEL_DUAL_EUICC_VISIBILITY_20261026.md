# Pixel 10 Pro XL: built-in eSIM disappears with a removable eUICC

Status: confirmed on one Pixel 10 Pro XL (Android 17, `CP2A.260805.005`); a no-reboot metadata-refresh pilot succeeded. This is a platform workaround, not a fix to AOSP subscription synchronization.

## Evidence (2026-10-26; sensitive IDs omitted)

- Built-in eUICC: Android public card ID `0`, `isRemovable=false`, five readable profiles (Skylo, giffgaff, YES, povo, Club).
- Removable GD eUICC: Android public card ID `12`, `isRemovable=true`, zero profiles reported by Android's eSIM service; active test SIM `GSMA_TEST` remained on logical slot 0.
- `SubscriptionManagerService` refreshed internal card ID 0 and marked all five subscriptions `isEmbedded=1`; then refreshed removable card ID 12 and marked those same five records `isEmbedded=0`. This repeated at 23:03 and 23:11. `Embedded subscriptions: []` matched the missing Android Settings entries.
- A standalone Android `app_process` process running as ADB Shell UID 2000 verified the `ISub.requestEmbeddedSubscriptionInfoListRefresh(int)` Binder API. At 23:45:44 it requested **only card ID 0**; logs then showed five `isEmbedded=1` updates and no external card refresh. `dumpsys isub` subsequently showed `Embedded subscriptions: [1, 2, 4, 5, 12]`, remaining true on a later recheck. No reboot, Profile mutation, or card removal occurred.
- Separate read-only runtime preflight using `ITelephony.getUiccCardsInfo("com.android.shell")` correctly discovered one nonremovable eUICC and card ID `0` without hardcoding the ID.
- **Not yet verified:** direct observation of Android Settings UI after the repair; installation and end-to-end testing of a newly built 8bercue APK; sustained persistence if the system refreshes the removable card again.

## Candidate platform root cause

Android's `SubscriptionManagerService.updateEmbeddedSubscriptions(cardIds)` marks embedded subscriptions missing from *the current request's card list* as non-embedded. Independent successful updates for two different eUICCs can therefore overwrite each other's subscription visibility. See [AOSP implementation](https://android.googlesource.com/platform/frameworks/opt/telephony/+/android16-release/src/java/com/android/internal/telephony/subscription/SubscriptionManagerService.java).

## 8bercue mitigation

- Advanced settings → **Repair built-in eSIM visibility**, only while an authorized Shell Companion service is connected.
- Shell service verifies its UID and derives the target card ID from the Telephony hardware inventory, requiring exactly one `isEuicc() && !isRemovable()` card and a valid public card ID; untrusted UI supplies no ID, AIDL command, APDU or shell text.
- Preflight then explicit consent. The privileged Shell process requests one asynchronous subscription **metadata** refresh through `ISub`; no eUICC profile download, delete, enable, disable, switching or radio slot remapping.
- UI reports only that a refresh was requested, and asks the user to check Android Settings; this API does not provide a completion signal to the caller.
- Caveat: AOSP may hide *removable* eUICC subscriptions when built-in metadata is refreshed. A later removable-card refresh can hide built-in subscriptions again. This is user-facing and explicitly acknowledged before proceeding.
- On unsupported ROMs or without Shell permissions, fail closed; do not fall back to root, general command execution or direct subscription database edits.

## Acceptance boundaries

1. Offline localization, UI, and guardrail tests pass.
2. Android release APK builds on CI, without new signer keys or production release.
3. Before merging, test the upgraded App's UI/RPC on a real device and recheck `dumpsys isub` plus the Settings screen. No destructive profile operations.
4. Do not call the mitigation a durable dual-eUICC fix; upstream Android requires card-scoped reconciliation for that.
