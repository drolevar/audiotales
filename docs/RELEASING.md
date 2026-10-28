# Signed Android builds

The separate [release workflow](../.github/workflows/release.yml) runs when a stable version tag such as `v0.2.0` is pushed. It produces a signed, minified APK as a GitHub Actions artifact. It does not create a GitHub Release or publish to an app store. Ordinary branch and pull-request CI remains unsigned and has no signing inputs.

## Version identity

Tags must have exactly the form `vMAJOR.MINOR.PATCH`, without leading zeroes, prerelease labels or build metadata. `versionName` is the tag without `v`. `versionCode` is `MAJOR × 1,000,000 + MINOR × 1,000 + PATCH`; major is 0–2099 and minor/patch are 0–999. The code must be positive and at most 2,100,000,000. For example, `v0.2.0` becomes name `0.2.0`, code `2000`.

The workflow fetches all tags and rejects a version older than another existing stable version tag. Rerunning the same tag is supported while it remains the latest stable version. Keep published tags immutable. Development builds without `AUDIOTALES_RELEASE_TAG` retain name `0.1.0-dev` and code `1` (plus the debug suffix). The Gradle configuration validates a provided release tag independently.

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

Release signing inputs are supplied only to the signing step after the unsigned release build, lint and unit tests pass. The keystore is decoded into a private temporary runner directory, passwords reach `apksigner` through environment variables, and shell exit/signal traps remove the temporary key. The workflow token has read-only repository access and release runs do not write the shared Gradle cache.

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

Download it from the successful **Signed Android release build** run's **Artifacts** section. Retain the mapping and provenance for each distributed build. A signed build does not establish Android Auto, physical audio, headset or Bluetooth acceptance.

The release package ID is `io.github.drolevar.audiotales`; debug builds use `.debug`, so the release is a separate installation with its own library and progress.

## Local validation without a release

Run the pure boundary checks from the repository root:

```sh
python3 -m unittest discover -s scripts -p release_test.py -v
python3 scripts/release_version.py --tag v0.2.0 --check-tags
```

Build a local unsigned candidate with `AUDIOTALES_RELEASE_TAG=v0.2.0` and the usual release Gradle tasks. A disposable local test key can exercise signing and `scripts/release_verify.py` without creating or pushing a real release tag. Keep disposable keys and generated APKs outside tracked source. A complete verification command needs the signed APK, tag, full source commit, expected certificate fingerprint, pinned Build Tools directory, R8 mapping, partner-configuration presence and provenance output path; `--help` lists the arguments.

Official references: [Android app signing](https://developer.android.com/studio/publish/app-signing), [versioning](https://developer.android.com/studio/publish/versioning), [zipalign](https://developer.android.com/tools/zipalign), [16 KiB page support](https://developer.android.com/guide/practices/page-sizes), and [GitHub workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax).
