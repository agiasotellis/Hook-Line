# Hookline Radar – Privacy Policy

**Effective date:** [EFFECTIVE DATE, e.g. 7 October 2026]

This privacy policy explains how the Android app **Hookline Radar** (package name `app.hookline.radar`, shown on Google Play as "Hookline Radar" and on your phone as "Hookline") handles information. Hookline is a weather radar app that shows public radar, satellite, lightning and warning data.

The short version: **Hookline has no account, no ads, no analytics and no server of its own. The developer does not receive your location or any other information about you.** To show weather data, the app downloads it directly from public data services. Those services receive ordinary internet requests from your phone, and some of those requests contain a map area or, for US warning checks, a place's coordinates. The details are below.

## 1. Who we are

Hookline is developed and published by an individual developer:

- **Name:** [YOUR FULL LEGAL NAME]
- **Address:** [YOUR POSTAL ADDRESS, or city and country]
- **Email for privacy questions:** [YOUR CONTACT EMAIL]

In this policy "we" and "the developer" mean this person. For the purposes of the EU and UK General Data Protection Regulation (GDPR), the developer is the controller of the small amount of personal data described in section 5 (messages you choose to send us).

## 2. What the developer collects

**Nothing automatically.** Hookline does not contain analytics, advertising or tracking software, does not use Firebase or any crash-reporting service, does not create advertising or device identifiers, and does not send anything to a server run by the developer (there is none).

The developer only receives information if **you** choose to send it, for example:

- **Crash reports.** If Hookline closes unexpectedly, a technical report is saved on your phone. The next time you open the app it offers to send it. Nothing is sent unless you tap **Send report**. That opens Android's share sheet, and you choose how to send it (for example your email app) and can read or edit it first. The report contains the app version, the Android version, the phone manufacturer and model, the time of the crash and a technical stack trace. It does not contain your location, your saved places or any account information. If you send it by email, we also receive your email address and anything else you write.
- **Support emails** you send to [YOUR CONTACT EMAIL].

## 3. Information stored only on your phone

The following stays on your device. The developer cannot see it.

| What | Why | How long |
|---|---|---|
| Your location (last known position) | To centre the map, pick the nearest radar, show the "your location" dot, measure storm arrival times, and set the "Current location" place used by Storm Mode | Replaced each time the app gets a new position; removed if you uninstall or clear the app's data |
| Saved places (the name you give and the coordinates) | Storm Mode warnings and "rain starting soon" alerts for those places | Until you remove the place or uninstall |
| Settings (units, layers, map position, Storm Mode on/off, warning level, Wi-Fi-only rain checks, night vision, etc.) | To remember your choices | Until you change them or uninstall |
| Rain outlook and small radar grids around your places | To detect rain moving towards a place and draw the widget | Overwritten at each check |
| IDs of warnings already notified (the last 300) | So each warning notifies you only once | Rolling list |
| Offline map areas you download | Base map without signal | Until you delete them in the app or uninstall |
| Pictures and GIFs you create for sharing | Handed to the share sheet | Deleted when you share the next one; Android may also clear the cache |
| Crash report | See section 2 | Until you send or dismiss it |
| Trial start date and whether you bought the unlock | Free trial and purchase | Until you uninstall (your purchase itself is kept by Google Play) |

**Location permission.** Hookline asks for approximate location when it starts, and may ask for precise location when you tap the "show and follow your location" button. Android lets you choose approximate or precise. Location is only read **while the app is open**. Hookline does not request background location access and does not run a location foreground service. You can refuse or revoke location permission at any time in Android settings; the app still works, you just navigate the map yourself.

**Storm Mode in the background.** When Storm Mode is on (it is on by default and can be switched off in the menu), Android's WorkManager runs a check about every 15 minutes, also when the app is closed. This check does **not** read your phone's location. It uses the coordinates already saved on your phone: the "Current location" remembered the last time the app was open, and your saved places. Notifications require Android's notification permission, which you can refuse or revoke.

**Device backup.** If you have turned on Android's backup to your Google account, Android may include Hookline's settings and saved places in that backup. This is controlled by you and Google; the developer has no access to it. See Google's privacy policy.

## 4. Data services the app connects to

Hookline downloads weather and map data **directly from your phone** from the public services listed below. There is no Hookline server in between. Like any website or app, each of these services necessarily receives your **IP address**, the time of the request and standard technical information (such as the app or library name and version and the Android version), and what is requested. Some requests also reveal the area you are looking at, or a place's coordinates, as described in the table. Each service handles this under its own privacy policy; the developer does not receive it and has no control over it.

