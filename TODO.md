# To Do

## Next-event watch face corner (active undertaking)

Design: `doc/DESIGN.md`. Research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Napper extraction (Leg 1) is the hard part; the notification path is dead (notifications carry no time, user-confirmed) and Napper offers no API, web client, or widget. The schedule appears only on the app's own screen. See `doc/DESIGN.md` Leg 1 and `doc/research-napper.md`.

Direction (leaning): read Napper's on-screen next-event text on a dedicated always-on device and relay the value to the carried phone for the watch write. Open sub-choice: a cheap physical old phone (reliable) vs. an emulator / Waydroid on an always-on machine (verify Play Integrity lets Napper sign in there first).

Sequencing (recommended): build Legs 2+3 first against a dummy source so the watch feature isn't blocked on Leg 1.

On-device checks (only the user can answer):
- Accessibility exposure: with Napper foreground, is "First nap in 3 min" (and the bottom-bar "10:07") a readable text node? (`adb shell uiautomator dump` / Layout Inspector / Accessibility Scanner.) Decides whether the on-screen reading approach is viable.
- Do you use InfiniTime continuous/background heart-rate measurement? (Decides whether "HR state == Stopped" is a usable trigger.)

Firmware decisions with a recommended default:
- Firmware: modify the existing G7710 face (recommended) vs. add a new face.
- Settings toggle: hard-code for v1 (recommended) vs. a `Settings` option.

Implementation legs:
- Firmware: add `NextEventService` BLE service (`00060000-...`, modeled on SimpleWeatherService); wire through NimbleController / SystemTask / DisplayApp / AppControllers; render `HH:MM` in the G7710 corner when HR is Stopped. Buildable now against a dummy source.
- Transport: enable Gadgetbridge BLE Intent API on the device; re-pair the watch after the characteristic is added (GATT service-discovery cache); re-send on `BLUETOOTH_CONNECTED`. Buildable/testable now.
- Extraction (Leg 1): read Napper's on-screen next-event text on a dedicated device and relay it to the carried phone.
- Build/flash: build `pinetime-mcuboot-app-dfu` via the official Docker image; OTA via Gadgetbridge's File Installer; do not Validate until BLE reconnect + reboot survive.
- Consider adding InfiniSim as a submodule for hardware-free UI iteration on the face.

## User written inbox
