# Wake gestures: raise-wrist and shake, the algorithms and every gate in front of them

Source read 2026-08-12 against the `clock-sync` branch (base 1.16.1); all claims cited to file:line. Amended 2026-09-14: every threshold below is now a setting (see the tuning-knobs section), the lower-wrist algorithm is documented, and one shake-speed claim is corrected. Line numbers from the 2026-08-12 read have drifted since; the names have not.

## The pipeline

- SystemTask polls the BMA421 accelerometer every **100 ms** in every state, awake or asleep (`stateUpdatePeriod`, `SystemTask.cpp:198`, called at `:445-446`).
- `MotionController::Update` pushes x/y/z into ring buffers. Since 2026-09-14 they hold 16 samples (`historySize`) and the raise detector reads a configurable `window` of them, default 8 = **~800 ms**. Index 0 is the newest sample and index `size - k` is k ticks ago.
- `GetAccelStats(window, settle)` averages the **newest `settle` samples** ("now", default 2 = ~200 ms) and `settle` samples starting `window - 1` ticks ago ("prev", default ~600–800 ms ago).
- Units: 1 g ≈ 1024. The thresholds are sines of angles: 724 = sin 45°, 887 = sin 60°, 384 ≈ sin 22°, 265 = sin 15°, 64 ≈ sin 3.6°.

## The wake gates, in order (`SystemTask.cpp:518-541`)

1. **Notification mode Sleep (the quick-settings moon) disables BOTH raise and shake wake entirely** (`:525`). Silent (bell Off) does NOT — it only mutes chimes/buzzes.
2. The Raise Wrist toggle in Settings → Wake Up must be on.
3. `ShouldRaiseWake(thresholds)`, ALL must hold (the numbers are the defaults; all four are sliders now):
   - **Level**: `|xMean| ≤ 384` — the 3–9 o'clock axis within ~22° of level. A sideways-tilted wrist fails.
   - **Still**: `yVariance ≤ 56²` over the newest ~200 ms — the gesture must END in stillness. Continuous motion re-fails this every 100 ms tick. (Near-vertical poses additionally require z to be calm; that `-724` cutoff is still hardcoded.)
   - **Facing you**: `yMean ≤ -64` — the 12 o'clock edge raised at least ~3.6° toward the viewer.
   - **Rolled**: `DegreesRolled(now vs ~600 ms ago) < -45°` — the face must have ROTATED toward you by ≥45° around the forearm axis within the window. The math is only meaningful when the readings are gravity (`MotionController.cpp:14`): while the arm is accelerating, the tilt estimate is garbage in random directions.

So the detector's signature is **"one smooth ≥45° roll of the forearm, settling into a still, level, slightly-toward-you pose"** — a rotate-and-settle detector, not a motion detector.

## Why aggressive waving does not wake it (expected behavior)

Three independent gates reject waving: the stillness gate (variance stays high the whole time), the roll gate (waving is swinging, not rolling, and under acceleration the angle math is meaningless anyway), and often the level gate (arm ends up sideways). Waving harder makes it worse, not better. The gesture that works: drop the wrist, one smooth rotate-up to reading position, hold for a beat; the wake fires within ~100–300 ms of settling.

**Shake-to-wake is the feature for the "wave at it" instinct**: a separate toggle in Settings → Wake Up (can coexist with Raise Wrist), comparing an EMA of motion speed (`MotionController.cpp:66-72`) against the adjustable threshold in Settings → Shake Threshold. Since 2026-08-12 a shake wake locks the screen exactly like a raise wake.

## Shake-to-wake: the algorithm (`MotionController.cpp:66-72`)

Runs on the same 100 ms poll and shares the notification-Sleep kill switch with raise-wake (`SystemTask.cpp:525`); its own toggle lives in Settings → Wake Up.

- Each tick computes a "speed": `|Δz + Δy/2 + Δx/4| × 100 / elapsed_ticks`, where each Δ is the newest accelerometer sample minus the **previous tick's** (~100 ms). CORRECTION 2026-09-14: this line used to say the delta spanned the whole ~800 ms ring. It never did. The code reads `zHistory[size - 1]`, and in this `CircularBuffer` index 0 is the newest sample while index `size - 1` is `data[idx - 1]`, the previous tick. The `× 100 / elapsed_ticks` normalization only makes sense for a one-tick delta, which is the tell. Practical consequence: shake wake responds to per-tick jerk fed through the EMA, not to displacement over the last second.
- Axis weights make the face normal dominant: z (out of the watch face) counts full, y (along the band) half, x (across) quarter. Flapping the face back and forth registers strongest; sideways sawing weakest.
- The speed feeds an EMA: `accumulatedSpeed = 0.2·speed + 0.8·accumulatedSpeed` (integer form). One sharp flick gets diluted to 20% and decays ×0.8 per tick (mostly gone in ~0.5 s); sustained shaking for ~300–500 ms ramps the accumulator up.
- Wake fires when `accumulatedSpeed > shakeWakeThreshold` (persisted setting, default 150, adjustable in Settings → Shake Threshold; `Settings.h:399`). Nothing resets the accumulator on wake — it just decays.