| Service (operator) | Used for | Location information in the request |
|---|---|---|
| **NWS API, api.weather.gov** (NOAA / US National Weather Service) | US warnings | **Storm Mode background check:** for each US place with warnings turned on (your "Current location" and saved places), the app requests the warnings at that point and sends its **latitude and longitude rounded to 4 decimal places (about 10 m)**. While the app is open, it downloads the nationwide list of tornado, severe thunderstorm and flash-flood warnings, which contains no location. |
| **NOAA Open Data on Amazon Web Services** (NEXRAD Level II real-time and archive, MRMS, GOES GLM lightning) | US radar, archive, rain alerts, hail and rotation tracks, lightning | Which radar station (and dates, for the archive) you view, which indicates the region you are looking at. Rain-alert, hail and rotation files cover the whole contiguous US and contain no location; the rain check for each place is calculated on your phone. Lightning requests name only the satellite (GOES-East or GOES-West). |
| **NOAA map services, mapservices.weather.noaa.gov** | US radar mosaic when zoomed out | The bounding box of each map tile you view (the visible map area). |
| **EUMETNET OPERA Open Radar Data** (EUMETNET, hosted on CloudFerro cloud storage) | European radar and the European rain-rate composite for rain alerts | Which radar station you view; composite files cover all of Europe and contain no location. Rain checks are calculated on your phone. |
| **MeteoAlarm** (EUMETNET and the national weather services), feeds.meteoalarm.org | European warnings | Only the country of the map area or place (one feed per country). |
| **Deutscher Wetterdienst open data**, opendata.dwd.de | Dual-polarisation (CC, ZDR) for German radars | Which German radar you view. |
| **NASA GIBS**, gibs.earthdata.nasa.gov | Satellite imagery (GOES, Himawari) | The map tile coordinates you view (the visible map area). |
| **EUMETSAT EUMETView**, view.eumetsat.int | Meteosat satellite imagery and lightning over Europe and Africa | The bounding box of each map tile you view (the visible map area). |
| **OpenFreeMap**, tiles.openfreemap.org (map data © OpenStreetMap contributors) | Base map (roads, names, borders) and offline map downloads | The map tiles you view or download (the visible map area). |
| **Google Play** (Google) | Free trial unlock purchase and restoring it | Handled by Google Play; see section 5. |

If you zoom in on the map near where you are, the map area in these requests will be near where you are. The developer cannot link these requests to you, and none of them contain your name, email, saved place names or any identifier created by Hookline.

Privacy policies: [NWS](https://www.weather.gov/privacy) · [Amazon Web Services](https://aws.amazon.com/privacy/) · [EUMETNET](https://www.eumetnet.eu/) · [CloudFerro](https://cloudferro.com/) · [MeteoAlarm](https://www.meteoalarm.org/) · [Deutscher Wetterdienst](https://www.dwd.de/) · [NASA](https://www.nasa.gov/privacy/) · [EUMETSAT](https://www.eumetsat.int/) · [OpenFreeMap](https://openfreemap.org/) · [Google](https://policies.google.com/privacy)

All of these connections use encryption (HTTPS).

## 5. Purchases

Hookline is free to download with a 7-day free trial. After that, a single one-time in-app purchase ("Hookline unlock") through **Google Play Billing** unlocks the app permanently. There is no subscription. Payment is handled entirely by Google: the developer never sees your card or payment details. The app asks Google Play only whether this Google account owns the unlock, and confirms (acknowledges) a new purchase to Google Play. Google Play gives the developer the standard order information it provides for every purchase (such as order number, date, price, and the buyer's country, region and postal code), under [Google's privacy policy](https://policies.google.com/privacy). We use these only for accounting, tax and refunds, and keep them as long as tax law requires.

## 6. How information is used

- Information on your phone is used only to provide the app's features described above.
- Crash reports and emails you send us are used only to fix problems and answer you.
- We do not sell, rent or share personal data, do not use it for advertising, and do not build profiles.

## 7. Sharing

The developer does not share any personal data with anyone, except where required by law. When you use **Share** (a radar picture, a GIF, or a crash report), Android's share sheet sends it to the app and recipient that **you** choose.

## 8. Retention and deletion

- On your phone: remove saved places in the app, delete offline maps in the app, or delete everything at once with **Android Settings → Apps → Hookline → Storage → Clear storage**, or by uninstalling the app.
- Crash reports and emails you send us: kept only as long as needed to resolve the issue, and at most [24 months], unless the law requires longer. Email [YOUR CONTACT EMAIL] to have them deleted sooner.
- Purchase records: kept by Google Play; the developer keeps sales records as long as tax law requires.
- Requests already sent to the data services in section 4 are held by those services under their own policies.

## 9. Your rights

Depending on where you live (for example under the GDPR in the EU/UK or the CCPA/CPRA in California), you may have the right to access, correct, delete or restrict the use of personal data we hold, to object, and to data portability. Because the developer holds no data about you unless you emailed us, most requests can be answered quickly: write to [YOUR CONTACT EMAIL]. We will reply within one month. Where the GDPR applies, our legal basis for handling messages you send us is our legitimate interest in answering you and fixing the app (Article 6(1)(f)). You may also complain to your data protection authority [e.g. in Greece: Hellenic Data Protection Authority, www.dpa.gr].

We do not sell or "share" personal information as those terms are defined in California law.

## 10. Children

Hookline is a general-audience weather tool and is not directed at children under 13. We do not knowingly collect personal data from children. If you believe a child has sent us personal data, contact us and we will delete it.

## 11. Security

Hookline stores its data in the app's private storage on your phone, which other apps cannot read. All network connections use HTTPS. Because the developer runs no server and receives no data automatically, there is no central database of user data that could be breached.

## 12. Weather data disclaimer

Hookline is not an official warning service and is not affiliated with or endorsed by NOAA, the National Weather Service, EUMETNET, MeteoAlarm, Deutscher Wetterdienst, NASA, EUMETSAT or any government. It shows public data as it arrives, which can be delayed or wrong. For safety decisions, follow your national weather service and local authorities.

## 13. Changes to this policy

If this policy changes, we will publish the new version at this address with a new effective date and, for significant changes, mention it in the app's release notes on Google Play. If a future version of Hookline starts collecting more information, this policy will be updated before that version is released.

## 14. Contact

Questions or requests about privacy: **[YOUR CONTACT EMAIL]**
[YOUR FULL LEGAL NAME], [YOUR POSTAL ADDRESS, or city and country]
