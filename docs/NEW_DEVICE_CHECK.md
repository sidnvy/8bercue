# Check a new phone

Use a phone and card you control. Start with the signed Beta from
[Releases](https://github.com/sidnvy/8bercue/releases), and record its version.
The app requires ARM64 Android 9+. A ROM must also expose an accessible card
interface; Android version and phone brand alone do not establish support.

## Connect and read

1. Install the APK. For upgrades, keep the existing production signing identity
   and app data. A Lab/self-signed APK may use a different key; a signing conflict
   is not a reason to discard existing data or unresolved operation records.
2. Follow [wireless or USB setup](ADB_CONNECTION.md). Wireless setup is available
   on Android 12+ with Wi-Fi. The main-user computer bootstrap uses the displayed
   App-created connection window.
3. If several cards are readable, explicitly choose the intended card. Check the
   physical slot/port and masked suffix on the selection sheet. A built-in and
   removable eUICC are different cards.
4. Wait for a fresh profile list. Check it against the intended card. An empty
   list can be valid; a connected companion alone is not a verified card read.
5. If reporting compatibility, open **Settings → Help & feedback → Connection
   details**. Note the selected backend and any fixed failure stage. Use **Refresh**
   on the home screen to repeat the read; no separate compatibility test is needed.
6. Restart only the app, reconnect and repeat the read. Note whether pairing or
   a computer command was needed again. No phone reboot is needed for this check.

This walk performs observation. Do not install, enable, disable, rename or delete
profiles to test discovery. Keep active subscriptions unchanged. A cleanup error
or unresolved operation should be reported, not cleared by deleting safety files.

## Describe the result

| Result | What it establishes |
| --- | --- |
| Installed and launched | APK/ROM installation works |
| Companion connected | Authenticated App-to-Shell service is attached |
| Card identity and profiles read | The selected card can be observed through that backend |
| An individually tested write | Only that operation on that app/ROM/card combination |
| Network registered or mobile data worked | Separate radio/operator service observation |

Use the [device report template](../.github/ISSUE_TEMPLATE/device-compatibility.yml)
with app version, phone model, Android/ROM build, card product/firmware if known,
setup method, backend and the result. Include a short reproduction and fixed
error category for failures. Failures help identify missing adapters too.

The app's **Settings → Help & feedback → Export diagnostics** offers reviewed
formats. Keep full EID/ICCID, activation/confirmation codes and raw APDU/HTTP logs
out of public reports. A small sanitized summary is enough to begin triage.

For code changes, see [CONTRIBUTING.md](../CONTRIBUTING.md) and
[the adapter guide](ADAPTERS.md).
