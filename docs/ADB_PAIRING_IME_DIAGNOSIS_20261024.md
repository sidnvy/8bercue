# Pixel 10 Pro XL: keyboard does not appear in notification pairing (2026-10-24)

## Verified real-device observations

- Google Pixel 10 Pro XL running Android 17, build **CP2A.260805.005**, security patch **2026-08-05**. Gboard is the selected IME. No live application-overlay windows were detected by WindowManager.
- The original Android SystemUI notification `RemoteInput` (equivalent to Shizuku's `PendingIntent.getForegroundService` + mutable reply PendingIntent + `RemoteInput.Builder`) showed a focused inline input box but did not visually show an on-screen keyboard.
- A separate ordinary `EditText` in an app-owned Android Dialog showed the same missing-keyboard effect. An independent **Android Settings search field** also showed the same effect. Gboard `dumpsys input_method` showed **`mInputShown=true`**, a valid focused `mServedView`, and an InputMethod window reported visible with a surface. However, screenshots showed no full keyboard; the UI showed only a code Autofill chip in one case. IME WindowManager inset reports near-zero effective keyboard content.
- A bounded Gboard force-stop (no cache/data cleared, IME settings unchanged) did not restore keyboard visibility. Do not blame `RemoteInput` alone or claim our notification code can reliably force SystemUI to present the IME.
- Google **Pixel Update – October 2026** explicitly identifies a fix for the virtual keyboard sometimes not appearing on search inputs on Pixel 8+ **including Pixel 10 Pro XL**. The documented Pixel 10 Pro XL target build is **CP3A.261005.005**. Our device is two monthly updates behind. Source: https://support.google.com/pixelphone/thread/471669543/google-pixel-update-october-2026?hl=en. This is a **strongly matching official fix**, not proof that every notification RemoteInput bug is resolved by the update.
- In Shizuku's public repository, `RikkaApps/Shizuku#459` describes keyboard disappearing specifically while in the system Wireless debugging pairing flow; in that user's case a third-party Edge Gestures service caused it. We did not find a currently visible overlay on this Pixel 10, so that other user's diagnosis is not proven to apply here.
- The lab application's `wireless_adb_identity.xml` pairing preferences file was **absent** after the user's first keypad attempt. Thus the six-digit attempt was *not* confirmed as a successful saved pairing. The old service also silently restored the input UI after an error; this is now changed to show a bounded failure category without writing secrets.

## UX decision

- Remove custom notification number-button grid, its XML layout, code buffer and tests. User found this unwieldy and it masks an underlying OS/IME issue.
- Prefer the platform notification `RemoteInput` path exactly as intended on healthy Android firmware, with a separate notification action **Open numeric keyboard** launching a standard, nonexported in-app dialog using `TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD`. This dialog uses native Gboard rather than a custom keypad, and is an optional fallback, not the default.
- App continues to reject non-six-digit input, does not copy to clipboard or persist/log codes, and promptly zeros mutable arrays. Pairing failure status is a sanitized category; the user should not interpret a closed dialog as a successful pair.
- **Recommend Pixel October OTA only as an explicit, supervised change**, since the update requires a reboot and may disrupt existing remote ADB control. Do not change the Pixel's OS, adbd, debug settings or other mobile device as part of this IME fix.

## Remaining acceptance

- Reconfirm that SystemUI `RemoteInput` shows keyboard after OS update (or a confirmed on-device OS/IME fix), and validate native numeric fallback's notification action separately. A synthetic lab test verified that the app's dialog gains native EditText focus and the framework reports the Gboard input view active, but this pre-update firmware still visually fails to render the full keyboard.
- Owner enters a fresh Android pairing code directly on the phone. App must visibly show pairing result, persist authenticated GUID, and then verify STLS → Binder → removable eUICC read-only inventory before marking Draft PR #17 mergeable.

## 2026-10-25 production acceptance correction

The separate numeric-input Activity proposed above is not a valid pairing
fallback. Automated APK49 testing reproduced the pairing server closing on
notification-content launch, including while holding Settings until a result.
AOSP WirelessDebuggingFragment.onPause removes MODE_PAIRING; its dismiss listener
calls disablePairing. A floating theme or another task does not prevent onPause.
APK50 removes the Activity and content PendingIntent and accepts codes only via
SystemUI RemoteInput. On the current Pixel10 firmware, the framework reports
notification reply input shown and live pairing + UID2000 handoff pass. No overlay
permission, Settings modification or debugging toggle is introduced.

Source: https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/development/WirelessDebuggingFragment.java#239
