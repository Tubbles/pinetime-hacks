# Research: InfiniTime internals for the "next event" corner

Date: 2026-07-19. Reference material behind `doc/DESIGN.md`. Claims tagged verified (URL or `file:line`) or inference. Line references are against InfiniTime as of upstream commit `b66c5de7` (2026-07-18); the repo submodule is pinned to release tag 1.16.1, so exact line numbers may differ by a few but the structure holds.

## 1. The Casio G7710 watch face

Files: `src/displayapp/screens/WatchFaceCasioStyleG7710.{h,cpp}`. Class `Pinetime::Applications::Screens::WatchFaceCasioStyleG7710`, inherits `Screen`.

Constructor dependencies (verified, `.h:30-37`): `DateTime&`, `const Battery&`, `const Ble&`, `NotificationManager&`, `Settings&`, `HeartRateController&`, `MotionController&`, `FS&`. No weather service is passed to this face today (unlike Digital / PineTimeStyle).

The heart-rate corner (verified, `.cpp:151-159`): two LVGL labels created in the constructor:

```cpp
heartbeatIcon = lv_label_create(lv_scr_act(), nullptr);
lv_label_set_text_static(heartbeatIcon, Symbols::heartBeat);
lv_obj_align(heartbeatIcon, lv_scr_act(), LV_ALIGN_IN_BOTTOM_LEFT, 5, -2);
heartbeatValue = lv_label_create(lv_scr_act(), nullptr);
lv_obj_align(heartbeatValue, heartbeatIcon, LV_ALIGN_OUT_RIGHT_MID, 5, 0);
```

Bottom-left corner = `heartbeatIcon` (heart symbol) + `heartbeatValue` (BPM). Bottom-right = step counter (`stepValue`/`stepIcon`).

Refresh loop (verified, `.cpp:196-313`): created as an LVGL task at `LV_DISP_DEF_REFR_PERIOD`; every field is a `Utility::DirtyValue<T>` so labels are only rewritten on change. Heart-rate logic (`.cpp:292-305`):

```cpp
heartbeat = heartRateController.HeartRate();
heartbeatRunning = heartRateController.State() != Controllers::HeartRateController::States::Stopped;
if (heartbeat.IsUpdated() || heartbeatRunning.IsUpdated()) {
  if (heartbeatRunning.Get()) {
    lv_obj_set_style_local_text_color(heartbeatIcon, ..., color_text);        // green 0x98B69A
    lv_label_set_text_fmt(heartbeatValue, "%d", heartbeat.Get());
  } else {
    lv_obj_set_style_local_text_color(heartbeatIcon, ..., lv_color_hex(0x1B1B1B)); // dimmed
    lv_label_set_text_static(heartbeatValue, "");
  }
}
```

"Not in use" = `HeartRateController::State() == States::Stopped`: heart icon recolored to near-black, BPM blanked. The label objects still exist.

`HeartRateController::States` = `{Stopped, NotEnoughData, NoTouch, Running}` (`src/components/heartrate/HeartRateController.h:18`). `Stopped` is set only by `Disable()`. Inference: with background HR measurement enabled (`Settings::GetHeartRateBackgroundMeasurementInterval`, disabled by default), the state does not return to `Stopped` between samples, so a "corner shows event only when Stopped" rule would hide the event whenever background HR is on. Design must account for this.

Fonts (verified, `.cpp:35-49, 315-334`): the face loads three fonts from the LittleFS external-flash filesystem and `IsAvailable()` returns false if any is missing — the G7710 face requires the external resources package (`infinitime-resources-x.y.z.zip`). Existing corner labels use the default theme font (no explicit font set), so plain `HH:MM` text needs no new resource.

## 2. Watch face registration / selection

- `src/displayapp/apps/Apps.h.in`: `enum class WatchFace : uint8_t { Digital, Analog, PineTimeStyle, Terminal, Infineat, CasioStyleG7710, PrideFlag }`; `using UserWatchFaceTypes = WatchFaceTypeList<@WATCHFACE_TYPES@>;` (CMake-configured).
- Each face defines a `WatchFaceTraits<WatchFace::X>` in its header (G7710 at `.h:105-124`): `name = "Casio G7710"`, a `Create(AppControllers&)` factory, `IsAvailable(FS&)`.
- `src/displayapp/UserApps.h:45-60`: `consteval CreateWatchFaceDescriptions(...)` builds the `userWatchFaces` array at compile time.
- `src/displayapp/DisplayApp.cpp:540-550`: `Apps::Clock` looks up `settingsController.GetWatchFace()` and calls `watchFace->create(controllers)`, falling back to `userWatchFaces[0]`.
- Which faces are compiled in: `src/displayapp/apps/CMakeLists.txt:22-32`, overridable via `-DENABLE_WATCHFACES=...`; the Docker build forwards an `ENABLE_WATCHFACES` env var.
- Docs: `doc/code/Apps.md`.

