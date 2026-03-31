# Quickstart: Playback Listening Practice Filters

## Goal

Validate that listeners can switch among three practice filters during playback with uninterrupted audio and clear active-state feedback.

## Prerequisites

- Android development environment set for this repository.
- A debug build variant installed (recommended: `debugProd`).
- A playable spoken-audio episode in queue.

## Build and test commands

```bash
./gradlew :modules:features:player:testDebugUnitTest
./gradlew :modules:services:repositories:testDebugUnitTest
./gradlew :app:assembleDebugProd
```

## Manual validation flow

1. Start episode playback in the app and open the Effects surface.
2. Select `Add noise` and confirm playback continues with audible noise overlay.
3. Select `Mask voice` and confirm newest filter replaces the previous one.
4. Select `Low-pass` and confirm speech sounds muffled while controls remain responsive.
5. Select `Off` and confirm unfiltered playback resumes without restart.
6. Rapidly toggle between filters and confirm no crash, no stuck buffering, and latest selection wins.
7. If casting is active, select a practice filter and confirm a graceful unavailable message while playback continues.

## Expected outcomes

- Filter transitions complete within ~1 second under normal local playback.
- Playback does not restart when filters change.
- Active filter state is always visible to the listener.
- Unsupported player paths fail gracefully with clear status.

## Regression checks

- Existing speed, trim-silence, and volume-boost controls still operate correctly.
- Playback controls (play/pause/seek) remain responsive with and without active practice filters.

## Story Verification Notes (2026-03-08)

- **US1 (Add noise + Off)**: Implemented through `PracticeFilterType` selection, `ShiftyNoiseAudioProcessor`, and Effects UI toggle group.
- **US2 (Mask voice)**: Implemented through `PracticeFilterType.VOICE_MASKING` and `ShiftyVoiceMaskingAudioProcessor` routing in `ShiftyAudioProcessorChain`.
- **US3 (Low-pass)**: Implemented through `PracticeFilterType.LOW_PASS` and `ShiftyLowPassAudioProcessor` routing in `ShiftyAudioProcessorChain`.

## Command Run Results (2026-03-08)

- `./gradlew :modules:features:player:testDebugUnitTest :modules:services:repositories:testDebugUnitTest`  
  Result: **FAILED (environment/toolchain issue)** in `:modules:services:crashlogging:compileDebugJavaWithJavac` because dependency class files are Java 21 (`class file version 65`) while current runtime/toolchain expects Java 17 (`class file version 61`).
- `./gradlew :app:assembleDebugProd`  
  Result: **FAILED (same environment/toolchain issue)** in `:modules:services:crashlogging:compileDebugProdJavaWithJavac` with the same Java class version mismatch.

## Manual Validation Execution Status

- Manual device validation steps are documented above and remain **pending execution** in this environment.
