# Wake gestures: raise-wrist and shake, the algorithms and every gate in front of them

Source read 2026-08-12 against the `clock-sync` branch (base 1.16.1); all claims cited to file:line.

## The pipeline

- SystemTask polls the BMA421 accelerometer every **100 ms** in every state, awake or asleep (`stateUpdatePeriod`, `SystemTask.cpp:198`, called at `:445-446`).
- `MotionController::Update` (`MotionController.cpp:46`) pushes x/y/z into 8-sample ring buffers (`histSize = 8`, `MotionController.h:107`), so the buffers span the last **~800 ms**.
- `GetAccelStats` (`MotionController.cpp:83`) averages the **newest 2 samples** ("now", ~200 ms) and the **oldest 2 samples** ("prev", ~600–800 ms ago) (`numHistory = 2`, `MotionController.h:89`).
- Units: 1 g ≈ 1024. The thresholds are sines of angles: 724 = sin 45°, 887 = sin 60°, 384 ≈ sin 22°, 265 = sin 15°, 64 ≈ sin 3.6°.

## The wake gates, in order (`SystemTask.cpp:518-541`)

1. **Notification mode Sleep (the quick-settings moon) disables BOTH raise and shake wake entirely** (`:525`). Silent (bell Off) does NOT — it only mutes chimes/buzzes.
2. The Raise Wrist toggle in Settings → Wake Up must be on.
3. `ShouldRaiseWake()` (`MotionController.cpp:113-129`), ALL must hold:
   - **Level**: `|xMean| ≤ 384` — the 3–9 o'clock axis within ~22° of level. A sideways-tilted wrist fails.
   - **Still**: `yVariance ≤ 56²` over the newest ~200 ms — the gesture must END in stillness. Continuous motion re-fails this every 100 ms tick. (Near-vertical poses additionally require z to be calm.)
   - **Facing you**: `yMean ≤ -64` — the 12 o'clock edge raised at least ~3.6° toward the viewer.
   - **Rolled**: `DegreesRolled(now vs ~600 ms ago) < -45°` — the face must have ROTATED toward you by ≥45° around the forearm axis within the window. The math is only meaningful when the readings are gravity (`MotionController.cpp:14`): while the arm is accelerating, the tilt estimate is garbage in random directions.

So the detector's signature is **"one smooth ≥45° roll of the forearm, settling into a still, level, slightly-toward-you pose"** — a rotate-and-settle detector, not a motion detector.

## Why aggressive waving does not wake it (expected behavior)

Three independent gates reject waving: the stillness gate (variance stays high the whole time), the roll gate (waving is swinging, not rolling, and under acceleration the angle math is meaningless anyway), and often the level gate (arm ends up sideways). Waving harder makes it worse, not better. The gesture that works: drop the wrist, one smooth rotate-up to reading position, hold for a beat; the wake fires within ~100–300 ms of settling.

**Shake-to-wake is the feature for the "wave at it" instinct**: a separate toggle in Settings → Wake Up (can coexist with Raise Wrist), comparing an EMA of motion speed (`MotionController.cpp:66-72`) against the adjustable threshold in Settings → Shake Threshold. Note: per the lock-screen design, a shake wake comes up UNLOCKED (only raise-wrist locks).

## Shake-to-wake: the algorithm (`MotionController.cpp:66-72`)

Runs on the same 100 ms poll and shares the notification-Sleep kill switch with raise-wake (`SystemTask.cpp:525`); its own toggle lives in Settings → Wake Up.

- Each tick computes a "speed": `|Δz + Δy/2 + Δx/4| × 100 / elapsed_ticks`, where each Δ is the newest accelerometer sample minus the one ~800 ms ago (full ring buffer span). The ×100/elapsed factor normalizes poll jitter (~1× at the 100 ms cadence).
- Axis weights make the face normal dominant: z (out of the watch face) counts full, y (along the band) half, x (across) quarter. Flapping the face back and forth registers strongest; sideways sawing weakest.
- The speed feeds an EMA: `accumulatedSpeed = 0.2·speed + 0.8·accumulatedSpeed` (integer form). One sharp flick gets diluted to 20% and decays ×0.8 per tick (mostly gone in ~0.5 s); sustained shaking for ~300–500 ms ramps the accumulator up.
- Wake fires when `accumulatedSpeed > shakeWakeThreshold` (persisted setting, default 150, adjustable in Settings → Shake Threshold; `Settings.h:399`). Nothing resets the accumulator on wake — it just decays.

Operationally: two or three brisk shakes across half a second, ideally rotating the face, beat one violent flick. A shake wake comes up UNLOCKED (only raise-wrist sets the lock).

## Tuning knobs (firmware, if ever wanted)

All in `ShouldRaiseWake`: the 45° roll threshold (`rollDegreesThresh`), the stillness variance (`varianceThresh = 56²`), the end-pose angles (`xThresh`, `yThresh`). Loosening the roll or variance thresholds makes it trigger easier at the cost of false wakes (and with the wrist-raise lock feature, every false raise-wake also locks the screen).

## Non-factors, checked

- CPU load: the 100 ms poll runs in SystemTask (higher priority than the display task) in every state; the check itself is a handful of integer ops. No plausible multi-second starvation path was found in this read.
- AOD: raise from AOD wakes normally (`IsSleeping()` is true in `AODSleeping`, see `doc/DESIGN-lock-screen.md` residual notes).
- The wrist-raise lock: affects what happens AFTER the wake (touch rejected until button), never whether the wake fires.
