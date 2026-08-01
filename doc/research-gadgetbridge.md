# Research: Gadgetbridge and phone-to-watch transport

Date: 2026-07-19. Reference behind `doc/DESIGN.md` Leg 2. Claims tagged verified (URL) or inference.

Headline: since Gadgetbridge 0.82.0 there is a documented, upstream BLE Intent API that lets any local Android app (including Tasker) write an arbitrary hex payload to any GATT characteristic through Gadgetbridge's existing connection to the watch. The phone side needs no fork; the custom work concentrates in the InfiniTime firmware (a custom characteristic), which this project modifies anyway.

## 1. Gadgetbridge <-> InfiniTime support today

Source: `PineTimeJFSupport.java` on master (Codeberg). Registered services: Current Time (CTS), Alert Notification, Device Information, Battery, Music Control, Weather (old CBOR), Simple Weather, Navigation, Motion (step count), Heart Rate, BLE filesystem. Plus world-clock characteristic writes.

InfiniTime side (`doc/ble.md`): CTS, ANS + the call/notification-event characteristic `00020001-...`, Music `00000000-...` (fw >=0.8), Navigation `00010000-...` (>=0.11), Motion `00030000-...` (>=1.7), SimpleWeather `00050000-...` (fw >=1.14, replacing the old Weather Service from 1.8). No calendar service, no world-clock service upstream.

Weather (both generations, verified from source): `onSendWeatherCBOR()` -> old WeatherService; `onSendWeatherSimple()` -> SimpleWeatherService (fixed binary ~49-53 bytes). Selection: "if firmware major==1 && minor<=13 use CBOR, else Simple." Changelog: 0.71.0 added InfiniTime weather; 0.75.0 fixed expiry + navigation; 0.77.0 added sunrise/sunset (a SimpleWeather field, so SimpleWeather support is <=0.77.0).

Calendar: not supported for InfiniTime. `PineTimeJFSupport` has no calendar-event methods; InfiniTime has no calendar BLE service upstream. Gadgetbridge core does have generic calendar-sync infrastructure (CalendarReceiver / CalendarEventSpec) used by other devices (Zepp OS 0.72.0, Huawei 0.82.0, Bangle.js). A complete but abandoned prototype exists both sides (FederAndInk): an InfiniTime calendar app + BLE service (branch `feature/CalendarTimeline`) and a Gadgetbridge branch (`feature/PineTimeCalendarEvents`) reusing GB's calendar plumbing; never merged. That service squats on `00050000-...`, since assigned to SimpleWeather (1.14+), so reviving it needs a UUID re-home (e.g. `00060000-...`).

Practical lessons from FederAndInk (verified, directly relevant to any custom characteristic): MTU had to be raised (~150-byte writes failed with `BLE_ATT_ERR_INSUFFICIENT_RES` until MTU 200); Android's GATT service-discovery cache hid newly added characteristics until the device was removed/re-added in Gadgetbridge.

## 2. Gadgetbridge extension surfaces (feeding data in)

### 2a. Weather input path
- `nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER` broadcast/explicit intent, extras `WeatherJson` (one WeatherSpec), `WeatherSecondaryJson`, or `WeatherGz` (gzip JSON array). https://gadgetbridge.org/internals/development/weather-support/ . Android 14+ needs an explicit intent (target `nodomain.freeyourgadget.gadgetbridge`, `.nightly` for nightlies).
- WeatherSpec fields: timestamp, location, currentTemp, todayMin/MaxTemp, currentCondition(+Code), humidity, windSpeed/Direction, forecasts[].
- Provider apps: Tiny Weather Forecast Germany, QuickWeather, Breezy Weather, LineageOS OpenWeatherProvider. GB has no network permission; weather always comes from a third-party app.

