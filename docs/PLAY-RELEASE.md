# Releasing Hookline on Google Play

How a build gets from this repo to Google Play. The owner-side steps (account, testing, listing) are in [play/LAUNCH-CHECKLIST.md](play/LAUNCH-CHECKLIST.md).

## 1. Signing (one-time setup)

Google Play uses **Play App Signing**: Google keeps the key that signs the app on users' phones, and you sign uploads with a separate **upload key**. If the upload key is ever lost, Google can reset it, so losing it isn't fatal.

An upload key (`hookline-upload.jks`, PKCS12, RSA 4096, alias `hookline-upload`, valid ~27 years) has been generated for you. Keep the file and its password in a password manager. **Never commit it to the repo.**

Add four repository secrets in GitHub (Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
|---|---|
| `HOOKLINE_KEYSTORE_BASE64` | the keystore file, base64-encoded (one line) |
| `HOOKLINE_KEYSTORE_PASSWORD` | keystore password |
| `HOOKLINE_KEY_ALIAS` | `hookline-upload` |
| `HOOKLINE_KEY_PASSWORD` | key password (same as the keystore password) |

## 2. Building

Every push to `main` runs **Test release** (`.github/workflows/release.yml`), which publishes on the Releases page:

- `hookline-<tag>-arm64-v8a.apk` (and other ABIs): **debug** builds for side-loading and testing. Paywall disabled.
- `hookline-<tag>-play.aab`: the **release app bundle**, R8-optimised and signed with the upload key. This is what you upload to Play Console.
  If it is named `-debugkey.aab`, the secrets above aren't set; Play will reject it.

Version numbers live in `app/build.gradle.kts` (`versionCode` must go up by at least 1 for every upload; `versionName` is what users see).

Locally (with the Android SDK): `./gradlew :app:bundleRelease` with the four `HOOKLINE_*` environment variables set (`HOOKLINE_KEYSTORE_FILE` pointing at the .jks instead of the base64 secret).

## 3. Price

Hookline is a **paid app**: users pay once in the Play Store before downloading. There is no trial, paywall, subscription or in-app purchase in the code.

- Set the price in Play Console → Monetize with Play → **App pricing** (needs a payments profile). Suggested: US$19.99 base, then review the regional prices Play converts to (e.g. €14.99 in the euro area).
- Google's rule: an app that has been offered for free can never become paid. Make sure the app is set to **Paid** before any release goes out.
- Refunds follow Google Play's refund policy; you can also refund any order yourself in Order management.
- Testing: **internal** testers install a paid app for free. Google's help says **closed** testers must buy it; check App pricing for a per-track price (some accounts can set the closed track to Free), otherwise refund your 12 testers in Order management after they buy.

## 4. Uploading

1. Play Console → your app → Testing → Internal testing → Create new release.
2. Upload `hookline-<tag>-play.aab`. Release notes: see `play/store-listing.md` ("What's new").
3. Review and roll out. Promote the same build to Closed testing, then Production when access is granted.
