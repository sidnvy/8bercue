> Current release preparation: [2026-10-25 candidate](RELEASE_CANDIDATE_20261025.md) adds explicit fixed-revision native admission and owner-granted linking permission. The historical Lab-only/isolation statements below describe earlier builds. Public release still requires exact signed-APK acceptance.

# ADB native dependency and signed delivery — 2026-10-24

> Current acceptance: [fresh onboarding and release audit, 2026-10-25](PIXEL10_FRESH_ONBOARDING_ACCEPTANCE_20261025.md) — owner-authorized fresh pairing and three new-identity reconnect/read cycles PASS on APK `07f09a13…`; production gates remain open. Earlier APK results below retain their original scope.

> Earlier exact-APK hardware result: [2026-10-25 post-reboot acceptance](PIXEL10_POST_REBOOT_ACCEPTANCE_20261025.md) — three reconnect/read cycles and expired-choice recovery PASS; production gates remain open. Earlier statuses below are historical.

> Latest code/review status: [2026-10-25 follow-up](PR17_REVIEW_FOLLOWUP_20261025.md). Historical hardware evidence below remains scoped to its tested APK.

**Lab only. Production Release excludes the pairing JNI library. The unchanged release verifier is not amended to allow it.**

## Provenance and build inputs

The historical Lab dependency was `io.github.vvb2060.ndk:boringssl:20250114`, AAR SHA-256 `366c9af78cee572ffcfad6e8c17650646b9f79492e8177d35226a2a5569f3fbc`. Its POM points to [BoringSSL_Android](https://github.com/vvb2060/BoringSSL_Android). Tag `20250114` resolves to wrapper commit `20b87e2e25a793531c9cd651c629c62976b42e2b`; its native submodule points to upstream BoringSSL `571a7432a19592c620fa316abde47770dad4f82b`.

The new Lab build compiles crypto from that exact upstream source, rather than linking the Maven prebuilt static archive. `tools/build_adb_crypto_source.sh` checks source archive SHA-256 `d44a73d4927b66f0d43b51f171a044a900b91eeb431763083915f33e64713a0e`, rejects unsafe archive paths/links, compares the upstream LICENSE to the tracked notice, and builds with Android NDK `27.3.13750724`, API 28 and Release CMake flags. CMake directly links the source-built static crypto target into the bounded JNI backend and synthetic executable. This is source provenance, not a claim of byte identity to the historical Maven AAR.

Reproduce:

```sh
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
bash android-app/tools/build_lab_adb_pairing.sh
```

The Lab build deliberately uses the existing stable local debug signing identity. Do not generate a replacement key for device upgrades. A missing signing identity should fail the build.

## Notices and artifact checks

`third_party/boringssl-LICENSE.txt` retains upstream OpenSSL, SSLeay, ISC and included third-party license notices verbatim. Lab's `assets/legal/third-party-notices.txt` includes existing runtime notices plus the exact BoringSSL license. Production notice assets and native allowlist remain unchanged. Maintainer licensing approval is still required before distributing native pairing in production; adding notices alone does not grant a production authorization.

`verify_lab_artifact.py` checks the actual signed APK, exact JNI/native asset inventory and native asset hashes, AArch64 ELF64, 16 KiB PT_LOAD and ZIP alignment, permitted dynamic dependencies, and packaged BoringSSL notice. Crypto itself links only Android system libraries. Existing lpac assets retain their declared internal library dependencies. Generated inventories include APK and library hashes/sizes and signer digest and always report `SIGNED_DEBUGGABLE_LAB_ONLY`, `productionReady=false`.

Build inventories: `app/build/adb-crypto-source-inventory.json` (unstripped source outputs), `app/build/lab-artifact-inventory.json` (actual packaged library bytes). Gradle may strip the packaged JNI; use the appropriate inventory for each artifact. Synthetic executable is never packaged in an APK. The source rebuild passed locally. The owner then completed GitHub OAuth `workflow` authorization, and commit `6335df69542a5fde34effc06774bf4165e013f20` successfully enabled `build_adb_crypto_source.sh` in Android CI. It replaces only the historical pinned-AAR crypto build step, while retaining the LPAC source rebuild and existing gates. This step compiles both JNI and the synthetic contract and checks source digest, notice equality, ARM64, 16 KiB alignment and system-only dynamic dependencies; it does not execute the ARM64 contract or sign/publish an APK. The actual job result is recorded in [PR #17 checks](https://github.com/sidnvy/8bercue/pull/17/checks). The earlier rejection was from GitHub OAuth scope, reproduced outside the sandbox; no alternate credential/control path was used.

## Signed upgrade assessment and remaining gate

The Pixel's installed Lab signer was compared against each rebuilt Lab APK before `adb install -r -t`; the signer matched and pairing preferences remained byte-for-byte equivalent as parsed values. The key was not rotated; successful paired reconnect independently demonstrates continued use of an authorized key.

The approved production certificate is identified by SHA-256 `24aa7984b0d0977ff1e87b57beca91b2e36a05c04a5443f1a47aa23d90a6ef1c`. Lab has a separate debug signer. A production-signed APK cannot be assumed to upgrade that Lab installation while preserving its data/Keystore. No signing lineage or production signing credentials were used in these hardware tests. The later fresh-onboarding run separately obtained explicit owner authorization to remove only the Lab app data; that does not authorize an implicit cross-signer migration or further data removal.

Protected production signing is already provisioned: the existing main-only signer smoke succeeded at `91564e7` ([run 39735032895](https://github.com/sidnvy/8bercue/actions/runs/39735032895)), and the published certificate digest was independently verified. Only protected secret names were inspected, never values. This baseline smoke does not sign or approve the current wireless candidate.

A production candidate with pairing JNI would also fail the current intentionally unchanged native allowlist. Source build, notices, Lab alignment and Lab signed-upgrade evidence are complete, while **production native admission, licensing approval, protected signing of the exact final candidate and compatible production upgrade acceptance remain open**. Do not label this Lab APK a production-ready Release or mark PR #17 ready for merge on the strength of Lab acceptance alone.