Modify-vs-add: modifying the existing face needs zero registration work (two files + wiring). Adding a face needs a new enum value (append-only — the value is persisted in `settings.dat` as `watchFace`), new Traits, a `src/CMakeLists.txt` entry, and inclusion in the build's face list. Firmware must fit the mcuboot slot (475,136 bytes, `bootloader/README.md:97`); the default build already includes all faces, so a modification costs almost nothing.

## 3. BLE services and the SimpleWeatherService precedent

`src/components/ble/` services (verified listing): AlertNotification(+Client), BatteryInformation, CurrentTime(+Client), DeviceInformation, Dfu, FS, HeartRate, ImmediateAlert, Motion, Music, Navigation, SimpleWeather, plus NimbleController, BleController, NotificationManager, ServiceDiscovery.

Custom UUID scheme (`doc/ble.md:63-99`): base `xxxxxxxx-78fc-48fe-8e23-433b3a1942d0`; service `SSSS0000-...`; characteristic `SSSSCCCC-...`. Allocated: Music `0000`, Navigation `0001`, Call-notification char `0002`, Motion `0003`, old Weather `0004` (removed), SimpleWeather `0005`. Inference: a new service takes `0006`.

SimpleWeatherService (`src/components/ble/SimpleWeatherService.{h,cpp}`, `doc/SimpleWeatherService.md`):
- UUID service `00050000-...`, one write-only characteristic `00050001-...` (`BLE_GATT_CHR_F_WRITE`).
- Message format: byte[0] = message type (0 current, 1 forecast), byte[1] = version. Current-weather payload: 8-byte LE local-time UNIX timestamp, 3x int16 temps (deg C x100), 32-byte location string, icon ID, optional sunrise/sunset.
- Write path: phone writes -> NimBLE calls the free-function `WeatherCallback` -> `OnCommand(ctxt)` parses `ctxt->om->om_data` -> stores into `std::optional<CurrentWeather> currentWeather` / `std::optional<Forecast>`. No ack/notify back, no persistence (RAM only, lost on reboot).
- Expiry: `Current()` / `GetForecast()` return `std::nullopt` if the stored timestamp is older than 24 h relative to `dateTimeController.CurrentDateTime()` (`.cpp:167-193`).
- Consumed by faces holding a `Controllers::SimpleWeatherService&` and polling in `Refresh()` (e.g. `WatchFacePineTimeStyle.cpp:542-557`, `WatchFaceDigital.h:73`) into a `DirtyValue<std::optional<...>>`. The BLE host task writes the optional while the display task reads it; upstream accepts this benign race.
- Phone side: Gadgetbridge merged support in Codeberg PR #3475 (2024-01-04).

## 4. Cost of a new custom BLE service

1. New file pair in `src/components/ble/`. Size precedent: NavigationService 117+58 lines, SimpleWeatherService 238+197. A minimal "next event" service ~150-250 lines.
2. `NimbleController.h`: include, a member, an accessor.
3. `NimbleController.cpp`: constructor-initialize the member; call `yourService.Init()` in `NimbleController::Init()` (which is just `ble_gatts_count_cfg(...)` + `ble_gatts_add_svcs(...)`).
4. Add the `.cpp` to `src/CMakeLists.txt` (SimpleWeatherService appears in more than one target source list; InfiniSim consumes the same sources).
5. Expose to the UI (§5).

## 5. How a face gets BLE-service data

- `src/displayapp/Controllers.h:36-56`: `struct AppControllers` holds controller references plus pointers for BLE-task-owned services: `SimpleWeatherService* weatherController`, `MusicService* musicService`, `NavigationService* navigationService`.
- Services are members of `NimbleController` (owned by `SystemTask`). `SystemTask::Work()` registers them after `nimbleController.Init()` (`SystemTask.cpp:143-145`): `displayApp.Register(&nimbleController.weather());` etc.
- `DisplayApp::Register(...)` overloads store into `controllers.weatherController` etc. (`DisplayApp.cpp:723-733`).
- `WatchFaceTraits::Create(AppControllers& controllers)` dereferences the pointer when constructing the face (`WatchFacePineTimeStyle.h:136`, `WatchFaceDigital.h:94`).

So giving the G7710 face a new service = add a pointer field to `AppControllers`, one `Register` overload, one `SystemTask` call, one constructor param, one line in the Traits `Create`.

## 6. Per-face settings

`src/components/settings/Settings.h`: per-face settings are nested structs in the serialized `SettingsData` — `struct PineTimeStyle { ... PTSWeather weatherEnable; }`, `struct WatchFaceInfineat { bool showSideCover; int colorIndex; }` — with getter/setter pairs that set `settingsChanged`. `PTSWeather {On, Off}` is the exact precedent for a corner toggle; PineTimeStyle toggles it from an on-watch long-press menu.

