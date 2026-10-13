# Task 16 Report — Route Acquisition (Bluetooth and BLE)

## Status

DONE_WITH_CONCERNS. TDD followed: the failing `RouteAcquisitionTest` was written and
confirmed failing (compile error: missing classes), then implemented and passing.

## What changed

Bluetooth acquisition moved out of capture start and into a bounded, cancellable
component. A Bluetooth candidate (`AudioRoute.BluetoothA2dpOnly`, `Exposed`) can no
longer start capture, so opening SCO at capture start deadlocked the mode at `Off`.
Now the service acquires first, then starts the engine on the already-opened stream.

- **`RouteAcquisition`** (new) owns the attempt: select routing (`startBluetoothSco()`
  for classic `HFP`/`SCO`, `setCommunicationDevice()` for BLE `LE Audio`), wait
  bounded for the platform to actually report the selection (`isBluetoothScoOn()` /
  `getCommunicationDevice()`), open the Oboe input stream without starting it, confirm
  the reported device resolves to a wired, BLE, or `SCO` headset microphone
  (`TYPE_BUILTIN_MIC`, `kUnspecified`/0, and unresolvable ids are unconfirmed), then
  start the stream. Any blocking gate question cancels the attempt; cancellation,
  failure, and gate loss close the stream and release routing.
- **`AndroidRouteAcquisitionPlatform`** (new) implements the platform seam with
  `AudioManager` + `MicrophoneCapture`/JNI.
- **`OboeAudioCapture`** now requests `oboe::AudioApi::AAudio` and exposes
  `getDeviceId()`. The JNI bridge gains `nativeOpenInputCapture` /
  `nativeStartInputStream` / `nativeCloseInputCapture`, and
  `nativeStartCaptureAndVad` reuses a stream already opened by acquisition.
- **`VoiceAsrEngine`** no longer opens or closes SCO; it starts the capture pipeline,
  which attaches VAD to the acquired stream.
- **`VoiceControlService`** runs acquisition when the route is `BluetoothA2dpOnly` and
  every non-route gate question passes (`gate.state.value.allowed`), then starts the
  engine. A pending attempt is cancelled when the candidate disappears. Failures log
  `mic_acquisition_skipped` with reason `route_acquisition_failed` / `route_acquisition_timeout`.
  `stopEngine()` cancels any attempt and releases routing.
- **DI** provides the platform and `RouteAcquisition` singletons.

## Files touched

Kotlin (main):
- `modules/services/voice/src/main/kotlin/.../voicecontrol/route/RouteAcquisition.kt` (new)
- `modules/services/voice/src/main/kotlin/.../voicecontrol/route/AndroidRouteAcquisitionPlatform.kt` (new)
- `modules/services/voice/src/main/kotlin/.../voicecontrol/audio/MicrophoneCapture.kt`
- `modules/services/voice/src/main/kotlin/.../voicecontrol/audio/OboeCaptureEngine.kt`
- `modules/services/voice/src/main/kotlin/.../voicecontrol/engine/VoiceAsrEngine.kt`
- `modules/services/voice/src/main/kotlin/.../voicecontrol/service/VoiceControlService.kt`
- `modules/services/voice/src/main/kotlin/.../voicecontrol/di/VoiceControlModule.kt`

Kotlin (test):
- `modules/services/voice/src/test/kotlin/.../voicecontrol/route/RouteAcquisitionTest.kt` (new)
- `modules/services/voice/src/test/kotlin/.../voicecontrol/engine/VoiceAsrEngineTest.kt`

C++:
- `modules/services/voice/src/main/cpp/OboeAudioCapture.h`
- `modules/services/voice/src/main/cpp/OboeAudioCapture.cpp`
- `modules/services/voice/src/main/cpp/jni_bridge.cpp`

## Tests added

`RouteAcquisitionTest` (13 tests), covering every brief scenario:
- classic candidate: `startBluetoothSco()` first, no stream opened/started before the
  link is up, `Acquired` and start only once `isBluetoothScoOn` is true
- BLE candidate: `setCommunicationDevice(device)` attempted; capture only after
  `getCommunicationDevice()` reports it (availability alone never permits)
