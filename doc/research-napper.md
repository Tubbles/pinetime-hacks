# Research: Napper app (com.napper) integration surface

Date: 2026-07-19 (revised 2026-07-20). Reference behind `doc/DESIGN.md` Leg 1. Goal: find a way to obtain Napper's predicted next-sleep / next-wake time automatically, for personal use with the user's own account and data. Claims tagged verified (URL / command output) or inference.

Scope note: this project only pursues reading Napper's own on-screen output on a device the user controls. Approaches that would intercept the app's network traffic, extract stored credentials, or decompile the app are out of scope, both because Napper's Terms restrict them (see below) and because they are not the direction this project is taking.

## 1. App identification (verified)

- Play package `com.napper`: https://play.google.com/store/apps/details?id=com.napper
- Name: "Napper: Baby Sleep & Parenting". Developer: Napper AB (Stockholm, Sweden), https://napper.app/en/about-us/
- Website: https://napper.app/en/ . iOS app `id1491340863`.
- Version 6.61.0 (July 2026), min Android 7.0 (API 24), targetSdk 36. Google/Apple sign-in, cloud account, multi-caregiver sharing.
- Cloud-synced account app; computes a predicted nap/bedtime schedule from wake-window / sleep-pressure models.

## 2. Clients and official integration surface

- Clients (verified): Android app, iOS app, and an Apple Watch app (companion to iOS). No Wear OS app.
- No web client / dashboard (verified): account/dashboard subdomains (`app.`, `web.`, `my.`, `account.`, `dashboard.`, `login.`, `portal.`, `family.` …`.napper.app`) do not resolve; `napper.app/{login,account,Watch,m/home}` render the marketing site or deeplink to the app store; caregiver sharing is done in-app via a QR code / invite, not a web page. So the app itself is the only place the schedule is shown.
- No public/developer/partner API (verified): none documented anywhere.
- No Health Connect, no Google Fit, no Apple Health (verified): the Play Data Safety page lists no health-data category at all — an app writing Health Connect would have to declare health data types. https://play.google.com/store/apps/datasafety?id=com.napper
- No calendar / ICS export, no IFTTT (verified absent).
- GDPR data export exists but is a manual, human-processed request returning a file — not usable for a live daily schedule. https://napper.app/privacy/

## 3. Terms of Service (verified, https://napper.app/terms-of-service/)

Two clauses bear on automation:
- §7 Acceptable Use: "You may not scrape, extract, index or harvest data from the App, nor use the App to train or develop machine-learning models."
- §8 Licence and Restrictions: "You may not copy, modify, reverse engineer, distribute or commercially exploit the App... Reverse-engineering is only permitted where required by mandatory law."

So there is no sanctioned automated-access path. Any automatic read of the schedule is a personal-use decision the account owner makes for their own data; this project keeps to reading the app's own displayed output (see §5) and does not pursue interception or decompilation.

## 4. What the app displays (verified, from Play screenshots) and its shape

The home screen shows the next event both ways:
- Center of the circular clock: "First nap in" + "3 min" (a relative countdown). This is a React Native `<Text>` element; React Native text is exposed to Android accessibility by default, so it is likely readable by an AccessibilityService.
- Absolute clock-time labels around the ring: wake "08:12", nap "10:07"-"11:17", "13.30", bedtime "19:54". Inference: these radial labels are likely custom-`Canvas` drawn (positioned around a circle), which would NOT be exposed to accessibility.
- A bottom bar echoing "Nap in 3 min" + "10:07" (the exact meaning of that "10:07" is worth an on-device glance).

App shape (verified from the store listing and public sources): React Native + Expo; account/login required; syncs across devices and caregivers; does not work offline. Inference: the predicted schedule is represented in the backend and hydrated to each client, with the prediction itself likely computed in-app. Either way, the value the user wants is visible on the app's own screen.

Absent surfaces that could have offered an easier read (all verified): no Android home-screen widget (a standing unshipped feature request in Play reviews; and there is no widget provider in the app), no ongoing/persistent notification carrying the time, no Quick Settings tile.

Correction (user, ground truth, 2026-07-19): Napper does NOT put the next sleep/wake time in any notification. Its reminder is a relative "30 minutes to bedtime" heads-up with no clock time. So notification-reading is not a source of the time.

## 5. The avenue this project pursues: read the app's on-screen output

Because the app is the only place the schedule appears and there is no widget or notification carrying the time, the approach is to read the app's own displayed next-event text on a device the user controls, and push the resulting time to the watch:

- An Android AccessibilityService (a small custom app, or Tasker/MacroDroid + AutoInput) reads the on-screen "First nap in X min" and event type while Napper is in the foreground, computes the absolute time (`now + X`), and fires the Gadgetbridge BLE write intent (Leg 2). A single read yields an absolute time the watch shows statically until the next refresh, so a few reads a day suffice.
- Because the reader needs the app in the foreground, a dedicated always-on device running Napper (an old phone left plugged in, or an Android environment on an always-on machine) removes the "it interrupts my main phone" downside. See `doc/DESIGN.md` Leg 1 for the device options and the relay wrinkle (the watch stays paired to the phone the user carries, so a stationary reader must relay the value to that phone).

Proven precedent for the reading technique (verified): `github.com/mg-diego/baby-tracker` drives the real Napper Android app via Appium/UIAutomator2 and reads screen elements by `resource-id`. It reads past logged events rather than the forward prediction, but it demonstrates that the app's UI can be read programmatically and gives stable element IDs.

## 6. On-device checks the user can run

Decisive check for the approach in §5 — is the on-screen next-event text readable by accessibility? With Napper in the foreground, `adb shell uiautomator dump` (or Android Studio's Layout Inspector, or the Accessibility Scanner app) and confirm whether "First nap in 3 min" and the bottom-bar "Nap in .../10:07" appear as readable text nodes. If the central countdown is exposed (likely, given React Native), the approach works.

Widget sanity check (expected: none): `adb shell dumpsys appwidget | grep -i napper` — no output means no widget.
