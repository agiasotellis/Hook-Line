# Hookline Radar – Google Play launch checklist

For: individual developer, app `app.hookline.radar`, version 1.0.0 (versionCode 11), free download + 7-day trial + one-time in-app product `hookline_unlock`.
Written 2026-10-07. Policy numbers checked that day; links at the end. Tick each box as you go.

Companion documents in this folder: `store-listing.md`, `data-safety.md`, `content-rating.md`, `declarations.md`; privacy policy in `docs/privacy/`; signing and upload in `docs/PLAY-RELEASE.md`.

Allow roughly **3–5 weeks** from account creation to production: identity checks (days), a closed test of **14 days minimum**, then production access review (usually up to 7 days) and the first app review.

---

## 0. Before you start (app-side to-dos found while drafting)

These are code or repo changes; none were made by this paperwork pass.

- [ ] **Privacy policy link inside the app** (About sheet). Required by Google's User Data policy in addition to the Play Console link.
- [ ] **Location disclosure screen** before the first location prompt, explaining that the last location is used by Storm Mode in the background (wording in `declarations.md` → Location permissions). Storm Mode is on by default.
- [ ] Optional: ask for the notification permission once after onboarding (otherwise Android 13+ users who never open the Storm Mode sheet get no warnings).
- [ ] Optional, privacy: round the coordinates sent to `api.weather.gov/alerts/active?point=` (currently 4 decimals ≈ 10 m) to 2–3 decimals, or filter warnings on the device so that no coordinates leave the phone (see `data-safety.md`, judgement call A).
- [ ] About sheet affiliation sentence: make it explicit "does not represent any government" (see `declarations.md` → Government apps).
- [ ] **NWS User-Agent** (`app/build.gradle.kts`): `HooklineRadar/0.1 (support@hookline.app)`. Make sure you own and read that address (or change it to `[YOUR CONTACT EMAIL]`), and bump the version string. NWS uses it to contact you if the app misbehaves.
- [ ] Greek and German in-app translations actually ship in 1.0.0 (the listings say so).
- [ ] README: "Requirements: … Android SDK 35" → 36; "thirty years of archive" → archive since 2008 (store text already uses 2008).
- [ ] Release build (not debug) tested on a real device: `BuildConfig.DEBUG` unlocks everything, so the trial and paywall only behave correctly in release builds.

## 1. Create the Google Play developer account

- [ ] Go to https://play.google.com/console/signup with the Google account you will keep long-term (consider a dedicated account, not your personal Gmail).
- [ ] Account type: **Personal** (you are an individual). An **Organization** account needs a free D-U-N-S number and suits a registered business; both can sell apps and in-app products. Personal accounts are subject to the testing requirement in step 6. If you form a company later you can't simply convert; you would transfer the app to a new organisation account.
- [ ] Pay the **US$25 one-time registration fee**.
- [ ] Identity verification: legal name and address (through a Google payments profile) and an **official government ID**. Verify the developer **email** (6-digit code) and **phone** (SMS/voice).
- [ ] **Device verification**: on a non-rooted physical Android phone running Android 10+, install the Play Console app, scan the QR code from Play Console *Home* and tap *Verify*.
- [ ] What becomes public: your **developer name**, **contact email** and, for accounts that sell (you will), your **legal name and address** from the payments profile; in the EU, trader details under the Digital Services Act (name, address, phone, email). Decide now what address/phone you are willing to publish.
- [ ] Android developer verification (new 2026 programme for all installs on certified devices, starting in Brazil, Indonesia, Singapore and Thailand on 30 Sep 2026, global in 2027): apps on Play are registered automatically in most cases. If you keep publishing test APKs on GitHub Releases, check in Play Console that `app.hookline.radar` is registered for **outside-Play distribution** too, and note that the GitHub APKs must be signed with a registered key.

## 2. Payments profile, merchant account and tax

- [ ] Play Console → **Settings → Payments profile**: create/link it and **set up a merchant account** (needed to sell the in-app product). Add a bank account for payouts.
- [ ] Tax information: fill the US tax form (non-US individuals usually **W-8BEN**; check the treaty rate for your country). In the EU/EEA, Google collects and remits VAT on your sales.
- [ ] Service fee: Google's fee on the first US$1M of yearly earnings is 15% for developers in the 15% tier. Check in Play Console whether you need to join it (account group set-up under *Settings*); otherwise the standard rate applies.

