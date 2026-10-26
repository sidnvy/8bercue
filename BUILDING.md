# Build 8bercue

Use the source tag matching a published release when reproducing that APK. The
current Beta is [0.20.0-beta.4](https://github.com/sidnvy/8bercue/releases/tag/v0.20.0-beta.4),
versionCode 52. Release archives include corresponding source, pinned native
sources, local patches, notices, a public certificate and checksums. Private
signing keys and per-session device archives are not build inputs.

## Toolchain and native components

Use JDK 17, Android SDK platform 36, build-tools 35.0.0 and Android NDK
27.3.13750724. The app targets ARM64 Android 9+ (API 28 minimum). The Gradle wrapper
and dependency locks/verification metadata pin Java build inputs. An existing
populated Gradle cache supports `--offline`.

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
bash android-app/tools/build_android_lpac.sh
STAGE_ADB_PAIRING_RELEASE=1 bash android-app/tools/build_adb_crypto_source.sh
bash android-app/gradlew -p android-app :app:assembleRelease :app:lintRelease --console=plain --no-daemon
```

The first script rebuilds LPAC and its dependencies from bundled locked sources.
The second rebuilds pinned BoringSSL/JNI and stages the exact Release inputs with
provenance. It clears generated staging before validation, so a failed build
cannot reuse stale JNI. Required native admission, source/license hashes,
allowlists, ARM64 and 16 KiB ELF/ZIP alignment are checked by the artifact gates.
Alignment checks are not physical 16 KiB-device runtime qualification.

Without signing variables, the result is
`android-app/app/build/outputs/apk/release/app-release-unsigned.apk`.
Verify it with:

```sh
python3 android-app/tools/verify_artifact.py android-app/app/build/outputs/apk/release/app-release-unsigned.apk --build-tools "$ANDROID_HOME/build-tools/35.0.0" --allow-unsigned
```

## Your signing identity

To build a signed derivative, supply `FIVEBER_RELEASE_KEYSTORE`,
`FIVEBER_RELEASE_STORE_PASSWORD`, `FIVEBER_RELEASE_KEY_ALIAS` and
`FIVEBER_RELEASE_KEY_PASSWORD` through your build environment. Use your own key;
the publisher key is intentionally absent. Read
[signing and migration](release/SIGNING_AND_MIGRATION.md) before replacing an
installation. A different signer cannot silently preserve the old app's data
and Keystore identity. Preserve unresolved safety records.

Install your signed build on the intended device, then use
[wireless or USB setup](docs/ADB_CONNECTION.md). A self-signed build is not an
upgrade of the production APK unless its signer is compatible.

## Relevant checks

```sh
bash android-app/tools/run_bootstrap_tests.sh
bash android-app/tools/run_ui_tests.sh
bash android-app/tools/run_compatibility_tests.sh
bash android-app/tools/run_recovery_tests.sh
bash android-app/tools/test_wireless_adb_discovery.sh
python3 android-app/tools/test_localization.py
python3 android-app/tools/test_reconcile_identity.py
python3 android-app/tools/test_feedback_privacy_contract.py
```

These include deterministic protocol, permission, identity, discovery, UI and
recovery checks. Simulated results do not establish live device qualification.
Use [CONTRIBUTING.md](CONTRIBUTING.md) to choose checks for a particular change.

`SHA256.json` records the sealed source snapshot excluding itself; release
`SHA256SUMS` covers downloadable assets. Before preparing a new release, stage
its intended files, update the source manifest with
`python3 android-app/tools/check_source_manifest.py --update`, then check with
`--check`. A tagged source tree is immutable.

Publisher reporting builds use [REPORTING_BUILD.md](release/REPORTING_BUILD.md).
Protected signing and draft-first publication are described in
[GITHUB_ACTIONS.md](release/GITHUB_ACTIONS.md). See [LICENSING.md](LICENSING.md) for
the AGPL terms, limited BoringSSL linking permission and third-party notices.
