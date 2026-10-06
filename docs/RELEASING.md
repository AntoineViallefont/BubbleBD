# Build and release

**English · [Français](RELEASING.fr.md)**

## Development setup

Install Android Studio with JDK 17 or newer, Android SDK 36, build-tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1. Copy `local.properties.example` to `local.properties` and set the SDK path. Gradle and CMake download dependencies on the first build.

```sh
git clone https://github.com/AntoineViallefont/BubbleBD.git
cd BubbleBD
cp local.properties.example local.properties
```

Edit `sdk.dir` in `local.properties` before building. The usual macOS path is `/Users/YOUR_NAME/Library/Android/sdk`.

```sh
./scripts/build.sh
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. It has a different signature from the public beta in Releases.

To update a checkout without local changes:

```sh
git pull --ff-only
./scripts/build.sh
```

## Publication signing

Official releases use a key separate from Android Debug. The maintainer's key and password remain outside the repository in `~/.config/bubblebd/signing/`: `release.p12` (alias `bubblebd`) and `password`. Keep a separate encrypted backup and reuse the same key for updates. Never upload this directory to GitHub.

Forks must generate their own key in a private directory and set `BUBBLEBD_SIGNING_DIR`. An APK with a different key cannot update the official app.

```sh
./scripts/build.sh --release
```

This builds release, runs JVM tests and release lint, verifies 16 KiB ZIP alignment, then signs and verifies the APK. It fails if the private key is missing. It does not publish anything. ZIP alignment alone is not a runtime test on a 16 KiB device.

Increase `versionCode` for every public update. Also update `bubbleVersion` and delivery filenames in the relevant scripts. Review permissions, network configuration and privacy documentation. Test the signed APK and an update before publication.

Public build identifiers are documented in `public-build.properties`; it contains no provider secret. Copy the values to `local.properties` to use the same configuration. The shared metadata service has shared quotas: public forks should host their own service or leave the endpoint empty.

## Checks and limitations

All automated Android checks use `scripts/test-device.sh` and the dedicated `BubbleBD_Test_API_36_1` AVD on port 5580. The private reference corpus cannot be redistributed and is absent from the repository. The maintainer keeps evidence and runs the complete corpus for engine changes. Do not report all tests passing while known failures remain.

For language-specific checks, with the dedicated AVD running:

```sh
BUBBLEBD_TEST_LANGUAGE=en ./scripts/test-device.sh -Pandroid.testInstrumentationRunnerArguments.class=fr.bubblebd.LocalizationTest -Pandroid.testInstrumentationRunnerArguments.localizationLanguage=en
BUBBLEBD_TEST_LANGUAGE=fr ./scripts/test-device.sh -Pandroid.testInstrumentationRunnerArguments.class=fr.bubblebd.LocalizationTest -Pandroid.testInstrumentationRunnerArguments.localizationLanguage=fr
```

The script resets test-library data. Do not use it on a phone or a shared emulator. Other existing UI tests use French labels; the test script defaults to French.

Beta 0.3.33 retains the existing detection algorithm. The latest complete private baseline covered 61 pages and 340 variants; 15 pages still failed at least one criterion. These figures are not a success-rate estimate for all comics.

Older Firebase builds used Android Debug signing and cannot be replaced directly by the public beta. Uninstalling would erase local app data; always explain this consequence. Original comic files are preserved.

## Signed update check

With the maintainer's signing key available, the following resets only the dedicated test emulator, signs a temporary test companion and checks a synthetic library and preferences before and after an in-place update. Both APKs must use that key. It does not require root or touch a phone.

```sh
./scripts/test-device.sh --update-release distribution/BubbleBD-0.3.32-beta.apk distribution/BubbleBD-0.3.33-beta.apk
```
