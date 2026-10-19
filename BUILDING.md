# Build the corresponding release source

This is the exact main/release source for 8bercue 0.18.0-rc1, versionCode 22. Debug-only laboratory tools, private device records, activation credentials and signing keys are excluded; none are compiled into the release APK. This archive does not claim to include the private test workspace or its full debug test suite.

Use the versions in release/toolchain.json. Set JAVA_HOME to JDK 17+, ANDROID_HOME to the SDK with platform 36 and NDK 27.3.13750724. The Gradle wrapper downloads its pinned distribution; Maven dependencies are resolved from the repositories in settings.gradle with dependency locks and verification metadata. An existing populated Gradle cache supports --offline.

Run `bash android-app/tools/build_android_lpac.sh` to rebuild the native payload from the bundled complete upstream source archives and local patch. Run `bash android-app/gradlew -p android-app :app:assembleRelease :app:lintRelease --console=plain` to build an unsigned APK. The native shared library can be modified and rebuilt using the same script; no publisher key is required to build or run a self-signed derivative.

To sign your own build, supply all four FIVEBER_RELEASE_KEYSTORE, FIVEBER_RELEASE_STORE_PASSWORD, FIVEBER_RELEASE_KEY_ALIAS and FIVEBER_RELEASE_KEY_PASSWORD environment variables. Use your own private key; the publisher key is intentionally absent. Android permits same-package upgrades only with a compatible signing identity. Review release/SIGNING_AND_MIGRATION.md before replacing an existing installation and preserve unresolved safety records.

Install your signed APK with `adb -s YOUR_DEVICE install path/to/app-release.apk`. Open the app, choose Connect using computer and run `python3 android-app/tools/bootstrap_companion.py --serial YOUR_DEVICE --adb /path/to/adb`. Review every device/profile selection before card operations. The companion requires authorized ADB access. No fixed card identity is required by this release.

The SHA256.json manifest covers archive contents except itself. Source archives and license notices remain unmodified. See LICENSING.md, COPYING and android-app/third_party.
