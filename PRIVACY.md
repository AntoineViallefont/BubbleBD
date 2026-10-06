# Data and privacy

**English · [Français](PRIVACY.fr.md)**

Updated 6 October 2026 — BubbleBD 0.3.33.

BubbleBD is a volunteer project maintained by Antoine Viallefont. It requires no BubbleBD account and contains no advertising or analytics SDK. General questions can go to the [project issues](https://github.com/AntoineViallefont/BubbleBD/issues), which are public: do not include personal information. Report vulnerabilities privately through the Security tab.

## Reading and your library

Panel detection runs on your device using a bundled model. Your library, progress, preferences, book records and edits stay in the app's storage. Reading a locally available book does not require the Mistral service.

Your selected files remain under your control. Removing a book from BubbleBD removes its library entry and private copies, not the original. Uninstalling erases the app's local data: back up anything important first. There is no library synchronisation between devices.

## Looking up book information

When importing a new book or explicitly requesting a lookup, BubbleBD may query public catalogues and websites, then a shared service hosted on Cloudflare that uses Mistral. Locked records are excluded from searches. A previously requested search may continue in the background.

Depending on the clues available, searches send the title, series, volume, ISBN and potentially authors, publisher, publication date, genre, a summary excerpt, source URLs, text extracted from the cover and a JPEG cover thumbnail. Full comics and reading progress are not sent to the metadata service. Panel detection does not send comic pages.

Contacted services receive the information needed for the request and technical connection information, including the IP address of the party contacting them. Remote images may be downloaded to display covers.

The shared service stores found records and their sources for equivalent future searches. Positive records currently have no automatic expiration; negative results are cached for one day. Submitted covers are not stored in D1. Personal record edits are not synchronised as community contributions. A manually entered value may, however, be used as a clue in a new search: do not enter personal information in book fields.

Application-level service logs are disabled. This does not guarantee that Cloudflare, Mistral, Microsoft or queried catalogues keep no logs or other data. Their own terms apply. The Mistral account's training opt-out status has not been verified; the free plan may use inputs and outputs to improve models. Do not use book-information lookup with confidential material.

- [Mistral privacy policy](https://legal.mistral.ai/terms/privacy-policy)
- [Mistral data use](https://help.mistral.ai/en/articles/347617-do-you-use-my-user-data-to-train-your-artificial-intelligence-models)
- [Cloudflare privacy policy](https://www.cloudflare.com/privacypolicy/)
- [Microsoft privacy statement](https://privacy.microsoft.com/privacystatement)

Available catalogues and their addresses can be found in the code and in book-record sources. Network availability, quotas and accuracy remain beta limitations. Mistral lookup is free for readers, but is not guaranteed to be unlimited.

## Optional cloud access

OneDrive sign-in goes through Microsoft with your permission. It lets the app browse authorised files and download books for reading; it does not create a BubbleBD account. Tokens stay in the app's private storage. You can revoke access through your Microsoft account.

Other storage providers may appear in Android's folder picker. Availability depends on the installed provider and whether it exposes selectable folders. Google Drive folder access has not been verified on a connected account; BubbleBD has no dedicated Google Drive integration. The chosen provider's own data handling applies.

## GitHub and volunteers

GitHub hosts the public code, issues and volunteer forms. Your username and contributions are visible to others. No Google email address is needed to volunteer now. Do not attach full comics, credentials or private documents. If a Google Play test is arranged, an appropriate enrolment method will be announced separately. This repository does not collect a list of Google email addresses.

To avoid the shared service in a personal build, leave `metadata.endpoint` empty. This does not disable direct catalogue queries. Offline reading uses books already available locally.
