# Beta distribution policy

Beta releases are limited direct distribution, not full stable qualification.
Release notes describe the tested operations and known limits. A successful card
read or result on another APK/ROM does not qualify unperformed writes, network
service or other devices.

Publish only an owner-authorized tag on reviewed main source after necessary CI.
The protected signing workflow creates a draft. Public promotion follows
acceptance of the exact downloaded production-signed APK and asset/source checks.
Maintainer review and required CI are the gates; CodeRabbit is advisory.

The release includes the APK, full corresponding source, build scripts, native
source archives/local patches, licenses, privacy information, public certificate
and checksums. Private keys, activation credentials and internal device/test
archives are excluded. Tags and published APK/source assets are immutable.
Keep detailed acceptance records internally; publish a concise scope and relevant
checks in release notes.

Existing caller authentication, full card identity, explicit operation intent,
transaction locking and unknown-outcome recovery remain mandatory. Release
approval does not authorize a card mutation or waive these checks. Stable
`productionReady` remains false until its separate qualification is complete.

Native linking permission is limited to the exact admitted BoringSSL revision;
see [LICENSING.md](LICENSING.md) and
[the permission](release/BORINGSSL-LINKING-PERMISSION.txt). Protected signing and
migration are documented in [release/GITHUB_ACTIONS.md](release/GITHUB_ACTIONS.md)
and [release/SIGNING_AND_MIGRATION.md](release/SIGNING_AND_MIGRATION.md).
