# Play Console – App content declarations

App: **Hookline Radar** (`app.hookline.radar`), 1.0.0, targetSdk 36. Checked against `app/src/main/AndroidManifest.xml` and the code on 2026-10-07.
Play Console path: **Policy and programs → App content**. Every item below must show "Completed" before you can send the first release for review.

Permissions in the app's own manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`. Libraries merge in a few more (Play Billing adds `com.android.vending.BILLING`; WorkManager adds things like `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED`). **Before the first upload, check the merged list**: Android Studio → `AndroidManifest.xml` → *Merged Manifest* tab, or after uploading, Play Console → *App bundle explorer* → *Permissions*. The answers below assume no `ACCESS_BACKGROUND_LOCATION`, no `FOREGROUND_SERVICE_*` type permission, no `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, no `USE_FULL_SCREEN_INTENT`, no `READ_MEDIA_*` and no `AD_ID` appear there. If one does, the matching declaration below changes.

---

## Privacy policy
- **Privacy policy URL:** `[PRIVACY POLICY URL]`, e.g. `https://[your-github-user].github.io/[repo]/privacy/` (see LAUNCH-CHECKLIST.md, step 9).
- The same URL must be reachable from inside the app. *To do (app change, not done here):* add a "Privacy policy" link to the About sheet (`MenuSheets.kt`, `AboutSheet`). Google's User Data policy requires the link both in Play Console and in the app.

## App access
- **Answer:** *All functionality in my app is available without any special access.*
- Hookline Radar is a **paid app** (bought once in the Play Store). There is no login, account, trial or paywall: everything works after install. Leave the instructions empty.

## Ads
- **Does your app contain ads?** → **No.** No ad SDK, no house ads, no sponsored content.

## Advertising ID
- **Does your app use advertising ID?** → **No.** (Required question for apps targeting Android 13+. Confirm `com.google.android.gms.permission.AD_ID` is absent from the merged manifest. No Google Play services ads/analytics library is included.)

## Content ratings
- See `content-rating.md`. Category: All Other App Types. Expected: Everyone / PEGI 3 / USK 0 / IARC 3+.

## Target audience and content
- **Target age groups:** tick **13–15, 16–17, 18 and over**. Do **not** tick 5 and under, 6–8 or 9–12.
- **Could your store listing unintentionally appeal to children?** → **No.**
- **Reasoning:**
  - The app is a technical weather radar for adults and teenagers (storm spotters, chasers, pilots, outdoor workers, weather enthusiasts). Its vocabulary (reflectivity, velocity, dual-pol, cross-sections) and its paid unlock are not designed for children.
  - Including any group under 13 brings the app under the **Families policy** (stricter rules on location, SDKs and design review). Hookline makes no effort to suit young children, so it should not opt in.
  - 13+ is preferable to 18+ only: teenagers are a real part of the weather-enthusiast audience, the app has no mature content, no ads and no user-to-user contact, so there is no reason to exclude them. Purchases by teens go through Google Play's family/parental controls.
  - The listing graphics are radar maps and text; no cartoon characters or child-oriented themes.

## News apps
- **Is your app a news app?** → **No.** It shows data feeds and official warning text, not news articles or editorial content.

## Health apps
- Required for **all** apps. → Select **"My app doesn't provide any health features"**.

## Financial features
- Required for all apps. → Select **"My app doesn't provide any financial features"**. (Selling the app itself is not a financial feature.)

## Government apps
- **Is your app developed by or on behalf of a government?** → **No.**
- **But Hookline presents government information** (NWS/NOAA warnings and radar, national weather services via MeteoAlarm/OPERA, DWD, NASA, EUMETSAT). Google's *Requirements for apps that communicate government information* ask non-government apps to:
  1. **show the information sources clearly** in the store description and the app, and
  2. **make clear that the app does not represent a government or political entity**.
  Google's Misleading Claims policy also forbids apps that "falsely claim affiliation with a government entity".
- **What is already compliant in the app:** the About sheet (`MenuSheets.kt`) has a "Not an official warning service" section, lists every source with its domain (`api.weather.gov`, `mapservices.weather.noaa.gov`, NOAA Open Data on AWS, EUMETNET OPERA, MeteoAlarm, DWD, NASA GIBS, EUMETSAT, OpenFreeMap) and says "Hookline is not affiliated with or endorsed by any of these organisations." The warnings sheet says "Hookline is not an official warning service; always follow local authorities."
- **What the store listing does:** the full description (store-listing.md) opens with the disclaimer and lists the sources with their official URLs (`weather.gov`, `noaa.gov`, `meteoalarm.org`, `dwd.de`, `eumetnet.eu`, `earthdata.nasa.gov`, `eumetsat.int`), which lets users verify the information.
- **Recommended small app change (not done here):** in the About sheet, change the affiliation sentence to the more explicit *"Hookline is an independent app. It does not represent, and is not affiliated with or endorsed by, any government, government agency or these organisations."* and add `https://www.weather.gov` and `https://www.meteoalarm.org` as source links. The current text very likely passes; this makes it unambiguous.
- Do **not** use "NOAA", "NWS", "official" or government logos in the app name, icon, short description or screenshots in a way that suggests endorsement. The listing drafts avoid this.

