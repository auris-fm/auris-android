# Task 17 Report: Voice Control Settings Status

## Status

DONE_WITH_CONCERNS

- Commit: `2e140972f` — `feat(voice): show voice control status in settings`
- Base: `609241d20`

## What changed

1. Created `VoiceControlStatus.kt` with the pure `voiceControlStatus(mode, gate, micExposure, permissionGranted)`
   function. It evaluates the brief's rows in order (permission, Setup, Conflicts, context, route exposure,
   mode) and returns the brief's exact text for the first match.
2. Created `VoiceControlStatusTest.kt` (19 tests) covering every row and the precedence cases.
3. Rendered the status in `VoiceControlSettingsPage` as a new `SettingRow(primaryText = "Status", ...)`
   after the Speech Model row. The fragment now injects `VoiceControlGate`, `AudioRouteMonitor` and
   `ListeningModePolicy`, collects their state, reads `RECORD_AUDIO` permission, and passes the resolved
   text into the page. Preview updated with a `statusText`.
4. Added `companion object { const val ID = ... }` to `ModelsReadyCondition`, `NotOnCallCondition`,
   `NotCastingCondition`, `BatteryOkCondition`, `OtherAppPlayingCondition`, `DeviceSupportedCondition`
   and changed each `override val id` to use `ID`. This is behavior-preserving; the packet asserted these
   constants already existed, but only `EnabledByUserCondition` and `AppInForegroundCondition` had them.

## Files touched

- `modules/features/settings/src/main/java/.../settings/voice/VoiceControlStatus.kt` (new)
- `modules/features/settings/src/main/java/.../settings/voice/VoiceControlSettingsFragment.kt`
- `modules/features/settings/src/test/java/.../settings/voice/VoiceControlStatusTest.kt` (new)
- `modules/services/voice/src/main/kotlin/.../gate/conditions/{ModelsReadyCondition,NotOnCallCondition,NotCastingCondition,BatteryOkCondition,OtherAppPlayingCondition,DeviceSupportedCondition}.kt`

## Tests added

`VoiceControlStatusTest`, 19 tests:

- Every table row: permission, disabled-in-settings, model download (both `Blocked` and `Unknown`/loading),
  generic setup, phone call, casting, battery saver, other-app-playing, no context, `NoMic`, `Exposed`,
  `Continuous`, `WakeWord`, otherwise `Off`.
- Precedence: permission beats everything; blocked Setup beats blocked conflict; blocked conflict beats
  the missing-microphone row; the built-in-microphone row needs an allowed context.

## Commands run and observed results

| Command | Result |
|---|---|
| `./gradlew :modules:features:settings:testDebugUnitTest --tests VoiceControlStatusTest` (before implementation) | FAIL — `Unresolved reference 'voiceControlStatus'` ×16 (expected red) |
| `./gradlew :modules:features:settings:testDebugUnitTest --tests VoiceControlStatusTest` (after implementation) | BUILD SUCCESSFUL |
| `./gradlew :modules:features:settings:testDebugUnitTest --tests VoiceControlStatusTest :modules:services:voice:testDebugUnitTest --tests '*Condition*' --tests '*Gate*'` | BUILD SUCCESSFUL |
| Test report XML for `VoiceControlStatusTest` | `tests="19" skipped="0" failures="0" errors="0"` |
| `./gradlew spotlessCheck` | Initially FAILED (formatting), `spotlessApply` then `spotlessCheck` → BUILD SUCCESSFUL |
| commit (runs spotless hook) | `2e140972f` created |

## Self-review findings

- The status function uses `isNotAllowed` (any state other than `Allowed`, absent rule ignored). This means a
  conflict condition in its transient `Unknown` state is labelled with that condition's blocker text rather
  than falling through to "Active". This is deliberate: reporting "Active" while the gate is not allowed
  would be wrong, and `ModelsReadyCondition` reports `Unknown("models_loading")` for the in-progress state
  that the brief maps to "Blocked: model download in progress", so `Unknown` must be treated as non-passing.
- The gate group flags (`setupPassed`, `contextPassed`) back the generic "Blocked: setup" and
  "Inactive: no playback or foreground context" rows, so no `allowed = false` state can produce a false
  "Active".
- I did not use `gate.allowed` directly; the per-group checks and route checks cover it. Verified by
  construction that every `allowed = false` path hits a row before the mode fallback.

## Concerns

1. **Out-of-scope file edits (intentional):** the packet listed `ModelsReadyCondition.ID`,
   `NotOnCallCondition.ID`, `NotCastingCondition.ID`, `BatteryOkCondition.ID`,
   `OtherAppPlayingCondition.ID` as existing constants, but they did not exist. I added them (plus
   `DeviceSupportedCondition.ID`) so the status mapping references canonical IDs instead of duplicated
   string literals. These are behavior-preserving, but they are outside the brief's file list.
2. **Permission is read only at composition.** `checkSelfPermission` is evaluated during composition and
   is not lifecycle-refreshed, so after the user grants the permission and returns to this screen the
   Status row could stay "Blocked: microphone permission required" until another recomposition. The brief
   did not specify live permission observation; a lifecycle observer/resume hook would close this.
3. **Unknown conflict states** get a specific blocker label as described above; accepted and documented
   in the implementation comment.
4. Task 18 (full build/verify) is not run here; only the settings test, the voice gate/condition tests,
   and spotless were run.