## 3. Create the app

- [ ] Play Console → **Create app**
  - App name: **Hookline Radar**
  - Default language: **English (United States) – en-US**
  - App or game: **App**
  - Free or paid: **Free** (the app is free to download; the unlock is an in-app product. A free app can't be changed to paid later, which is fine here.)
  - Accept the Developer Program Policies and US export laws declarations.
- [ ] **Store settings**: category **Weather**, tags, contact email (see `store-listing.md` §4).

## 4. App signing and the first bundle

- [ ] Follow **`docs/PLAY-RELEASE.md`**: create the upload key (keystore), store it and its passwords safely (back it up in two places), and configure the CI secrets `HOOKLINE_KEYSTORE_FILE`, `HOOKLINE_KEYSTORE_PASSWORD`, `HOOKLINE_KEY_ALIAS`, `HOOKLINE_KEY_PASSWORD` that `app/build.gradle.kts` reads.
- [ ] Use **Play App Signing** (default for new apps): Google holds the app signing key; you sign uploads with the upload key. If the upload key is ever lost it can be reset via Play support; the app signing key never leaves Google.
- [ ] Build an **Android App Bundle**: `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`. (The ABI `splits` block only affects APKs; the bundle is split by Play.) Check it was signed with the upload key, not the debug key.
- [ ] **Target API**: the app targets **36** (Android 16). From **31 Aug 2026**, new apps and updates must target API 36 or higher; extension requests run to 1 Nov 2026. You're compliant.
- [ ] Every upload needs a higher `versionCode` (currently 11).

## 5. Internal testing (first upload, billing setup, license testers)

- [ ] **Testing → Internal testing → Create new release**, upload the AAB, release notes from `store-listing.md`, roll out. Add yourself (and anyone helping) as internal testers via an email list; open the opt-in link on your phone and install from Play.
- [ ] After upload, check **App bundle explorer → Permissions**: expect INTERNET, ACCESS_NETWORK_STATE, ACCESS_COARSE/FINE_LOCATION, POST_NOTIFICATIONS, com.android.vending.BILLING and WorkManager's own (e.g. WAKE_LOCK, RECEIVE_BOOT_COMPLETED, possibly FOREGROUND_SERVICE). There must be **no** ACCESS_BACKGROUND_LOCATION, FOREGROUND_SERVICE_LOCATION, exact-alarm, full-screen-intent, READ_MEDIA_* or AD_ID. If something unexpected appears, fix the manifest before going further (see `declarations.md`).
- [ ] Check the **pre-launch report** (automatic for testing tracks) for crashes and accessibility warnings.
- [ ] **Create the in-app product** (possible once a build with the BILLING permission is uploaded and the merchant account exists): **Monetize with Play → Products → One-time products → Create**
  - Product ID: **`hookline_unlock`** (must match `Entitlement.PRODUCT_ID`; can't be changed or reused later)
  - Name: *Hookline unlock* (max 55 chars). Description (max 200): *Unlocks every Hookline Radar feature permanently. One payment, no subscription.*
  - Purchase option: a single **Buy** option, not rentable, quantity 1.
  - Price: **US$19.99** as the base, then let Play **convert to all countries** (or use a pricing template) and review the regional prices (e.g. €/£ round numbers like 19.99, lower prices in lower-income markets if you want).
  - Tax category: digital app/software default.
  - **Activate** the product.
- [ ] **License testers**: **Settings (account level) → License testing** → add the Gmail accounts of you and your testers, response *RESPOND_NORMALLY*. They buy with test cards ("Test instrument, always approves", "…declines", slow cards) and are not charged. Test on a release build installed from the internal track:
  - [ ] fresh install shows 7 days left; buying unlocks; the purchase is acknowledged (otherwise Play refunds it after 3 days, or 3 minutes for test purchases);
  - [ ] **Restore purchase** after reinstall or on a second phone with the same account;
  - [ ] pending payment ("slow test card") message;
  - [ ] refund in *Order management*, then tap **Restore purchase**: the app re-locks once the trial is over (the code only clears the unlock on a manual Restore that finds no purchase);
  - [ ] after the trial (change the device date forward 8 days, or wait), the paywall appears and Storm Mode stops notifying until unlocked.

## 6. Closed testing (required for new personal accounts)

New **personal** accounts created after 13 Nov 2023 must run a **closed test with at least 12 testers who stay opted in continuously for at least 14 days** before applying for production. Testers who opt out before 14 days don't count.

- [ ] **Testing → Closed testing → Create track** (or use "Alpha"), upload the same AAB (or a newer one), add testers by **email list** or a **Google Group**. Recruit **15–20 people** so drop-outs don't push you below 12.
- [ ] Share the opt-in link; each tester must **accept** and **install from Play**, and keep the app installed. Ask them to actually use it a few times a week; Google asks about engagement.
- [ ] Make testers license testers too (step 5) if you want them to test the purchase for free; otherwise their trial simply runs out, which is also a useful test of the paywall.
- [ ] Collect feedback (Play's private feedback, email, a form). Ship at least one update during the test that fixes something testers reported; it helps the application.
- [ ] Keep notes: number of testers, what they tested, what you changed. You'll need them.
- [ ] After **14 full days with ≥12 opted-in testers**, Play Console *Dashboard* → **Apply for production**. The form asks about your closed test (how you recruited, engagement, feedback summary), the app (audience, value, expected installs) and production readiness (what you changed). Review usually takes **7 days or less**.

## 7. Store listing

- [ ] **Main store listing**: app name, short and full description (en-US) from `store-listing.md`; then **Manage translations** → add **Greek (el-GR)** and **German (de-DE)** texts.
- [ ] Graphics: 512×512 icon, 1024×500 feature graphic, **4–8 real phone screenshots at 1080×1920** (not the mockups in `docs/img/`), optional tablet screenshots and YouTube video. Specs in `store-listing.md` §5.
- [ ] Release notes ("What's new") for 1.0.0 in all three languages.

## 8. App content (Policy and programs → App content)

- [ ] **Privacy policy** URL (step 9).
- [ ] **App access**: instructions about the 7-day trial (text in `declarations.md`).
- [ ] **Ads**: No. **Advertising ID**: No.
- [ ] **Content rating**: questionnaire per `content-rating.md` (All Other App Types → Everyone / PEGI 3 / USK 0, In-App Purchases).
- [ ] **Target audience**: 13–15, 16–17, 18+; not appealing to children.
- [ ] **Data safety**: per `data-safety.md` (confirm judgement calls A–E first).
- [ ] **News app**: No. **Health apps**: "My app doesn't provide any health features". **Financial features**: none. **Government apps**: No.
- [ ] Nothing to declare for foreground services, background location, exact alarms, full-screen intents or photo/video permissions, if the permission check in step 5 is clean.
- [ ] **EU trader status** (Digital Services Act): declare as a trader if you sell to EU users (you do, via the unlock); provide the contact details to publish.

## 9. Host the privacy policy

The page must be on a public, non-geofenced URL, as HTML (not PDF), not editable by others, and titled as a privacy policy. `docs/privacy/index.html` is self-contained (inline CSS, no external assets).

- [ ] Fill every `[PLACEHOLDER]` in **both** `docs/privacy/PRIVACY.md` and `docs/privacy/index.html` (name, address, email, effective date, retention period, your data protection authority). Keep the two in sync.
- [ ] Choose hosting:
  - **GitHub Pages from `/docs`** (repo Settings → Pages → *Deploy from a branch* → `main` / `/docs`). URL: `https://agiasotellis.github.io/couplet-radar/privacy/`. Caveats: (1) Pages on a **private** repo needs a paid GitHub plan; (2) publishing `/docs` makes **everything in `docs/` public**, including the competitive analysis, product spec and these Play notes. Make sure you're happy with that.
  - **Recommended:** a separate small **public** repo (e.g. `hookline-site`) containing only `privacy/index.html` (and later a support page), served with GitHub Pages: `https://[user].github.io/hookline-site/privacy/`. Or a custom domain (e.g. `hookline.app/privacy`) if you own it.
  - Avoid "raw" GitHub URLs (`raw.githubusercontent.com` serves the HTML as plain text) and `github.com/.../blob/...` pages of a private repo (they 404 for the public).
- [ ] Open the URL in a private browser window on your phone to confirm it loads without login.
- [ ] Put the same URL in the app's About sheet (step 0) and in Play Console.

## 10. Trademark and name check for "Hookline"

Do this **before** spending on graphics or marketing; renaming after launch is painful (the package name `app.hookline.radar` can never change on Play).

- [ ] **USPTO** (US): https://tsdr.uspto.gov/ → search serial **86757729** (HOOKLINE, class 9, CRM software). Check whether it is **live, abandoned or cancelled** and who owns it. Also run a full search at https://tmsearch.uspto.gov/ for "HOOKLINE" and close variants in **class 9** (software, apps) and **class 42** (software services).
- [ ] **EUIPO** (EU trade marks): https://euipo.europa.eu/eSearch/ → "HOOKLINE", classes 9 and 42.
- [ ] **TMview** (EU national offices, incl. Greece and Germany, plus many others): https://www.tmdn.org/tmview/
- [ ] **WIPO Global Brand Database** (international registrations): https://branddb.wipo.int/
- [ ] Search **Google Play** and the web for apps or companies named "Hookline" (it is also a fishing term; fishing apps/brands are likely). A same-name app in a different field is lower risk, but similar names in weather/software are a problem.
- [ ] If anything live and close turns up in class 9/42, get advice or consider a variant. If clear, consider filing your own mark (EUIPO, or the national office in your country; class 9 + 42) once revenue justifies it.

## 11. Production release

- [ ] **Production → Countries/regions**: start with the regions the radar covers (United States and its territories, Canada, the EU/EEA, the UK, Switzerland, Norway, and other European countries in OPERA/MeteoAlarm). The satellite layer works elsewhere, but the app's value is US + Europe; adding other countries invites refunds and bad reviews.
- [ ] **Create release**: promote the tested bundle from closed testing (or upload a new one), add release notes, use a **staged rollout** (e.g. 20% → 50% → 100%) to catch crashes.
- [ ] **Send for review** from *Publishing overview*. First reviews can take several days.
- [ ] After approval: check the live listing in all three languages, buy the unlock once with a real card to verify the full flow (refund yourself afterwards in Order management), and watch **Android vitals** (crash/ANR rates) and reviews for the first week.

## 12. After launch

- [ ] Every update: bump `versionCode`/`versionName`, keep target API current (Google raises it every August), update Data safety and the privacy policy **before** shipping any change in data handling (new endpoint with coordinates, crash SDK, accounts, analytics).
- [ ] Answer reviews and support email; keep the support address in the NWS User-Agent valid.

## Sources

- Testing requirements for new personal developer accounts (12 testers, 14 days, production access application): https://support.google.com/googleplay/android-developer/answer/14151465
- Play Console registration (US$25 fee, account types): https://support.google.com/googleplay/android-developer/answer/6112435
- Required information and verification for developer accounts (ID, email/phone, public details): https://support.google.com/googleplay/android-developer/answer/10841920
- Android device verification for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14316361
- Choose a developer account type (personal vs organisation, D-U-N-S): https://support.google.com/googleplay/android-developer/answer/13634885
- Android developer verification (2026 timeline, Play auto-registration): https://developer.android.com/developer-verification
- Target API level requirements (API 36 from 31 Aug 2026): https://developer.android.com/google/play/requirements/target-sdk
- Create in-app (one-time) products: https://support.google.com/googleplay/android-developer/answer/1153481
- Test Google Play Billing (license testers, test cards): https://developer.android.com/google/play/billing/test
- User Data policy (privacy policy URL requirements): https://support.google.com/googleplay/android-developer/answer/10144311
- Data safety section: https://support.google.com/googleplay/android-developer/answer/10787469
- Health apps declaration (all apps): https://support.google.com/googleplay/android-developer/answer/14738291
- Store listing assets: https://support.google.com/googleplay/android-developer/answer/9866151