### 2b. BLE Intent API (the key finding)
Docs: https://gadgetbridge.org/internals/automations/intents/ ; PR https://codeberg.org/Freeyourgadget/Gadgetbridge/pulls/4025 . Authored by dakhnod, merged 2024-09-09, shipped in 0.82.0.
- `nodomain.freeyourgadget.gadgetbridge.ble_api.commands.CHARACTERISTIC_WRITE` — extras `EXTRA_DEVICE_ADDRESS` (MAC), `EXTRA_CHARACTERISTIC_UUID`, `EXTRA_PAYLOAD` (hex-encoded bytes).
- Also `...commands.CHARACTERISTIC_READ` and `...events.CHARACTERISTIC_CHANGED` broadcast (notifications/read results). Trap (verified against `BleIntentApi.java`): the CHANGED *event* carries the characteristic in extra `EXTRA_CHARACTERISTIC`, whereas the WRITE/READ *commands* use `EXTRA_CHARACTERISTIC_UUID`. A receiver must read `EXTRA_CHARACTERISTIC`. The public gadgetbridge.org docs get this wrong.
- The notify half (subscribe + rebroadcast) is real and enabled by a separate per-device toggle ("Broadcast GATT notification Intents"). Because Gadgetbridge registers every discovered GATT service into the Intent API before its device-specific "supported services" gate, a brand-new InfiniTime characteristic is writable, subscribable, and rebroadcast with no PineTimeJFSupport fork. Per-characteristic filtering was added in 0.84.0 (below that, enabling notify subscribes to every notify-capable characteristic).
- Enabled per device: Device Settings -> Developer settings -> "BLE Intent API". Works on any BLE device; a "Generic GATT Client" device type exists for otherwise-unsupported gadgets.
- Security caveat: sender identity cannot be verified — any app on the phone can use it once enabled.
- Other Intent API actions (each gated in Developer options): BLUETOOTH_CONNECT/_DISCONNECT, ACTIVITY_SYNC, TRIGGER_EXPORT/TRIGGER_ZIP_EXPORT, SET_DEVICE_SETTING, SET_GLOBAL_SETTING, DEBUG_SEND_NOTIFICATION, PebbleKit-compat SEND_NOTIFICATION.

### 2c. Bangle.js two-way intent precedent
PR #2769 (merged 2022-08-17): phone->watch via `com.banglejs.uart.tx` (extra `line`, gated by per-device "Allow Intents"), watch->phone via JSON intents. So "arbitrary app-to-watch data via intent" already has upstream precedent for one device family; the generic BLE Intent API now covers most of that ground without device-specific code.

## 3. Forking / building Gadgetbridge

