# Read-only recovery

An uncertain operation must not disable observation indefinitely. Version 46 separates exclusive read-only card access from mutation authorization. It preserves the persistent quarantine marker and original transaction bytes; it never retries a write or treats a readable card as proof that an earlier operation succeeded.

## Recovery path

1. Open **Connect using computer** and run the same displayed command during the App-created window. An existing connected session is unchanged unless the app explicitly opens a recovery window. The Shell-to-App challenge, version/APK pin and authenticated typed Binder remain mandatory.
2. When a persistent quarantine exists, the Shell client validates the marker and journal, serializes recovery attempts and inventories the phone's processes. It refuses recovery while any Shell native `lpac` worker or child of an old backend remains. It verifies each old backend's UID 2000, exact name, executable, package APK classpath and process start time, rechecks identity before requesting termination, and verifies exit. It never signals a native worker, another app or an unverified process. If process inspection is denied or inconsistent, access stays paused.
3. Recovery must obtain the original kernel file lock. Neither the lock inode nor quarantine marker is unlinked. New immutable recovery receipts bind the marker, original transaction and installed APK digests; no card/Profile identifier or credential is stored in clear text.
4. The authenticated backend reacquires that exclusive lock, verifies the complete eUICC identity against the unresolved transaction, reads the complete Profile inventory twice and requires identical fingerprints and stable reader/card identity. Failed cleanup, changed cards and unstable results remain blocked.
5. Successful observation is displayed as **Read-only access restored**. The earlier outcome remains **unknown**, mutation controls stay unavailable, and all write entry points still require both an unblocked lock and reconciled history. No automatic enable, rollback, nickname, download, notification submission or deletion occurs.

The current recovery scope restores observation only. It does not resolve the earlier transaction's intent or provide authorization to resume writes. If a backend cannot be proved stopped, the kernel lock is busy, recovery evidence is invalid, or the same card is unavailable, retain records and investigate; do not clear app data or delete safety files.

## Explicit lifecycle review (Recovery V2, draft)

Read-only access **never** by itself clears an uncertain write. After the user reads the
visible profile list, Settings → Advanced recovery → **Review the unknown operation**
offers a separate, single-use, 90-second consent procedure. It is limited to a
durable, bound `enable` / `disable` / `rename` / legacy `switch`
transaction whose result is `running`, `unknown`, or `observed_unknown`.

The service uses the original exclusive card lock, rechecks the original full
card identity and selected reader, obtains identical complete inventories twice,
requires the same write-policy eligibility and verified absence of unmanaged Shell
workers, and then rechecks all bindings after user confirmation. It persists an
immutable review disposition bound to the original record digest, observed
inventory and consent token. The **original journal and history remain untouched**:
the old outcome stays `unknown`, never `verified`, and the old write is not
replayed.

Two independent blocks are handled:

- **Unknown journal without a quarantine marker** ([#14](https://github.com/sidnvy/8bercue/issues/14)).
  The new review disposition is the last durable mutation under exclusive
  locking and verified worker quiescence. A stale UI-only `pending` flag
  is then cleared after verified service state.
- **Unknown journal with a persistent worker marker** (related to
  [#2](https://github.com/sidnvy/8bercue/issues/2)).
  The disposition is fsynced **before** marker archival. Until the old marker
  has been archived under exclusive locking and a separately fsynced receipt
  has been created, the active quarantine continues to prevent writes.
  A crash after review but before archival requires another explicit same-card
  confirmation; it must not silently grant write access.

The marker is moved atomically to an archival filename; neither the lock inode
nor original quarantine bytes are deleted. The archival receipt contains
digests, not raw card identifiers or credentials.

**Excluded:** `download`, `delete`, and unresolved notification delivery.
Their effects may be irreversible or remote and require different, operation-
specific reconciliation. A read-only card is **not** proof of write support on
the ROM, and the review procedure does not certify a failed mutation as successful.

The REFRESH/`closeChannel()` false-quarantine root cause from issue #2 is
handled separately by [PR #3](https://github.com/sidnvy/8bercue/pull/3);
this review path does not replace that upstream fix. PR #13 also changes
recovery/UI code and should be integrated separately after review.

## Qualification

`bash android-app/tools/run_recovery_tests.sh` exercises worker identity, PID reuse, children/native workers, observed termination, process-table names containing spaces, malformed/symlink evidence, cross-process exclusion and preserved unknown journals. Provider tests cover explicit recovery windows, live Binder replacement rejection and single-use authenticated replacement.

The owner requested publication before device verification. Version-46 device results are recorded against the downloaded Release APK after publication. Earlier candidates and other devices are not acceptance evidence for that APK.

Beta 2 (47) fixes nested Shell scopes in the explicit Verify card and recovery entry. Beta 1 (46) successfully read the card but that entry still failed. APK47 live evidence must come from its published download.