Persistence: the whole `SettingsData` is written raw to `/settings.dat`; `LoadSettingsFromFile` discards the file if `version != settingsVersion` (currently `0x000a`). Adding a field requires bumping `settingsVersion`, so users lose all stored settings once on first boot of the new firmware (reset to defaults, nothing worse).

## 7. Existing calendar / next-event work

Grep of `src/` and `doc/` for `calendar|appointment`: no calendar-event feature exists. Hits are only PineTimeStyle's decorative calendar icon and LVGL's unused `lv_calendar` widget.

Upstream PRs/issues:
- PR #790 "WIP: Calendar / Timeline app and service" — open draft since 2021-10-28, never merged. A calendar BLE service speaking the Gadgetbridge sync protocol + timeline UI. Closest precedent.
- PR #923 "Calendar app" — open, month-view using `lv_calendar`, no phone data.
- PR #1958 "Up to date calendar app" — #923 rebased onto the 1.14 framework; author reports running the mcuboot-app version on a sealed PineTime with no issues found.
- Issue #913 "Simple calendar view" — closed.
- Community writeup: github.com/FederAndInk/my_projects/blob/main/infinitime_calendar_events.md — a 4-characteristic calendar service; reports Gadgetbridge already had internal calendar-event infrastructure so only the device-specific service code was needed. None merged in either project.

Conclusion: nothing upstream ships "next event" today; a firmware fork is required (and, for real calendar data, a phone-side piece too), unless events are pushed via some other writable channel.

## 8. Web research

Latest release (GitHub API): InfiniTime 1.16.1, published 2026-07-05. Assets: `bootloader_infinitime-1.16.1.hex`, `infinitime-resources-1.16.1.zip`, `pinetime-mcuboot-app-dfu-1.16.1.zip`.

InfiniSim (github InfiniTimeOrg/InfiniSim): runs the InfiniTime UI (LVGL + screens + controllers) on desktop via SDL2, purpose-built for developing faces without hardware. Deps: CMake, SDL2, g++/clang++, `lv_font_conv` (npm), Pillow, optional libpng. Build: `cmake -S . -B build -DInfiniTime_DIR=<path to InfiniTime checkout> [-DBUILD_RESOURCES=ON] [-DENABLE_USERAPPS=...]` then `cmake --build build`. Interaction: left-click = touch, right-click = button; keys: `v/V` battery, `h/H` heart rate start/stop +-10 bpm, `b/B` bluetooth, `i/I` screenshots, arrows = swipes. BLE is simulated, not real — a new GATT service cannot be exercised end-to-end; stub its data source. Actively maintained (main commit 2026-07-06).

Firmware build via Docker (`doc/buildWithDocker.md`): image `infinitime/infinitime-build` (Ubuntu 22.04, ARM GCC 10.3, nRF SDK 15.3.0, MCUBoot tooling). Submodules must be initialized first. Run `docker run --rm -it -v ${PWD}:/sources --user $(id -u):$(id -g) infinitime-build`; `ENABLE_USERAPPS` / `ENABLE_WATCHFACES` forwarded to CMake; always builds with `-DBUILD_DFU=1 -DBUILD_RESOURCES=1`. Targets: `pinetime-mcuboot-app` (bootloader-compatible, the one a sealed watch needs). OTA artifact for Gadgetbridge: `pinetime-mcuboot-app-dfu-x.y.z.zip`.

OTA/DFU safety on a sealed watch (from `bootloader/README.md`, `doc/gettingStarted/about-software.md`, `updating-software.md`, `ota-gadgetbridge.md`):
- Boot chain: MCUBoot bootloader at flash 0x0000, app in primary slot at 0x8000, DFU writes to a secondary slot in external SPI flash, bootloader swaps and runs the new image on reboot. The DFU zip contains only the app; OTA never touches the bootloader.
- Validation: the swapped-in firmware runs unvalidated; the user taps Settings -> Firmware -> Validate. If the watch resets before validation, MCUBoot swaps back ("safety feature to prevent bricking").
- Crash protection: the bootloader arms the ~7 s hardware watchdog before jumping; a hung/crash-looping firmware gets reset and rolled back.
- Physical-button escapes (work on a sealed watch): hold during boot until the pine cone is blue = force rollback even if validated; until red = boot the recovery firmware (BLE + OTA only) from SPI flash. Watches shipped after June 2021 include bootloader 1.0.0 + recovery 0.14.1.
- Assessment: bricking risk low. Failure ladder: transfer CRC -> watchdog auto-rollback before validation -> manual Reset -> button rollback -> button-loaded recovery. Practical rule: do not Validate until BLE reconnect + a reboot survive with the new corner working.

Fork policy: `CONTRIBUTING.md` is standard GitHub flow. `doc/InfiniTimeVision.md`: "Prefer solid default experience over customization. Personalization is achieved through custom watch faces." Single `main` branch, semver tags, GPL-3.0. No dedicated "maintaining a fork" guide, nothing prohibiting personal forks; watch faces are compile-time selectable specifically so builders compose their own firmware.