- Build (https://gadgetbridge.org/internals/development/setup-environment/): Gradle, JDK 21, `./gradlew assembleMainDebug`/`installMainDebug`; compileSdk 36, minSdk 23, targetSdk 34. Moderate difficulty.
- Coexistence/migration: a self-built `mainDebug` cannot install alongside the F-Droid build (Pebble content-provider conflict); a different signing key makes Android treat it as a different app — migrate via GB's DB export/import; re-pair the watch. The `mainNopebble` variant installs alongside the F-Droid build.
- Nightlies: official nightly F-Droid repo exists, signed with the project key. So testing the BLE Intent API needs no fork — stable >=0.82.0 already has it.
- Distribution: releases via F-Droid; mainline GB is not on Google Play.
- Upstreaming odds (inference): GB accepts extension surfaces (BLE Intent API 2024, Bangle.js intents 2022, generic weather receiver) and has a "new gadget" tutorial. An InfiniTime calendar/custom-field feature would likely need the firmware side upstream first — FederAndInk's work stalling out-of-tree both sides is the cautionary precedent.

## 4. Alternative phone-side paths

### 4a. Second app holding its own GATT connection alongside Gadgetbridge
- Verified: each `connectGatt()` registers a distinct client interface (`clientIf`), stack-wide limit `GATT_MAX_APPS = 30`; connections are brokered by the system Bluetooth process. (van Welie BLE series.)
- Not verified (open): whether a second app's `connectGatt()` to a device already connected by another app on the same phone attaches to the existing ACL link and succeeds. Neither the Punch Through guide nor van Welie addresses the same-phone two-app case; the "peripheral stops advertising once connected" exclusivity concerns a second phone, not a second app. Treat same-phone GATT multiplexing as plausible-but-unproven; a 10-minute nRF Connect test (while GB is connected) settles it.
- Caveats if it works: shared link => single negotiated MTU (cannot change mid-connection), shared service-discovery cache, reconnection contention when GB drops.

### 4b. Automation apps writing GATT directly
- Tasker plugins that write arbitrary hex to device+service+characteristic exist (Basic BLE Writer and forks). They open their own GATT connection, inheriting 4a's uncertainty plus unclear maintenance.
- Dominant combination: Tasker/MacroDroid doesn't need a BLE plugin — it can fire the GB BLE Intent API broadcast (2b), letting GB perform the write on its established connection. It can also read incoming app notifications and transform them into the intent — satisfying "automation reads a notification and writes a characteristic" without touching BLE.

### 4c. Android-side custom-data-to-InfiniTime outside Gadgetbridge
None found. Only Linux companions (itd, WatchMate, Amazfish, Siglo), iOS InfiniLink, a Home Assistant feature request, and FederAndInk's GB-based project. Absence of evidence, not proof of absence.

## 5. The notification channel (InfiniTime side)

- GB per-app forwarding: Settings -> Notifications -> Applications list, per-app on/off + per-app content-based filter rules. A dedicated "event pusher" app's notifications can be the only ones forwarded, or filtered by content.
- InfiniTime persists notifications: `NotificationManager` stores a circular buffer of 5 notifications, 100 chars each (`TotalNbNotifications = 5`, `MessageSize {100}`), browsable in the watch's Notifications app.
- A custom-firmware watch face could read/parse the newest matching notification. Downsides: 100-char budget, 5-slot buffer polluted by real notifications and by your own updates, text-parsing fragility, and each update buzzes/lights the watch unless notification behavior is also patched. Workable as an MVP; inferior to a dedicated characteristic if firmware is being customized anyway.

## 6. Ranking of phone-side delivery options

Assumes the watch runs a personal InfiniTime fork growing a "next event" characteristic regardless; a few tiny writes per day.

1. Stock Gadgetbridge + BLE Intent API write to a custom InfiniTime characteristic (fired from Tasker/MacroDroid or a ~50-line app). Robustness high (documented public API in stable GB >=0.82.0, rides GB's managed connection/reconnect, no contention). Maintenance minimal (zero GB code). The clear winner. Caveats: any local app can write once enabled; re-pair after adding the characteristic (service cache); no delivery queue — re-send on `BLUETOOTH_CONNECTED` or a timer.
2. Gadgetbridge fork with a native custom-data/calendar feature (optionally reviving FederAndInk). High robustness once built; high maintenance (perpetual rebase, signature/data migration off F-Droid, lost F-Droid updates unless upstreamed). Only if the goal grows into real calendar sync worth upstreaming.
3. Notification piggyback (a dedicated app/Tasker posts a notification, GB per-app filter forwards it, the watch face parses the 5-slot store). Medium robustness (fragile text parsing, buffer pollution, buzz per update). Decent MVP to validate the face UX before designing the characteristic.
4. Weather-channel piggyback (stuff the timestamp into a SimpleWeather field via `ACTION_GENERIC_WEATHER`). Low robustness (collides with any real weather provider, semantic abuse of a versioned format). Strictly dominated by #1.
5. Second custom app holding its own GATT connection. Unproven same-phone ACL sharing (4a) + reconnect contention; you own a background BLE app forever. Only if the Intent API were removed.
6. Automation-app own-GATT plugin. Lowest — inherits #5's uncertainty plus unmaintained plugins. Superseded by Tasker -> GB Intent API.

Recommended: custom InfiniTime characteristic (mind MTU + service-cache lessons) + stock GB >=0.82.0 with BLE Intent API enabled + a Tasker profile or minimal helper app that broadcasts `CHARACTERISTIC_WRITE` on data change and re-sends on GB's `BLUETOOTH_CONNECTED`. Keep option 3 as the throwaway MVP and option 2 only as an eventual upstreaming ambition.