- stream reports `TYPE_BUILTIN_MIC`, `kUnspecified`, or an unresolvable id → stream
  closed, `Failed("route_acquisition_failed")`, routing released
- gate blocks while pending → `Cancelled`, no capture, routing released
- late completion after cancellation → no capture, routing released
- stream fails to start → closed, routing released
- built-in speaker / isolated wired headset → `NotNeeded`, zero platform interactions
- candidate route discovery and no-candidate failure; timeout releases routing

`VoiceAsrEngineTest`: removed the ten obsolete SCO tests (the engine no longer owns
SCO) and added `start with BluetoothA2dpOnly starts capture`.

## Commands run and observed results

1. `./gradlew :modules:services:voice:testDebugUnitTest --tests RouteAcquisitionTest`
   - before implementation: FAIL (compile errors: unresolved `RouteAcquisition`,
     `BluetoothCandidate`, `BluetoothTransport`, `AcquisitionResult`)
   - after implementation: **BUILD SUCCESSFUL** (13 tests)
2. `./gradlew :modules:services:voice:testDebugUnitTest`
   - **BUILD SUCCESSFUL** (470 tests)
3. `./gradlew :modules:services:voice:assembleDebug`
   - **BUILD SUCCESSFUL** (C++ compiled for arm64-v8a and x86_64)
4. `./gradlew :app:compileDebugKotlin`
   - **BUILD SUCCESSFUL** (Hilt graph resolves)
5. `./gradlew spotlessApply` then `spotlessCheck` (also run by the pre-commit hook)
   - **BUILD SUCCESSFUL**

Commit: `f9c36033d` feat(voice): acquire the external microphone before capture

## Self-review findings

- The post-open gate re-check is implemented (a gate that blocks while the stream is
  opening cancels before `startInputStream()`), though the test suite exercises gate
  loss during selection and cancellation rather than that exact interleaving.
- `release()` closes any open stream unconditionally but only stops SCO / clears the
  communication device when routing was actually acquired, so an engine stop on a
  wired route does not disturb communication routing.
- The engine no longer inspects the route at all; it trusts the service to start it
  only for an isolated/acquired route. `VoiceAsrEngineTest` still starts capture for
  `Speaker` at the engine level; capture eligibility remains the policy/service's
  responsibility.

## Concerns

1. **BLE candidate may be unreachable in production.** `AndroidAudioRouteMonitor.classifyRoute`
   maps a BLE-headset output with no BLE input to `Headset(hasMicrophone = false)` =
   `NoMic`, not `BluetoothA2dpOnly`. The service only triggers acquisition for
   `BluetoothA2dpOnly`, so the BLE acquisition path is implemented and unit-tested but
   may not run on a real BLE-only route until the monitor classifies that case as a
   candidate. `AndroidAudioRouteMonitor` was not in the brief's file list, so I did not
   change it; this needs a follow-up decision (Task 18 verification may surface it).
2. `RouteAcquisition` is a singleton holding `routingAcquired`; the service serializes
   attempts (one `acquisitionJob` at a time), so no overlap, but it is shared state.
3. If a device lacks a usable AAudio backend, Oboe reports `kUnspecified` and
   acquisition fails closed (capture stays off), which is the specified behavior but
   means those devices never listen.
4. No on-device/instrumented verification was run; the C++ path is only compile-verified.

---

# Task 16 Fix Report — Review round 1 (Important findings 1 & 2)

## Status

DONE_WITH_CONCERNS. TDD followed: the new/extended tests were written and confirmed
failing, then implemented and confirmed passing. No subagents dispatched.

## Findings addressed

### Important 1 — BLE acquisition unreachable (spec)

`AndroidAudioRouteMonitor.classifyRoute` collapsed a BLE output with no selected BLE
input into `Headset(hasMicrophone = false)` → `NoMic`, so the service (which only runs
acquisition for `BluetoothA2dpOnly`) never reached the LE Audio path.

Fix (Ruling A):
- Split the old `hasWiredOrBleOutput || hasWiredOrBleInput` branch into explicit
  `hasWiredInput` / `hasBleInput` / `hasWiredOutput` / `hasBleOutput` cases.
