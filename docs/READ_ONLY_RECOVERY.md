# Interrupted operations and recovery

“Unknown outcome” means communication stopped after a card command may have
been submitted, so the app did not receive a trustworthy final result. It does
not mean the card is damaged. The command may have succeeded; repeating it
without checking can make the situation worse.

## What the app checks automatically

Reconnect through your selected [connection method](ADB_CONNECTION.md). The app
reads the selected card automatically; **Refresh** repeats that read. No separate
compatibility or recovery test is needed for ordinary use.

Recovery checks that old owned workers have stopped, acquires exclusive card
access, verifies the complete card identity, and reads a stable profile list.
These are machine checks. A user does not need to interpret HAL diagnostics or
delete internal records to make them pass.

For an earlier enable/disable operation, the app can automatically confirm the
result only when two stable reads of the original card match the recorded
target, intended state and complete before-inventory transition. It writes a
separate durable observation receipt and retains the original unknown record.
Reading a vaguely similar list, an empty card or a different card is not enough.
Downloads, rename and delete are not automatically inferred by this path.

Enabling or disabling a profile can refresh the SIM and invalidate its old
logical channel. If an independent read proves the exact change and a fresh
companion is needed, wireless ADB and already-authorized Shizuku can reconnect
through the selected method. The computer route asks you to run its command
again. The app never repeats the card change as part of reconnecting. Phones
with several cards currently require choosing the card again in the new session.

## When a result still needs attention

A fresh list may be readable while the earlier operation remains unresolved.
The app keeps changes on that card paused when its intended result cannot be
proved. A missing original card cannot be checked by inserting a different one.
Other identified cards have their own operation histories and can be managed
independently; an old card's unknown result should not block them.

An active or unverified worker can still pause access across cards because the
underlying transport is shared. Reconnecting can retire only the app's own
verified old backend and check that work has stopped. This does not accept or
retry the original card operation. Unstable inventory, an unavailable original
lock or unclear ownership requires investigation.

Eligible unresolved enable/disable/rename/legacy-switch records may offer
**Review previous change** in **Settings → Operation records** after a fresh
read of the original card. This records an explicit review disposition; it does
not turn the lost response into a verified success or repair a broken interface.
Use it only when you understand the displayed unresolved change. Operation-
specific download review may let you abandon a locally unchanged uncertain
attempt once its checks pass; that does not prove the server or activation code
is reusable. Do not retry an uncertain download code simply because no profile
is visible.

## What helps a report

Report the app version, phone/ROM, card product if known, the action you selected
and the fixed message shown by the app. State whether the original card is
present and whether a fresh list can be read. Start with a short summary using
[the device report template](../.github/ISSUE_TEMPLATE/device-compatibility.yml).

Do not clear app data or remove lock/journal/quarantine files to hide an unknown
result. Avoid repeatedly changing debugging settings or resubmitting the same
card action. Detailed service logs belong in a private archive, not a public
issue or source commit.

Developers should run `bash android-app/tools/run_recovery_tests.sh` for changes
to recovery/worker exclusion and `bash android-app/tools/run_bootstrap_tests.sh`
for authenticated connection replacement. See [CONTRIBUTING.md](../CONTRIBUTING.md).