## Foreground service permissions
- **None.** `StormModeService` (foreground service type `location`) and the `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` permissions were removed from the manifest in this release. Background checks run via WorkManager periodic work (15 min), which needs no FGS declaration.
- If the merged manifest still shows `android.permission.FOREGROUND_SERVICE` (added by WorkManager for its optional foreground worker support), Play only asks for a declaration when a `FOREGROUND_SERVICE_<TYPE>` permission or a typed `<service>` is present. If Play Console nevertheless shows the *Foreground service permissions* form, answer that the app does not use foreground services, or remove the merged permission with `tools:node="remove"`.

## Location permissions
- **Foreground only.** The manifest has `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`, but **not** `ACCESS_BACKGROUND_LOCATION`, and there is no location foreground service.
- **No background location declaration is needed.** Google's policy defines background location access as the app *accessing location* after the user closes it or goes to the home screen, and the Permissions Declaration Form is required when the app declares `ACCESS_BACKGROUND_LOCATION` (Android 10+). Hookline reads location only while visible (`DeviceLocation.request` from `MapScreen`; MapLibre's location dot while the map is on screen).
- **Grey area to be aware of:** the 15-minute Storm Mode worker sends the **last stored** "Current location" coordinates to api.weather.gov when the app is closed. It does not call any location API, so it is not "background location access" under the policy. However, Google's User Data policy requires a **prominent in-app disclosure** when data is used in a way users might not expect. The Storm Mode sheet already says "Checks the official warnings for your location every 15 minutes in the background", but it is only seen if the user opens that sheet, and Storm Mode is **on by default**.
  - **Recommended app change (not done here):** show one short screen before the first location prompt, e.g.
    > *"Hookline uses your location while the app is open, to centre the map and pick the nearest radar. It also remembers your last location so Storm Mode can check official warnings for it every 15 minutes, even when the app is closed. Your location is sent only to the weather service that provides the warnings (in the US, the National Weather Service). You can turn Storm Mode off in the menu."*
    with buttons *Continue* (then the system prompt) and *Not now*. It must contain the word "location", say it is used when the app is closed, and appear before the permission request.
- Location is not used for ads or analytics.

## Notifications (POST_NOTIFICATIONS)
- No Play Console declaration exists for notifications. The runtime permission is requested on Android 13+ when the user switches Storm Mode on in its sheet. Channels: "Warnings for your places" (high), "Rain starting soon" (default), "Storm Mode status" (low).
- Note: Storm Mode defaults to *on*, but on Android 13+ no notification appears until the user grants the permission. Consider also asking once after onboarding so users who never open the Storm Mode sheet still get warnings. (App change, optional.)
- Notifications are used only for warnings and rain alerts for the user's places, never for promotion, in line with Google's spam/notification rules.

## Exact alarms
- **None.** Neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM` is in the manifest; scheduling uses WorkManager. No declaration needed.

## Full-screen intent
- **None.** `USE_FULL_SCREEN_INTENT` is not declared; warning notifications use high priority and the alarm category, not full-screen intents.

## Photo and video permissions
- **None.** No `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` or `READ_EXTERNAL_STORAGE`. Sharing writes the app's own images to its cache and hands a `content://` URI from its `FileProvider` (`cache/shared`) to the share sheet. No declaration needed.

## Account deletion
- Not applicable: the app has no accounts.

## Data safety
- See `data-safety.md`.

## "Misleading claims" and weather-specific risks
Google's Misleading Claims / Deceptive Behavior policy rejects apps with false or misleading functionality claims, implied government affiliation, or that put users at risk with inaccurate information presented as authoritative. For a radar/warning app:
- **Never claim to be official, life-saving or guaranteed.** The listing says what the app displays and where the data comes from, and repeats that it is not an official warning service.
- **Describe limits honestly.** Rain alerts are short-range extrapolation and "can't foresee storms that haven't formed yet" (in-app guide); background checks run about every 15 minutes, not instantly; data can be delayed or wrong; dual-pol in Europe is Germany only; the archive is NEXRAD since 2008 (US only); satellite is infrared imagery.
- **Coverage claims must match the code:** 291 radars (155 NEXRAD + 136 OPERA); US and Europe for radar; MeteoAlarm warnings for 38 countries; lightning in the Americas and Europe/Africa.
- **Pricing claims:** "paid once, no subscription, no in-app purchases". The price is set per country in Play; don't hard-code a price in localized listings.
- **README check:** the README's first line says "thirty years of archive"; the app offers the NEXRAD archive since 2008 (≈18 years). Don't copy "thirty years" into store text. (README not edited here.)
- **Avoid naming competitors** in the listing (Google's metadata policy disallows using other apps' names to attract users).
- **Screenshots must show the real app.** The images in `docs/img/` are labelled as procedurally generated mockups; do **not** use them as store screenshots, or Google may treat the listing as misleading.

## Sources

- Requirements for apps that communicate government information: https://support.google.com/googleplay/android-developer/answer/9514050
- Misleading Claims (Deceptive Behavior policy): https://support.google.com/googleplay/android-developer/answer/9888077
- User Data policy (privacy policy, prominent disclosure): https://support.google.com/googleplay/android-developer/answer/10144311
- Background location access / Permissions Declaration Form: https://support.google.com/googleplay/android-developer/answer/9799150
- Health apps declaration (required for all apps): https://support.google.com/googleplay/android-developer/answer/14738291
- App content page: https://support.google.com/googleplay/android-developer/answer/9859455
- Target API level requirements (Android 16 / API 36 from 31 Aug 2026): https://developer.android.com/google/play/requirements/target-sdk
