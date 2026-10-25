# Wireless ADB and navigation release candidate

The owner authorized merging PRs #17 and #19, testing main, and publishing a new
release only after acceptance. The merged main is `3cd8ccf1b077b7073c37dae7b9f2980bfaf53ff8`.
Its complete source tree equals the previously accepted UI head `6cc4599`.

This separate preparation change admits only the two named ARM64 libraries,
requires the pinned source/archive/JNI hashes in the APK provenance receipt,
checks ELF alignment and Android-system-only dependencies, and retains all
existing release content, signing, backup, reporting and private-state gates.
Unlisted LPAC or ADB metadata assets and duplicate ZIP entries are rejected.

The complete, hash-locked BoringSSL archive accompanies the source tree so the
native build no longer downloads a dependency. Full upstream license text is
bundled verbatim, including its original whitespace.

The existing protected signer and main-only environment guard are retained.
The release workflow prepares an immutable candidate tag and draft prerelease.
Public promotion is a separate step after testing those exact downloaded bytes;
the workflow no longer publishes before final APK acceptance.

## Copyright decision

The current license is AGPL-3.0-only. The pinned BoringSSL includes old OpenSSL
and SSLeay terms. GNU lists that license combination as GPL-incompatible:
<https://www.gnu.org/licenses/license-list.html#OpenSSL>.
Section 7 describes additional permissions:
<https://www.gnu.org/licenses/agpl-3.0.html#section7>.

sid explicitly adopted the proposed limited linking permission on 2026-10-25.
The granted text is in release/BORINGSSL-LINKING-PERMISSION.txt and bundled
verbatim in the notices. The policy pins its SHA-256 and the publication guard
rejects a pending or mismatched permission. This covers only copyright sid
controls; it does not relicense any third-party component. The separately bundled
lpac runtime is not linked into the pairing JNI. Any other rightsholder's required
permission remains a separate obligation.

This document does not certify a legal review, unperformed device tests or card
write operations. The final signed APK needs its own acceptance receipt.
