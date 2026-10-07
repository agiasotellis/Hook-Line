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

## 3. The in-app purchase

The app sells one product. Create it in Play Console → Monetize → Products → **One-time products**:

- Product ID: **`hookline_unlock`** (must match `Entitlement.PRODUCT_ID`)
- Name: "Hookline Unlock"; description: "Unlocks Hookline for good. One payment, no subscription."
- Price: e.g. US$19.99, then set regional prices (suggested: €14.99 euro area; lower in lower-income markets).
- Activate it.

The product can only be created after a first app bundle with the Play Billing library has been uploaded (any testing track).

How the app behaves:
- First launch starts a **7-day free trial** with everything enabled.
- After that, a paywall covers the map until the purchase; Storm Mode / rain alerts / the widget pause too.
- "Restore purchase" re-checks Google Play (purchases follow the Google account, across phones).
- Purchases are acknowledged immediately (Google refunds unacknowledged purchases after 3 days).
- Debug builds skip the paywall.

Testing purchases: add your Gmail as a **license tester** (Play Console → Settings → License testing). Testers get test cards that never charge.

## 4. Uploading

1. Play Console → your app → Testing → Internal testing → Create new release.
2. Upload `hookline-<tag>-play.aab`. Release notes: see `play/store-listing.md` ("What's new").
3. Review and roll out. Promote the same build to Closed testing, then Production when access is granted.
