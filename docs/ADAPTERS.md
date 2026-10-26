# Android and ROM adapter guide

Prefer a reusable capability check over a model-specific exception. Most new
phones should use an existing interface; a successful new-device report may need
only a compatibility-documentation change.

## Where code belongs

All Java paths below are relative to
`android-app/app/src/main/java/dev/sid/fiveberinspector/`.

| Responsibility | Entry points |
| --- | --- |
| Enumerate services/readers and bind trusted system APIs | `SystemHalAdapter`, `HidlManifestReaders`, `ReaderDescriptor` |
| Probe card presence, ISD-R, identity and cleanup | `FiveberReaderSelector`, `SecureElementFailure`, `CompatibilityReport` |
| Shared channel interface | `SecureElementTransport` |
| AIDL / HIDL / Telephony / OMAPI differences | `AidlSecureElementTransport`, `HidlSecureElementTransport`, `TelephonySecureElementTransport`, `OmapiSecureElementTransport` |
| Fixed read commands and Telephony operation scopes | `CardReadApduPolicy`, `TelephonyChannelCodec`, `TelephonyLifecyclePolicy`, `DownloadSessionPolicy`, `TelephonyNotificationPolicy` |
| User-visible fixed errors and safe report vocabulary | `UiMessages`, `CompatibilityDiagnostics` |

Some class/package names retain the original 5ber naming. Do not infer a card
allowlist from those names or add a manufacturer's identity to gain admission.

## Diagnose before changing code

Use [the read-only device check](NEW_DEVICE_CHECK.md). Identify the first failing
stage: service lookup, interface methods, card presence, management channel,
identity response, profile read or owned-channel cleanup. Preserve uncertainty
when a platform API cannot distinguish missing and denied access.

Check the actual interface declared by that ROM. AIDL comes from the trusted
installed system SecureElement APK; HIDL readers come from declarations, not
assumed SIM1/SIM2 names. Telephony requires the implemented physical-slot/port
methods. Do not broaden hidden-API reflection to arbitrary classes or relax the
real Shell identity to work around a denial.

A new interface belongs behind the transport boundary. Keep discovery read-only,
responses bounded and closure explicit. If the ROM denies access, report it;
permission must not be invented by ignoring an exception. The existing
Telephony AID-encoding workaround is experimental and may no longer work after
a ROM patch. A new adapter should document its own actual permission basis.

## Invariants for every adapter

- Enumerate actual readers; validate names and bounded inventories.
- Open only the intended management applet and close only owned channels.
- Do not call HAL init/reset or replace shared callbacks.
- During discovery, stop on uncertain cleanup; do not continue through another
  backend or card. Submitted enable/disable REFRESH cleanup has its own bounded
  independent postflight and session-retirement rules; an adapter must not
  invent a retry or skip that lifecycle policy.
- Bind the selected reader to the complete verified card identity. Equal masked
  suffixes do not establish that two cards are the same.
- Require explicit selection when more than one card is verified.
- Keep read-only discovery separate from operation-specific write admission.
  HIDL remains read-only unless a separately reviewed implementation changes it.
- Preserve caller authentication, transaction locking, consent tokens,
  durable unknown outcomes and independent postflight checks.

## Test and submit

Run `bash android-app/tools/run_compatibility_tests.sh` for discovery and policy
changes. Add focused cases for the new behavior and relevant negative paths:
missing methods, denied access, no card, malformed/duplicate readers, card swaps,
multiple cards, invalid EID and failed/lost channel-close responses.

Use controlled adapters for deterministic tests, then perform the read-only walk
on the actual ROM. State the exact tested app version and scope; simulated
passes do not qualify real hardware. A device report does not need raw APDUs,
identifiers or a long acceptance diary. Follow [CONTRIBUTING.md](../CONTRIBUTING.md)
for a concise PR.

## From a device result to a contribution

| Observation | First place to investigate | Useful contribution |
| --- | --- | --- |
| No discoverable interface | `SystemHalAdapter`, VINTF declarations and trusted system API lookup | Reusable discovery/binding support with missing/denied cases |
| Interface found, card cannot be read | Transport open/transmit/close behavior and `FiveberReaderSelector` failure stage | Focused adapter fix or a scoped failure report |
| One backend reads but writes are unavailable | `ReaderDescriptor` and operation-specific transport policies | Document the actual operation boundary; read success alone is not grounds to admit writes |
| Changing a profile loses the session | `ScopedLifecycleHalBridge`, `PortableProfileLifecycle` and `ReadOnlyRecovery` | Reproduce cleanup/postflight behavior without resending the card command |
| An existing adapter works on a new phone | No runtime change required | A compatibility row with app/ROM/card, backend and tested scope |

For a code PR, explain which interface differs, why its use is permitted on the
ROM, how the adapter remains bounded, and what was verified on the resulting APK.
Keep any transaction/recovery change separate from a transport-only fix unless
it is necessary to solve the same observed fault. Follow the check selection in
[CONTRIBUTING.md](../CONTRIBUTING.md); compatibility, recovery and bootstrap
changes have different relevant regression suites.
