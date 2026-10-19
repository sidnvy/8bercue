# Privacy and Data Safety working draft

This draft is based on the current implementation. It is not a submitted Play declaration. Publisher: sid (individual). Privacy/support: support@sidnvy.com. Direct APK distribution is the selected channel. The bundled legal/privacy.txt is the user-facing policy. Safety records have no automatic expiry; reviewed removal must preserve unresolved operations. No safety-record deletion is performed remotely by the publisher.

| Flow | Data | Recipient / storage | Trigger |
| --- | --- | --- | --- |
| Local card discovery and verification | EID, ICCID, card capabilities and profile metadata | App/privileged local service; masked UI, hashes in safety journal | User connection/read or confirmed operation |
| Provisioning HTTPS | Activation/confirmation credentials, GSMA messages, card certificate/identity data, network address | Operator SM-DP+ selected by the user's activation code; approved transaction host | User confirms download; prepare must not consume code |
| Notification HTTPS | Signed notification, sequence and protocol metadata | Recipient bound and validated for the installed profile | Verified install; lost response is not automatically resent |
| QR camera/image | Camera frames or explicitly chosen image | Local decoding, no QR upload | User chooses scan/import |
| Safety records | Card/profile hashes, intent/snapshot hashes, operation, time, outcome; notification host/sequence | Owner-restricted Shell local storage; may outlive uninstall | Before a mutation/network notification |
| Diagnostic export | Allowlisted, redacted operational status | User-selected Android share target | Explicit user action |

No advertising/analytics SDK or developer-operated collection endpoint was found in the audited release dependency set. This does not imply that operator provisioning sends no personal data. Platform/ADB/Shizuku are separate trust boundaries; the optional Shizuku manager is not bundled. Backup is disabled in the APK manifest. Activation drafts are excluded from view-state saving and cleared on leaving the foreground; logs/journals must never contain activation secrets or raw protocol payloads.

Before submission, review the exact RC against current [Google Play Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469) and [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311). Determine collection/sharing categories from actual off-device operator flows and the applicable service-provider definitions; do not prefill “no data collected” solely because 8bercue has no backend. Publish a reachable privacy URL and owner contact, specify retention/deletion including unresolved journal handling, and align app disclosures with the final policy.
