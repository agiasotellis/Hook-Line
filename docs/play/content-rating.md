# Play Console – Content rating (IARC questionnaire)

Play Console path: **Policy and programs → App content → Content ratings → Start questionnaire**.

The questionnaire is run by IARC inside Play Console. Ratings are issued immediately for ESRB (Americas), PEGI (Europe), USK (Germany), ClassInd (Brazil), the Google Play rating (South Korea apps, Russia) and the IARC generic rating for other territories. ACB and GRAC rate games only.

## 1. Basics

| Field | Answer |
|---|---|
| Email address | [YOUR CONTACT EMAIL] (IARC sends the rating certificate here; it is not published) |
| Category | **All Other App Types** (wording on some forms: "Utility, Productivity, Communication, or Other") |

### Why this category and not "Reference, News, or Educational"

- Hookline is a **tool**: a map that renders live sensor data (radar, satellite, lightning) the user pans, zooms, measures and inspects. That is the "utility/productivity" type of app, similar to a compass, GPS or barometer app.
- It contains no editorial content: no articles, no news reports, no lessons. The only text it shows is machine-issued warning text from NWS and MeteoAlarm and the built-in product guide. The Reference/News category exists for apps whose content is written material (dictionaries, encyclopedias, news publishers, learning apps), and its extra questions are about depictions of real-world violence or disturbing news imagery, which do not fit.
- Choosing the News category would also clash with the **News apps declaration**, where we answer *No* (see declarations.md).
- Both categories lead to the same lowest rating for this content, so the choice does not change the outcome. If a reviewer queries it, "All Other App Types" is the consistent answer.

## 2. Questionnaire answers

Answer **No** to every content question. The table lists each section with the reasoning, so you can answer quickly if the wording differs slightly.

| Section / question | Answer | Notes |
|---|---|---|
| Violence (realistic, fantasy, blood, gore, depictions of death or injury) | No | Weather maps only. Warning texts may say "threat to life" or "take shelter" but contain no depictions of violence. |
| Fear / horror / disturbing content | No | |
| Sexuality, nudity, sexual references | No | |
| Language (profanity, crude humour) | No | |
| Controlled substances (drugs, alcohol, tobacco) | No | |
| Gambling, simulated gambling, real-money betting | No | |
| Hate speech, discriminatory content, extremist symbols | No | |
| Does the app allow users to interact or exchange content with other users (chat, posts, user profiles, UGC visible to others)? | **No** | There is no in-app communication. The Share button only hands a picture to Android's share sheet; the user sends it from another app. IARC's "Users Interact" element is for in-app communication. |
| Does the app share the user's current physical location with other users? | **No** | Location is never shown to other users. (Requests to weather data services are covered by Data safety, not by this question.) |
| Does the app allow users to purchase digital goods? | **Yes** | One-time in-app purchase `hookline_unlock` through Google Play Billing, after a 7-day trial. This adds the **In-App Purchases** interactive element. |
| Does the purchase include random items (loot boxes)? | No | |
| Is the app a web browser or search engine / does it provide unrestricted internet access? | **No** | It loads only fixed weather and map services. No browser view, no arbitrary URLs. |
| Does the app contain or promote content that is primarily for adults / any "adult" themes? | No | |
| Is the app primarily a news or educational product? | No | |

## 3. Expected rating

| Authority | Expected rating |
|---|---|
| ESRB (Americas) | **Everyone**, interactive element: In-App Purchases |
| PEGI (Europe) | **PEGI 3**, In-App Purchases |
| USK (Germany) | **USK ab 0 Jahren** (USK 0), In-App-Käufe |
| ClassInd (Brazil) | **L** (Livre) |
| Google Play rating (Korea apps, Russia) | All ages / 0+ |
| IARC generic | **3+** |

A low content rating does not mean the app is aimed at children. Target audience is set separately (13 and over, see declarations.md), and the store listing will show the rating plus "In-app purchases".

## 4. After submitting

- Save the questionnaire, then **Apply rating**. The certificate email from IARC arrives within minutes.
- Re-do the questionnaire only if content changes (e.g. adding chat, user posts, a web view, or ads).

## Sources

- Content ratings for apps and games (rating authorities, IARC process): https://support.google.com/googleplay/android-developer/answer/188189
- Content rating requirements (questionnaire in Play Console): https://support.google.com/googleplay/android-developer/answer/9898843
- Prepare your app for review / App content page: https://support.google.com/googleplay/android-developer/answer/9859455