- An already-selected wired or BLE input → `Headset(hasMicrophone = true)`.
- Wired output with no wired mic (headphones) → `Headset(hasMicrophone = false)`
  (NoMic) — wired cannot be acquired.
- BLE output with no selected BLE input → `BluetoothA2dpOnly`, an acquirable
  candidate distinct from NoMic. `toMicExposure()` is unchanged, so this stays
  `Exposed` (blocking) until the external input is confirmed.
- A supporting production gap surfaced while wiring this up: the BLE candidate id is
  the *output* device id (`AndroidRouteAcquisitionPlatform.bluetoothCandidates` only
  returns it while no BLE input exists), but `setCommunicationDevice()` looked the id
  up in `GET_DEVICES_INPUTS` only, so it always returned null for a BLE candidate.
  `setCommunicationDevice()` now resolves the id from inputs first, then falls back to
  `GET_DEVICES_ALL` (`communicationDevice()`), which is what `setCommunicationDevice`
  accepts for `TYPE_BLE_HEADSET` output. `AndroidAudioRouteMonitor` was explicitly in
  scope; this platform change is the minimum needed for Ruling A to work on device.

### Important 2 — `release()` closes the live engine stream (quality)

`VoiceControlService.stopEngine()` called `routeAcquisition.release()` before
`voiceAsrEngine.get().stop()`, and `release()` unconditionally called
`platform.closeInputStream()`, deleting the engine's live `gCapture` from another
thread even on routes where acquisition never ran.

Fix (Ruling B):
- `RouteAcquisition` now tracks stream ownership: `openedStream` is set after
  `openInputStream()`, and `handOffStream()` marks the stream as owned by the capture
  engine once capture starts. `release()` closes the stream only while
  `openedStream && !streamHandedOff`; otherwise it leaves it alone and only undoes
  acquired routing. `release()` remains idempotent and clears the flags.
- `VoiceControlService.acquireCandidate` calls `routeAcquisition.handOffStream()`
  after a successful `startEngine()` (when `engineStarted` is true). If the engine did
  not actually start (backend null / not ready / start failed), or the gate blocked
  before `startEngine`, it calls `release()` so the stream is still closed.
- `stopEngine()` now stops the engine first and releases acquisition routing second,
  so release can never race a live capture.

### Minor — dead code

Removed `@Suppress("UNREACHABLE_CODE")` and the unreachable `Selection.TIMEOUT` after
`while (true)` by rewriting the bounded wait to a typed loop
(`var outcome: Selection? = null; while (outcome == null) { ... }`), so the compiler
no longer needs the suppression.

## Files changed

- `modules/services/voice/src/main/kotlin/.../voicecontrol/route/AndroidAudioRouteMonitor.kt`
- `modules/services/voice/src/main/kotlin/.../voicecontrol/route/AndroidRouteAcquisitionPlatform.kt`
- `modules/services/voice/src/main/kotlin/.../voicecontrol/route/RouteAcquisition.kt`
- `modules/services/voice/src/main/kotlin/.../voicecontrol/service/VoiceControlService.kt`
- `modules/services/voice/src/test/kotlin/.../voicecontrol/route/AndroidAudioRouteMonitorTest.kt`
- `modules/services/voice/src/test/kotlin/.../voicecontrol/route/RouteAcquisitionTest.kt`

## Tests added/adjusted (written first, confirmed failing)

`AndroidAudioRouteMonitorTest` (now 14 tests, +2):
- `ble headset output without a selected ble input is an acquirable candidate` —
  asserts `BluetoothA2dpOnly` and `toMicExposure() == Exposed`.
- `ble headset output with a selected ble input is headset with mic` — guards against
  over-correcting the input case.

`RouteAcquisitionTest` (now 17 tests, +4):
- `release without an acquisition attempt never closes the engine-owned stream` —
  the exact wired/speaker route bug.
- `release after the engine takes over the stream does not close it` — handoff then
  release; stream untouched, routing released.
- `release before the engine takes over closes the started stream` — success but no
  engine start (e.g. gate blocked) still closes.
