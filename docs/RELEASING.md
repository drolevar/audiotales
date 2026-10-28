# Signed Android releases

The separate [release workflow](../.github/workflows/release.yml) runs when a stable version tag such as `v0.2.0` is pushed. It builds and verifies a signed, minified APK, retains a GitHub Actions artifact and publishes a [GitHub Release](https://github.com/drolevar/audiotales/releases) with the exact verified files. It does not publish to an app store or change repository visibility. Ordinary branch and pull-request CI remains unsigned and has no signing inputs.

## Version identity

Tags must have exactly the form `vMAJOR.MINOR.PATCH`, without leading zeroes, prerelease labels or build metadata. `versionName` is the tag without `v`. `versionCode` is `MAJOR × 1,000,000 + MINOR × 1,000 + PATCH`; major is 0–2099 and minor/patch are 0–999. The code must be positive and at most 2,100,000,000. For example, `v0.2.0` becomes name `0.2.0`, code `2000`.

The workflow fetches all tags and rejects a version older than another existing stable version tag. The build can rerun the latest stable tag; publishing fails closed if any draft or published Release already exists for that tag. Keep published tags and assets immutable. Development builds without `AUDIOTALES_RELEASE_TAG` retain name `0.1.0-dev` and code `1` (plus the debug suffix). The Gradle configuration validates a provided release tag independently.

## Maintainer setup

Create or reuse a durable Android release keystore and keep an independent protected backup. Future updates must retain its signing identity. Do not commit the key or its passwords, and do not substitute a disposable test key for the release identity.

Configure these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing-key alias |
| `ANDROID_KEY_PASSWORD` | Signing-key password |

Set the repository Actions variable `ANDROID_SIGNING_CERT_SHA256` to the expected release certificate's SHA-256 fingerprint. The verifier accepts hexadecimal digits with optional colon separators. An absent setting or a mismatched signer fails the build.

The optional secret `AUDIOTALES_PARTNER_CLIENT_QUERY` supplies partner-provider configuration during compilation. Without it, LibriVox.org remains available and the app honestly reports partner access as unconfigured. This value is embedded in the APK and can be extracted; Actions secret storage does not make an APK-embedded value confidential. Provider access and distribution arrangements remain separate requirements. Local builds can continue using ignored `local.properties`.

Release signing inputs are supplied only to the signing step after the unsigned release build, lint and unit tests pass. The keystore is decoded into a private temporary runner directory, passwords reach `apksigner` through environment variables, and shell exit/signal traps remove the temporary key. The build job has read-only repository access and does not write the shared Gradle cache. A separate publishing job receives only a job-scoped `contents: write` workflow token and the expected public signing fingerprint; it has no signing or partner-client secrets.

The release gate runs `:app:assembleRelease`, `:app:lintRelease` and the existing
`:app:testDebugUnitTest` suite. AGP 9's default creates local JVM tests only for
the tested debug build type. Those tests exercise the shared source; they do not
establish runtime behavior of the minified APK.

## Verification and artifact contents

The workflow verifies the actual signed APK using `apksigner`, checks its signer against the configured fingerprint, and inspects package ID, version, API 26 minimum, API 37 target and non-debuggable status. It runs `zipalign -c -P 16 -v 4` and independently checks every bundled `.so`'s ELF load-segment alignment for 16 KiB page compatibility.

Only a successful verification uploads the artifact, retained for 30 days. It contains:

- `audiotales-VERSION.apk`
- `mapping.txt` from the exact minified build
- `provenance.json` with tag, source commit, version, signer fingerprint, hashes, native-library alignment results and a boolean indicating partner-configuration presence
- `SHA256SUMS` for the APK, mapping and provenance

The upload explicitly selects these four files. Any `.apk.idsig` sidecar emitted by signing stays outside the distributed bundle.

Download these files from the tag's GitHub Release. The successful **Signed Android release build** run's **Artifacts** section also provides the bundle for 30 days, including when publishing fails after a successful build. Retain the mapping and provenance for each distributed build. A signed build does not establish Android Auto, physical audio, headset or Bluetooth acceptance.

The release package ID is `io.github.drolevar.audiotales`; debug builds use `.debug`, so the release is a separate installation with its own library and progress.

## Publication and recovery

The publishing job downloads the successful build's exact immutable artifact ID using the SHA-pinned official download action, which fails on an archive-digest mismatch. `scripts/release_publish.py` requires exactly the four expected nonempty assets, validates their checksums and compares the provenance's tag, commit, application/version identity and signing fingerprint with this run. It also resolves the remote tag to the built commit, including annotated tags.

The tool creates a draft with concise installation notes, including separate `.debug` data and pending Auto/headset/Bluetooth qualification. It uploads all four assets without replacement flags, verifies the draft's identity and asset inventory, then downloads its assets into an empty directory and compares every byte's SHA-256 with the build artifact. Only after those checks and a final remote-tag check does it publish the draft. The Actions artifact remains the recovery source if any later step fails. Publication does not qualify playback behavior.

An existing draft or published Release causes publication to fail without changing it. Network or authentication errors also fail; they are never treated as an absent Release. A partial upload leaves a draft for maintainer inspection. Do not move the tag, overwrite assets or blindly rerun publication. Inspect the failed run and draft, download the retained build artifact and compare all four files and provenance. An unpublished failed draft may be removed manually after review before rerunning the failed publishing job against the same build artifact. An already published Release is retained; use a new version tag for corrections. If publication succeeded but the final status check failed, inspect the existing Release instead of replacing it.

## Local validation without a release

Run the pure boundary checks from the repository root:

```sh
python3 -m unittest discover -s scripts -p release_test.py -v
python3 scripts/release_version.py --tag v0.2.0 --check-tags
```

Build a local unsigned candidate with `AUDIOTALES_RELEASE_TAG=v0.2.0` and the usual release Gradle tasks. A disposable local test key can exercise signing and `scripts/release_verify.py` without creating or pushing a real release tag. Keep disposable keys and generated APKs outside tracked source. A complete verification command needs the signed APK, tag, full source commit, expected certificate fingerprint, pinned Build Tools directory, R8 mapping, partner-configuration presence and provenance output path; `--help` lists the arguments.

Official references: [Android app signing](https://developer.android.com/studio/publish/app-signing), [versioning](https://developer.android.com/studio/publish/versioning), [zipalign](https://developer.android.com/tools/zipalign), [16 KiB page support](https://developer.android.com/guide/practices/page-sizes), [GitHub workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), [artifact downloads by ID](https://github.com/actions/download-artifact#download-artifacts-by-id), and GitHub CLI [draft creation](https://cli.github.com/manual/gh_release_create), [upload](https://cli.github.com/manual/gh_release_upload), [download](https://cli.github.com/manual/gh_release_download) and [publication](https://cli.github.com/manual/gh_release_edit).
