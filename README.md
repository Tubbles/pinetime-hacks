# pinetime-hacks

Personal hacking playground for a PineTime smartwatch (InfiniTime firmware, Gadgetbridge companion on Android).

First project: display the "time of next event" — the next predicted sleep or wake-up time from the Napper baby-sleep app on the phone — in the bottom-left corner of the Casio G7710 watch face, in place of the heart-rate BPM indicator whenever heart rate is not in use. Fully automatic; no manual input per event.

See `doc/DESIGN.md` for the plan, `doc/research-*.md` for the grounded research behind it, and `doc/LOG.md` for the running log of findings.

Shape of the system (three independent legs):

1. Extraction (phone): read Napper's predicted next sleep/wake time, automatically. Napper has no API, so the working hook is parsing its own reminder notification.
2. Transport (phone to watch): Gadgetbridge's BLE Intent API (v0.82.0+) writes a small timestamp to a custom InfiniTime BLE characteristic. No Gadgetbridge fork.
3. Display (watch): a custom InfiniTime build stores the timestamp and renders it in the G7710 heart-rate corner when heart rate is not in use.
