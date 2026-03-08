# Quickstart: Hands-Free Voice Control

## Preconditions
- Current branch: `001-hands-free-voice-control`
- Android build environment configured

## Verification Commands

1. Formatting gate
- `./gradlew spotlessCheck`

2. Deeplink and command-entry tests
- `./gradlew :modules/services/deeplink:testDebugUnitTest`

3. Playback/repository behavior tests
- `./gradlew :modules/services/repositories:testDebugUnitTest`
- Targeted voice-control unit tests:
  `./gradlew :modules:services:repositories:testDebugUnitTest --tests "au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol.*"`

4. App-level tests for orchestration paths
- `./gradlew :app:testDebugUnitTest`

5. Instrumented validation for lifecycle/input integration
- `./gradlew :app:connectedDebugAndroidTest`
- Note: `CoreVoiceControlsTest`, `ConnectivityArbitrationTest`, and `RetentionOptOutFlowTest`
  are scaffolded and currently marked `@Ignore` until device-level voice/network harnesses are
  available.

6. Dependency health (only if dependency graph changed)
- `./gradlew buildHealth`

## Manual Validation Checklist
- Active playback uses continuous command intake.
- Inactive playback requires wake-word to enter command-ready mode.
- Online arbitration chooses cloud result when available before 1 second.
- Fallback chooses local result when cloud misses 1-second deadline.
- Late cloud result does not override already-executed local action.
- Retention default-on behavior is observable and user opt-out takes effect immediately.

## Expected Outcome
- All automated checks pass.
- Command execution remains single-action per utterance.
- No playback regression under degraded or offline connectivity.