Operationally: two or three brisk shakes across half a second, ideally rotating the face, beat one violent flick. Since 2026-08-12 a shake wake sets the touch lock exactly like a raise wake; tap, button, and notification wakes stay unlocked.

## Tuning knobs: settings since 2026-09-14

They used to be constants in `ShouldRaiseWake`. They are now persisted settings with sliders under **Settings → Raise wrist**, plus a Reset button that restores every default. Loosening the roll or variance thresholds makes it trigger easier at the cost of false wakes, and with the lock screen feature every false raise-wake also locks the screen.

| Slider | Setting | Default | Range | What it does |
| --- | --- | --- | --- | --- |
| Roll | `rollDegrees` | 45 | 10–90 | Minimum roll toward the viewer over the window. Applied negated. |
| Still | `stillness` | 56 | 8–200 | Applied squared as the y (and near-vertical z) variance limit over the "now" samples. |
| Level | `level` | 384 | 64–1024 | `|xMean|` limit, the 3–9 o'clock axis. 1024 is 1 g. |
| Tilt | `tilt` | 64 | 0–256 | `yMean` limit, applied negated: how far the 12 o'clock edge must be raised. |
| Win | `window` | 8 | 3–16 | Ring samples spanned between the "prev" and "now" groups. 8 is ~800 ms at the 100 ms poll. |
| Set | `settle` | 2 | 1–4 | Newest samples averaged into each group, and the sample count the variance is taken over. |

The last two are the timing that used to be hardcoded twice over: `window` was not a constant at all but the literal size of the accelerometer ring buffer, and `settle` was `AccelStats::numHistory`. The ring is now a fixed 16 samples with both counts indexing into it, so the defaults read exactly the indices the old 8-slot buffer did. `settle < window` is clamped where the values are used, because the two sliders move independently.

The shake-speed calculation is untouched by the ring resize: it reads `[size - 1]`, which is the previous tick whatever the size is.

The knobs reach `MotionController` as a plain `RaiseWakeThresholds` struct that `SystemTask` fills from `Settings` on each poll, so `MotionController` still knows nothing about `Settings`. Defaults live once, in `components/motion/MotionThresholds.h`, shared by the `SettingsData` initializers and the Reset button.

## Lower wrist: the sleep-on-lower gesture (`ShouldLowerSleep`)

Lower wrist is not a wake source at all, despite sitting in the Settings → Wake Up list: it is the gesture that puts the watch to sleep early, checked every poll while the state is Running (`SystemTask::UpdateMotion`). It runs on the same `AccelStats` as raise wake, over the original 8-sample window with 2 settle samples, which is deliberately NOT configurable (the same window also sets the span of the history scan below, so moving it would change two things at once).

Three ways to reach sleep, in order:

1. **Rolled onto its side**: `|xMean|` past the side level AND the roll of x against z past the side roll, in the matching direction. This is the "arm dropped and the watch rotated sideways" case, and it returns true immediately.
2. Otherwise, **facing the ground**: `yMean` must be at least the facing level (the 12 o'clock edge pointing down), and the roll of y against z must be at least the lower roll. Failing either rejects.
3. Otherwise, **sustained**: every sample between the two groups must be above the history floor, so a single dip does not count as a lowered wrist.

Settings → Lower wrist, sliders and Reset, same page implementation as raise wrist:

| Slider | Setting | Default | Range | What it does |
| --- | --- | --- | --- | --- |
| SideL | `sideLevel` | 887 | 512–1024 | `|xMean|` above which the sideways test applies. 887 is sin 60°. |
| SideR | `sideRollDegrees` | 30 | 5–90 | Roll of x against z required with it. |
| Face | `facingLevel` | 724 | 256–1024 | `yMean` below this rejects. 724 is sin 45°. |
| Roll | `rollDegrees` | 30 | 5–90 | Roll of y against z below this rejects. |
| Floor | `historyFloor` | 265 | 0–1024 | Any sample in the window below this rejects. 265 is sin 15°. |

## Non-factors, checked

- CPU load: the 100 ms poll runs in SystemTask (higher priority than the display task) in every state; the check itself is a handful of integer ops. No plausible multi-second starvation path was found in this read.
- AOD: raise from AOD wakes normally (`IsSleeping()` is true in `AODSleeping`, see `doc/DESIGN-lock-screen.md` residual notes).
- The lock screen: affects what happens AFTER the wake (touch rejected until the button), never whether the wake fires. Since 2026-09-14 every wake locks, not only the motion ones; see `doc/DESIGN-lock-screen.md`.
