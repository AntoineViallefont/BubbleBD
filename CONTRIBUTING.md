# Contributing to BubbleBD

[Français](CONTRIBUTING.fr.md)

Reader feedback is as valuable as code. Use the issue forms to [report a problem](https://github.com/AntoineViallefont/BubbleBD/issues/new?template=bug.yml), [suggest an improvement](https://github.com/AntoineViallefont/BubbleBD/issues/new?template=feature.yml), or [volunteer to test](https://github.com/AntoineViallefont/BubbleBD/issues/new?template=tester.yml). English and French are welcome.

Issues are public. Your GitHub username is sufficient; do not post a Google email address, credentials, private comics or personal data. Only attach screenshots you have the right to share. For a panel-detection problem, describe the layout, gestures and whether full-page reading still works.

## Code

Discuss substantial changes in an issue first, then submit a pull request from your fork. Preserve original files, reading progress, local reading and the absence of a BubbleBD account. Describe the before/after behaviour and checks performed. Contributions use AGPL-3.0-or-later, respecting third-party licences.

Detection rules must be general: no exceptions based on a book title, filename or page. Never change approved references or weaken assertions to hide a regression.

## Translations

Presentation strings are centralised in `app/src/main/java/fr/bubblebd/UiCatalog.kt`. French labels also exist as historical internal keys. Translate their presentation only; do not translate persisted sorting/filter keys, book titles, summaries or filenames. Keep numbered placeholders unchanged. `UiTextTest` checks placeholder parity and data preservation. Android supports English and French through `locales_config.xml`; other phone languages use the English interface.

## Checks

Install the tools listed in [the build guide](docs/RELEASING.md), then run:

```sh
./scripts/build.sh
python3 -m unittest discover -s services/metadata -v
node --test services/metadata/worker/test-worker.mjs
```

Node 22.13+ with `node:sqlite` is required. Backend tests are offline and need no provider key or payment.

Android checks must use `scripts/test-device.sh` on the dedicated `BubbleBD_Test_API_36_1` AVD at port 5580. They reset test data. Never target a personal phone. Examples:

```sh
BUBBLEBD_TEST_LANGUAGE=en ./scripts/test-device.sh -Pandroid.testInstrumentationRunnerArguments.class=fr.bubblebd.LocalizationTest -Pandroid.testInstrumentationRunnerArguments.localizationLanguage=en
BUBBLEBD_TEST_LANGUAGE=fr ./scripts/test-device.sh -Pandroid.testInstrumentationRunnerArguments.class=fr.bubblebd.LocalizationTest -Pandroid.testInstrumentationRunnerArguments.localizationLanguage=fr
```

Private regression images are deliberately absent. Some tests are skipped or cannot run without them. Public builds do not establish complete detection accuracy. The maintainer runs the full private regression suite when changing the detection engine.
