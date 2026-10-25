# PR #17 review follow-up — 2026-10-25

Previous head: `0005c87`. The eight CodeRabbit findings were checked against actual code and addressed in this follow-up. **Draft remains required. Hardware acceptance of this exact APK is still blocked by missing local mDNS advertisement after normal unlock.**

| Finding | Change | Verification |
| --- | --- | --- |
| 4366819411 — localization and recovery claims | Ten bilingual resources replace inline UI text; recovery confirmation now describes paired local TLS 1.3 and full Shell authority; attachment failure no longer claims Binder connected | 2,229 localization checks; Release/Lab compile and Lint |
| 4366819416 — legacy socket gate | Restore socketEligible=false; independent wirelessEligible=true only at three wireless entry checks | Source boundary regression; native Release verifier unchanged |
| 4366819417 — swallowed failure cause | Log fixed event marker plus exception class name only | No message, GUID, certificate, challenge or code in recovery diagnostic |
| 4366819428 — busy NSD resolver | At most two delayed retries only for FAILURE_ALREADY_ACTIVE; keep original request/ticket; cancel on loss, supersession or close | 23 actual production NSD lifecycle checks with controlled callbacks/clock queue |
| 4366819433 — uncertain handoff retry | Consume a main-thread one-shot managementAttempted flag before executor dispatch; subsequent found/lost/failed callbacks cannot retry or replace manual-recovery notice | Source contract and Lab/Release compilation; actual new-pairing hardware path NOT TESTED |
| 4366819435 — false Release isolation PASS | Missing unsigned Release explicitly reports NOT CHECKED | Execute embedded gate with absent, valid, leaking and missing-JNI ZIP fixtures |
| 4366819438 — optimized-away artifact checks | Explicit nonzero exits replace assert in all three Lab/source-native gates | Actual source build and actual signed Lab verification with PYTHONOPTIMIZE=2; negative gate fixtures with python -O |
| 4366819439 — truncated license accepted | Actual packaged notice bytes must equal full tracked Lab notices, which must contain complete upstream BoringSSL LICENSE | Truncated-license rejection in normal and optimized modes; actual APK notice verification |

## Verification results

- Full applicable offline bootstrap, UI, compatibility, recovery, privacy and identity-composition suites PASS.
- 144 protocol/certificate/discovery assertions PASS, including 23 NSD lifecycle checks.
- 85 artifact/embedded-gate checks PASS, including python -O. These use synthetic ZIPs and tool adapters and are not represented as real signature/ELF acceptance.
- Actual Release/Lab assembly and Lint PASS. Gradle unit tasks remain NO-SOURCE; substantive tests are the offline harnesses.
- Actual source-native crypto rebuild with PYTHONOPTIMIZE=2 PASS: AArch64 ELF64, 16 KiB PT_LOAD, system-only dynamic dependencies.
- Actual signed Lab verification with python -O PASS: complete notices, exact native inventory/hashes and 16 KiB ELF/ZIP alignment.
- Unmodified verify_release_surface.py and actual unsigned Release artifact gate PASS, productionReady=false.
- Existing CI protocol entry now includes the artifact gate and runtime source-contract regressions. No workflow file or historical crypto CI entry was changed; the previously rejected workflow patch still needs a workflow-authorized maintainer.

## Additional discovery compatibility fix

The real discovery failure prompted inspection of the deprecated first-host accessor. On API 34+ the resolver now checks every returned address and selects only one belonging to an active local interface; API 31–33 retains the guarded first-host path. A multi-address fixture with foreign first host and local second address passes, while an entirely foreign list fails closed. This improves compatibility but did not resolve the actual missing local advertisement: the real callback supplied two foreign addresses. No local address or port is invented from Settings/diagnostics, and no classic endpoint is used as fallback.

## Exact follow-up artifact and hardware limits

Lab APK SHA-256: `410a875bfeaa78d8e5e485825809de1c5ebd70cb3d8da7ff42c86bfb98069960`. Lab signer SHA-256: `3a1ebed312d2f5ec257cde4a2966e3b8da7533be20b014bd77a48e171f6329ea`. JNI bytes remain the previously source-built library; runtime/UI fixes are in this new APK. Generated inventory: app/build/lab-artifact-inventory.json.

The Pixel 10 pairing preferences remained available with pairedGuid present. Matching-signer data-preserving upgrades were performed; the final install result and current lock state are recorded in the PR update. No data clear, key rotation, debugging/adbd/VPN reconfiguration, eUICC mutation or other-phone interaction was performed.

The owner normally unlocked the phone. The actual reconnect attempt failed safely at discovery: Android reports wireless debugging enabled, trusted network and listening TLS port 46587, while the service-discovery cache has no record for that port. The discovered service returned two non-owned candidate addresses, both rejected. No TLS/CNXN/Connect/Binder stage was reached on that attempt. The current limitation is service discovery, not a demonstrated pairing-key or TLS failure. The earlier two successful 2026-10-24 reconnect/read cycles belong to their earlier APK, not this follow-up artifact. Final current-head restart/reconnect/Binder/removable slot 1 port 0 read and additional repeats are **NOT ACCEPTED** pending restoration of a locally discovered endpoint within the permitted safety constraints. No forbidden system/debugging changes were attempted. The owner subsequently proposed a reboot and confirmed a verified USB fallback. A reboot remains pending until the native Workstation device list shows the Pixel 10 USB transport; the observed list currently contains only its wireless transport. No reboot has been performed.

Production native admission/licensing, protected compatible signing/migration, and the source-native workflow patch remain separate owner gates. Do not convert green CI or corrected review findings into production or hardware approval.

## Second review round: gate and timing coverage

Three further findings at `451b465` were verified and corrected without changing app/runtime or APK bytes:

- 4368934058: the Handler stub now tracks virtual due times. Both busy retries are checked at 299 ms (no retry) and 300 ms (one retry); cancellation checks advance past the deadline.
- 4368934060: collect every numbered apksigner certificate digest and require exactly one signer; a two-signer fixture is rejected in normal and optimized modes.
- 4368934062: compare the complete packaged LPAC asset set with the manifest, rejecting both additional and duplicate entries. Both negative fixtures run in normal and optimized modes.

The wireless suite now passes 144 protocol/certificate/discovery assertions, and the gate suite passes 85 synthetic policy checks. The unchanged actual signed Lab APK above also passes the stricter verifier with python -O. Earlier full build/Lint and native results remain attached to their original runtime commit; this follow-up changes tests, packaging verification and documentation only.

A read-only route lookup for the app UID sends IPv4 mDNS (224.0.0.251) through wlan0, and Android service discovery shows wlan0 rather than tun0. These observations do not establish WireGuard as the cause or exclude every VPN/filter interaction; no VPN change was made.
