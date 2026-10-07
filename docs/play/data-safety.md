# Play Console – Data safety form answers

App: **Hookline Radar** (`app.hookline.radar`), version 1.0.0 (versionCode 11), targetSdk 36.
Play Console path: **Policy and programs → App content → Data safety → Start**.

This document gives the answer to type or tick for each question, followed by the reasoning. It was checked against the code on 2026-10-07 (manifest, `app/src/main`, `core/data`). If the app changes (new SDK, new endpoint, analytics, crash service, accounts), redo this form.

---

## 0. What the app actually does with data (evidence)

| Fact | Where in the code |
|---|---|
| Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`. No background location, no foreground service, no exact alarm, no media permissions. | `app/src/main/AndroidManifest.xml` |
| Libraries that talk to the network: OkHttp (own requests), MapLibre Android 11.5.2 (map tiles, no telemetry), Play Billing 8.0.0. No Firebase, no Google Play services location, no analytics/ads/crash SDK. | `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| Location read only in the foreground: one-shot `LocationManager` fix on launch (coarse requested), precise requested only when the user taps the location/follow button; MapLibre location dot while the map is shown. | `map/DeviceLocation.kt`, `map/MapScreen.kt`, `map/BaseMap.kt` |
| The last fix is stored on the device as the Storm Mode "Current location" (`storm_mode` prefs `home_lat/home_lon`). | `MapViewModel.onLocation` → `AlertChecker.setHome` |
| Background: WorkManager every 15 min (`StormModeWorker`), Storm Mode on by default. Uses stored coordinates, never reads location. | `stormmode/StormModeWorker.kt`, `stormmode/AlertChecker.kt` |
| **Only request that contains coordinates:** `GET https://api.weather.gov/alerts/active?point=<lat>,<lon>` with 4 decimals, made by the background check for each US place with warnings on. | `NwsAlerts.activeAt`, called only from `AlertChecker.check` |
| Foreground NWS request is nationwide (`/alerts/active?event=...`), no location. | `MapViewModel` line ~1572 (`activeEvents`) |
| Map-area requests: OpenFreeMap vector tiles; NASA GIBS tiles (z/x/y); NOAA MRMS map service and EUMETSAT WMS (`bbox` of the tile). Radar requests name the radar station. MeteoAlarm requests name the country. | `BaseMap.kt`, `Imagery.kt`, `NexradSource.kt`, `OrdRadar.kt`, `DwdDualPol.kt`, `Warnings.kt` |
| Rain alerts download whole composites (OPERA RATE for Europe, MRMS CONUS) and compute each place's outlook on the device. No coordinates sent. | `RainSource.kt`, `Nowcast.kt`, `AlertChecker.checkRain` |
| Crash log written to app storage; only leaves the device if the user taps **Send report**, which opens the Android share sheet (`ACTION_SEND`, user picks the app and recipient). | `CrashLog.kt`, `MapScreen.kt` (CrashCard) |
| Sharing radar pictures/GIFs: share sheet via FileProvider (`cache/shared`). | `share/Sharer.kt` |
| Billing: one-time product `hookline_unlock`; app queries/acknowledges purchases through the Play Store app; purchase flag kept in local prefs. No developer server, no server-side verification. | `billing/Entitlement.kt` |
| All endpoints are `https://`. | grep of `core/data`, `app` |
| `android:allowBackup="true"` (Android Auto Backup to the user's Google account). | `AndroidManifest.xml` |

### Google's definitions used below

- **Collected** = "transmitting data from your app off a user's device". This includes data your app's own code or its libraries send to *any* server, not just yours. Not collected: data processed only on the device; data processed **ephemerally** ("only stored in memory and retained for no longer than necessary to service the specific request in real-time"); end-to-end encrypted data.
- **Shared** = transferring user data to a **third party**. Not sharing: transfers to a **service provider** (processes data on the developer's behalf and instructions); transfers for legal purposes; **user-initiated** transfers the user reasonably expects (e.g. the share sheet); fully anonymised data.
- **Payment data:** if the app never accesses it and the payment service (Google Play) collects it directly under its own terms, the developer does not declare it.
- **IP addresses:** declare according to how you use them (e.g. if used to determine location). Hookline does not use IP addresses at all.

---

## 1. Data collection and security (first page)

**Q: Does your app collect or share any of the required user data types?**
→ **Yes.**

Reason: when Storm Mode checks US warnings in the background, the app sends the coordinates of the user's "Current location" (a position obtained from the device) to api.weather.gov. That is location data leaving the device, and it is not ephemeral from our point of view because we cannot guarantee how a third-party server treats it. Map-area requests to tile servers also reveal roughly where the user is looking (often near where they are). Answering "No" would be defensible only if this request were removed or replaced by one with no coordinates (see *Judgement call A*).

**Q: Is all of the user data collected by your app encrypted in transit?**
→ **Yes.** Every endpoint is HTTPS (api.weather.gov, *.s3.amazonaws.com, s3.waw3-1.cloudferro.com, feeds.meteoalarm.org, opendata.dwd.de, gibs.earthdata.nasa.gov, view.eumetsat.int, mapservices.weather.noaa.gov, tiles.openfreemap.org; Google Play via the Play Store app).

**Q: Which of the following methods of account creation does your app support?** (shown on current forms)
→ **My app does not allow users to create an account.** (No login of any kind.)

**Q: Do you provide a way for users to request that their data is deleted?**
→ **No** (recommended), with the explanation below. *Judgement call B.*

Reason: the developer holds no user data, so there is nothing for us to delete; the location sent to the NWS is held by the NWS, which we cannot delete. All on-device data can be deleted by the user (remove places, clear storage, uninstall), which the privacy policy explains. Ticking "Yes" would make the store show "You can request that data be deleted", which would over-promise for the NWS copy. If you prefer "Yes", fill the optional "Delete data URL" with the privacy policy section 8 link and be ready to answer emails.

---

## 2. Data types (second page)

Tick only the types marked **Declare**.

### Location

| Data type | Answer |
|---|---|
| **Approximate location** | **Declare** |
| **Precise location** | **Declare** |

Reasoning: the app requests coarse location on launch and fine location when the user taps the location button. The stored position is sent with 4-decimal precision (~10 m) to api.weather.gov in the Storm Mode check. If the user granted only approximate location, what is sent is approximate; if precise, it is precise. Both types are therefore possible. Saved places are coordinates the user picked on the map; they are also sent to the NWS (US only). Google counts them as location if they represent the user's location ("user or device physical location"). They usually do (home, work).

Map-tile, radar-station and country requests also reveal approximate area, but they describe what is *viewed*, not the device, and do not change the answer since location is declared anyway.

### Personal info (Name, Email address, User IDs, Address, Phone number, Race and ethnicity, Political or religious beliefs, Sexual orientation, Other info)
→ **Do not declare.** The app has no account, no forms and no identifiers. Saved place names stay on the device. The email address that arrives with a crash report or support email is sent by the user's own email app, not by Hookline (see App info and performance).

### Financial info (User payment info, Purchase history, Credit score, Other financial info)
→ **Do not declare.** *Judgement call C.*
Payment is done entirely in Google Play's purchase sheet; the app never sees payment details, so per Google's guidance it is not declared. Purchase state is read on the device from the Play Store app (`queryPurchasesAsync`) and stored locally as a boolean; the app has no server and never sends purchase history anywhere. The acknowledgement call goes to Google Play, which is the payment service itself.
Conservative alternative: declare **Purchase history – Collected, not shared, required, purpose App functionality** (some reviewers expect this for any IAP app). It is harmless if you prefer it.

### Health and fitness, Messages, Photos and videos, Audio files, Files and docs, Calendar, Contacts
→ **Do not declare.** None accessed. The share feature creates new images from the map and passes them to the share sheet; the app reads no user photos or files.

### App activity (App interactions, In-app search history, Installed apps, Other user-generated content, Other actions)
→ **Do not declare.** No analytics, no logging of taps or searches off the device. Radar search runs against the bundled site catalogue on the device. Which radar or map area a user views is sent to the data servers only as the URL needed to fetch that data, i.e. processed ephemerally to serve the request, and is covered by the Location declaration in any case.

### Web browsing
→ **Do not declare.**

### App info and performance (Crash logs, Diagnostics, Other app performance data)
→ **Do not declare.** *Judgement call D.*
The crash log is written to app-private storage. Hookline never transmits it. When the user taps **Send report**, Android's share sheet opens and the *user* chooses an app and recipient and sends it from there. Under Google's definitions this is either not collection by the app at all (the app does not transmit it) or user-initiated sharing that the user reasonably expects.
Conservative alternative: declare **Crash logs – Collected, not shared, optional (users choose to send), purpose App functionality**. Choose this if you will *ask* users to email reports to you (e.g. support page says "send us the crash report"), since you then receive them by design.

### Device or other IDs
→ **Do not declare.** The app does not read Android ID, advertising ID, IMEI or any identifier and does not create one. (Advertising ID declaration: **No**, see declarations.md.)

---

## 3. Per-type questions (for Approximate location and Precise location)

Play asks the same four questions for each declared type. Answers are identical for both.

**Is this data collected, shared, or both?**
→ **Collected** and **Shared**. *Judgement call A.*

- Collected: yes, it is transmitted off the device (to api.weather.gov).
- Shared: the receiver is a third party (NOAA/NWS). None of the exemptions clearly applies: NWS is not our service provider (it does not process data on our behalf or under our instructions), and the background check is automatic rather than a specific user action. So the honest answer is **Shared**. The store listing will then show "This app may share these data types with third parties: Location", which is accurate.
- If you think the stricter reading is wrong, the alternative is "Collected" only, arguing that a request to a public data API is processing to serve the user's own request. We don't recommend relying on that.

**Is this data processed ephemerally?**
→ **No.** (By Hookline it is not stored off device at all, but we can't speak for the NWS servers' logs. "Yes" would also be defensible because *we* never retain it; "No" is the safer answer.)

**Is this data required for your app, or can users choose whether it's collected?**
→ **Users can choose whether this data is collected (optional).**
Location permission can be denied (the app still works; you navigate the map yourself), Storm Mode can be switched off, and warnings can be turned off per place. Nothing is sent if any of these is off.

**Why is this user data collected?** (tick)
→ **App functionality** only.
Not Analytics, not Developer communications, not Advertising or marketing, not Fraud prevention, not Personalization, not Account management.

**Why is this user data shared?** (tick)
→ **App functionality** only.

---

## 4. Preview of what the store will show

- **Data shared with third parties:** Location (approximate location, precise location) – App functionality.
- **Data collected:** Location (approximate location, precise location) – App functionality, optional.
- **Security practices:** Data is encrypted in transit. (No "you can request deletion" badge if you answer No.)

---

## 5. Judgement calls for the owner to confirm

| | Question | Recommended | Alternative |
|---|---|---|---|
| **A** | Location: shared? | Collected **and shared** (NWS is a third party, background request is automatic) | Collected only. Or change the app so it sends rounded coordinates (e.g. 2 decimals, ≈1 km) or uses NWS zone/county codes from a bundled lookup; that does not remove the declaration but reduces precision. Removing the `?point=` request altogether (e.g. filtering the nationwide list on the device, as the foreground code already does) would let you answer **No data collected**. |
| **B** | Deletion request mechanism | **No** (we hold nothing; NWS copy is outside our control) | Yes + privacy policy link |
| **C** | Purchase history | Not declared (Play Billing collects it directly; app has no server) | Declare Purchase history: collected, required, App functionality |
| **D** | Crash logs | Not declared (only sent by the user via the share sheet) | Declare Crash logs: collected, optional, App functionality |
| **E** | Android Auto Backup (`allowBackup="true"`) | Not declared: the backup goes to the user's own Google account; on Android 9+ with a screen lock it is end-to-end encrypted, which Google excludes from "collection" | Set `android:allowBackup="false"` (or exclude `places`/`storm_mode` prefs with backup rules) if you want no copy of saved places off the device at all |

Also make sure the **privacy policy** (docs/privacy) and these answers stay in sync. Play checks that the Data safety section is consistent with the privacy policy and app behaviour.

## Sources

- Provide information for Google Play's Data safety section (definitions of collection, sharing, ephemeral processing, service providers, user-initiated sharing, data types, IP addresses, payment services): https://support.google.com/googleplay/android-developer/answer/10787469
- User Data policy (privacy policy and prominent disclosure requirements): https://support.google.com/googleplay/android-developer/answer/10144311
