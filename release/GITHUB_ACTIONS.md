# GitHub Actions: CI and signed Beta publication

This adds **two independent workflows** for the public 8bercue repository.

* **Android CI** runs on pull requests (including forks) and pushes to `main`: hermetic Java/Python regressions, tracked native package inspection, fresh ARM64 native rebuild with pinned NDK, unsigned Android build, release-surface checks and lint. It receives no release secrets and cannot publish. An unsigned main-branch engineering APK is retained briefly as a CI artifact, **not** as a user-downloadable release.
* **Publish signed Beta** runs only by an owner's explicit `workflow_dispatch` on **main**, with an **existing** `vX.Y.Z-beta.N` Git tag whose commit is on `main`. This workflow rebuilds the ARM64 native payload from bundled source archives, runs tests, builds a signed release APK, validates the approved signer, checks the reporting configuration, packages corresponding source and notices, computes SHA256 checksums and creates a **prerelease**. Any existing release is immutable.

## One-time GitHub configuration

The Actions environment **`beta-release`** has been created in GitHub Settings → Environments with `sidnvy` as the required reviewer and a branch policy limited to `main`. Keep these protections enabled. The workflow itself cannot provision or weaken this environment.

In that environment, add these **secrets** (never in repo, logs or PR comments):

| Secret name | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Base64 encoding of the owner-approved existing production keystore (binary; not the key itself in clear text) |
| `FIVEBER_RELEASE_STORE_PASSWORD` | Existing keystore password |
| `FIVEBER_RELEASE_KEY_ALIAS` | Existing signing alias |
| `FIVEBER_RELEASE_KEY_PASSWORD` | Existing key password |

The environment **variable** `GLITCHTIP_DSN` (non-secret client config) is already configured from `release/REPORTING_BUILD.md`; update it there if the reporting project changes. Do not use somebody else's reporting project. The signing identity must match the pinned certificate SHA-256 from `release/SIGNING_AND_MIGRATION.md`.

**Credential custody decision:** Uploading the existing production signing keystore to GitHub Actions changes its trust boundary. Do this only with the release owner's explicit approval, after establishing off-device recovery custody. If this is not approved, leave these environment secrets absent: CI will work but publication intentionally fails closed. Do not create a new signing key merely to satisfy automation.

GitHub Actions must be enabled on the repository. Optional: require the `Offline regression suite`, `Native ARM64 source rebuild`, and `Unsigned Android build and lint` checks in branch protection/rulesets before merging PRs.

## Preparing a new Beta release (manual owner gate)

1. Merge reviewed changes to `main` after required checks pass. Update Android `versionName` and monotonically increasing `versionCode`. Add `release/RELEASE_NOTES_<versionName>.md`, and refresh dated Beta policies/readiness evidence for the **same** build. In `BETA-READINESS.json`, set the matching `tag`, `apkVersion`, and `versionCode`, and leave `apkSha256` empty until the tagged build supplies its digest. The workflow refuses an old APK hash and fills the published readiness asset with the new digest. Do not claim unperformed hardware tests.
2. On the **reviewed source commit**, run `python3 android-app/tools/check_source_manifest.py --update`; commit the updated `SHA256.json` as part of the release-preparation changes. Source hashes must match **all** tracked files, including workflows, without hashing the manifest itself. Verify with `--check`.
3. Create and push the tag `v<versionName>` pointing at the reviewed `main` commit. Never move existing release tags.
4. In GitHub Actions → **Publish signed Beta** → **Run workflow**, select **main** and provide that tag. Approve the protected environment if prompted. Publishing is **not** triggered by a PR, regular merge, or creation of a tag alone.
5. Inspect the output and the newly created prerelease, including the signer fingerprint, downloadable APK, corresponding source archive, notices, notes and `SHA256SUMS`. Then perform real-device post-release validation on the **downloaded** signed APK; no successful CI job substitutes for hardware qualification.

A release fails before publication when the source manifest is stale, version/tag mismatch, release notes absent, signing inputs missing, expected signer incorrect, native build/test/artifact checks fail, or the tag already has a release. The release job only needs `contents: write` to create the GitHub Release. PR workflows have `contents: read` and no secrets.

## Download behavior

GitHub's `releases/latest` endpoint **excludes prereleases**. The 8bercue website already uses a Beta-aware GitHub Releases API resolver (`public/latest-release.js` in the separate website project). The CI workflow does not redeploy that website.

## Scope / limitations

This automates **limited Beta distribution**, not stable release qualification, Play Store uploads, live eUICC tests, or activation/disable/delete approval. It does not touch devices, ADB settings, unknown-operation journals, previous published tags or release assets. A new Beta still requires the owner's explicit tag/run/approval. Native compilation on the release runner may require more time and network access than the regular unsigned CI build.