- `release after a failed acquisition still closes the opened stream` — failure closes
  once and a second release is a no-op.

## Exact commands and observed output

1. Failing run (before implementation):
   `./gradlew :modules:services:voice:testDebugUnitTest --tests 'au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitorTest' --tests 'au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisitionTest'`
   Observed: `FAILED` — 4 failures:
   - `AndroidAudioRouteMonitorTest > ble headset output without a selected ble input is an acquirable candidate FAILED` (`AssertionError` at `AndroidAudioRouteMonitorTest.kt:146`)
   - `RouteAcquisitionTest > release without an acquisition attempt never closes the engine-owned stream FAILED`
   - `RouteAcquisitionTest > release after a failed acquisition still closes the opened stream FAILED`
   - `RouteAcquisitionTest > release after a successful acquisition does not close the handed-off stream FAILED`
   `30 tests completed, 4 failed`; `BUILD FAILED`.

2. Passing run (same command, after implementation): **BUILD SUCCESSFUL**.

3. Full module suite:
   `./gradlew :modules:services:voice:testDebugUnitTest`
   Observed: **BUILD SUCCESSFUL**. XML totals: 466 tests, 0 skipped, 0 failures,
   0 errors. Per class: `RouteAcquisitionTest` 17/0/0, `AndroidAudioRouteMonitorTest`
   14/0/0.

4. Native build:
   `./gradlew :modules:services:voice:assembleDebug --rerun-tasks`
   Observed: `configureCMakeDebug[x86_64]`, `configureCMakeDebug[arm64-v8a]`,
   `buildCMakeDebug[x86_64]`, `buildCMakeDebug[arm64-v8a]`,
   `:modules:services:voice:assembleDebug`; **BUILD SUCCESSFUL**. (C++ unchanged this
   round; rerun to prove it still compiles.)

5. App/Hilt graph:
   `./gradlew :app:compileDebugKotlin`
   Observed: `:app:compileDebugKotlin`; **BUILD SUCCESSFUL** (Hilt graph resolves).

6. Formatting:
   `./gradlew spotlessApply` then `./gradlew spotlessCheck`
   Observed: **BUILD SUCCESSFUL** (the pre-commit hook also ran `spotlessCheck`).

Commit: `5134db919` fix(voice): make BLE route acquisition reachable and stop releasing the live stream

## Self-review findings

- The `release()` invariant holds across repeat calls: after a failure the stream is
  closed once and `openedStream` is cleared; after handoff the stream is never touched.
- `handOffStream()` is a no-op unless `openedStream` is set, so the wired/speaker
  start path cannot accidentally mark an engine stream as "acquisition-owned".
- The engine's flow `finally` (`nativeStopCaptureAndVad`) remains the sole teardown of
  a handed-off stream, which is why `stopEngine()` now stops before releasing.
- The classification branch order preserves every pre-existing case:
  wired mic → headset(true); wired headphones → NoMic; A2DP+SCO-active → headset(true);
  A2DP/SCO → BluetoothA2dpOnly; speaker; unknown.

## Concerns

1. `setCommunicationDevice()` now accepts an output `TYPE_BLE_HEADSET` device via a
   `GET_DEVICES_ALL` fallback. This is the documented device set for that API, but it
   is only compile-verified here; on-device BLE verification (Task 18) should confirm
   the route reaches `Acquired` and captures on a real LE Audio headset.
2. `handOffStream()` is called when `startEngine()` reports `engineStarted`. The
   engine only attaches the acquired stream once its coroutine actually collects the
   capture flow; if the second `backend.ensureReady()` inside `VoiceAsrEngine.start`
   were to fail after the service's own readiness check, the handed-off stream would
   be left for the engine's teardown rather than closed by `release()`. This window is
   implausible (readiness is checked immediately before) and unchanged from the prior
   ownership model in spirit, but it is not covered by a service-level test.
3. No service-level unit test exercises `stopEngine` ordering; the ordering is
   verified by code review and the ownership tests in `RouteAcquisitionTest`, not by a
   test that drives `VoiceControlService` (no such harness exists for this path).
