# Interrupted operations and read-only recovery

A card operation can have an unknown outcome if communication stops after a
command was submitted. Reconnecting or reading the card does not prove that the
earlier command succeeded, and the app does not silently replay it.

## Restore observation

Reconnect through [wireless or computer setup](ADB_CONNECTION.md), then inspect
**Settings → Advanced → Verify card state**. Read-only recovery checks ownership
of the existing service/workers, exclusive card access, the original card identity
and stable profile inventories. It does not signal unrelated processes, delete
safety files, reset the HAL or retry the original write.

Successful observation can restore a fresh profile list while the earlier
outcome remains unknown. Failed cleanup, an active native worker, a changed card
or an unavailable original lock keeps further writes blocked.

## Review an unknown lifecycle operation

Eligible unknown enable/disable/rename/legacy-switch records can offer a separate
**Review the unknown operation** action. It requires fresh same-card observation
and explicit, short-lived user confirmation. The service persists a separate
review disposition; the original journal/history is not rewritten as a verified
success and the old operation is not replayed.

A persistent quarantine is archived only after the required locked checks and
review record are durable. A failed or interrupted review must not silently
authorize a new write. Download, deletion and unresolved notification delivery
need their own operation-specific reconciliation and are outside that lifecycle
review.

Do not clear app data or remove lock/journal/quarantine files to hide an unknown
result. Preserve the records and use a short, sanitized problem report.

Developers should run `bash android-app/tools/run_recovery_tests.sh` for changes
to recovery/worker exclusion and `bash android-app/tools/run_bootstrap_tests.sh`
for authenticated connection replacement. See [CONTRIBUTING.md](../CONTRIBUTING.md).
