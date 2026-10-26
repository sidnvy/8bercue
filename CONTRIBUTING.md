# Contributing

Help improve card support, Android/ROM adapters, usability, translations or
reproducible bug reports. A new device report is useful even when no code change
is needed. Start with the published Beta and a read-only check on a phone and
card you control.

## Try your device first

Follow [Check a new phone](docs/NEW_DEVICE_CHECK.md). Record the app version,
phone model, Android/ROM build, card family and selected backend. Distinguish
installation, companion connection, profile reads and individual write operations.
A read success should be submitted as a read success, without claiming downloads,
switching or mobile data were tested.

Use the [device report template](.github/ISSUE_TEMPLATE/device-compatibility.yml).
For 9eSIM, 海豚卡 or another card family, include the exact product/model and
firmware if known. Existing owner reports without those details remain reports,
not a complete compatibility guarantee.

Start with observation. Optional write testing should use your own disposable
profile and a separate plan for restoring the original state. Do not switch a
working subscription, consume an activation code or delete a profile merely to
prove discovery. Keep unresolved operation records for recovery.

## Add or improve an adapter

Read [the adapter guide](docs/ADAPTERS.md) and [architecture](docs/ARCHITECTURE.md).

1. Locate the failing stage: service lookup, interface binding, card presence,
   opening ISD-R, reading identity, profile read or closing the owned channel.
2. Prefer a reusable interface or capability check. A different phone name alone
   should not need a new branch or admission entry.
3. Keep platform differences in the transport/discovery layer. A new ROM adapter
   must not change profile transactions or grant generic APDU/Shell execution.
4. Add a focused regression for the observed behavior and relevant failure cases.
   Cover missing methods, denied access and cleanup failure when applicable.
5. Repeat the read-only device check on the resulting APK. State which operations
   were exercised and which remain untested.

Never relax complete card identity binding, explicit multi-card choice, caller
verification, one-use operation consent, transaction locks, per-card unknown-outcome
blocking or independent postflight reads to make a new phone appear supported.
A result unknown on one card must not restrict another card. Preserve legacy
records, scope new journals to the complete card identity, and prove that old
workers have stopped before releasing a global worker quarantine.
Do not initialize/reset a shared HAL or close another client's channels.

## Open a pull request

Keep the change small and explain the concrete failure and resulting behavior.
Include the device/ROM and adapter involved, the relevant tests, and a compact
result summary with limits. A compatibility-only report can update the matrix
without changing runtime code.

Follow [BUILDING.md](BUILDING.md) for the toolchain and commands. Run the tests
relevant to the changed layer; CI also runs the complete offline regressions,
pinned native rebuild, unsigned Android build and Lint. Label simulated tests as
simulated. Keep production signing keys out of contributions and CI for forks.

Maintainer review and required CI determine whether a PR can merge. CodeRabbit
and similar automated reviews are advisory; useful findings should be checked,
but waiting for a bot is not an additional merge gate after internal review.

## Public reports and documentation

Include only what helps another person use the app, reproduce the issue or
review the fix. Do not add per-session acceptance diaries, private device
snapshots, raw logs, chat transcripts or large evidence bundles to the source
tree. Keep detailed test archives separately and summarize relevant outcomes in
the issue or PR. Necessary regression fixtures, dependency provenance, licenses
and build/release instructions belong in the repository.

Exclude activation/confirmation codes, full EID/ICCID, private keys, access tokens,
profile payloads and unreviewed screenshots. Use fixed error categories or the
app's reviewed diagnostics instead of raw APDU/HTTP traces.

Keep discussion practical and respectful. Explain the behavior you observed and
allow for differences between cards, ROMs and app versions.
