# Practice Noise Seam Click Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove click-like artifacts from looping practice background-noise bed samples without changing the existing feature surface.

**Architecture:** Keep the current sample-backed environments and fix the loop seam inside `ShiftyNoiseAudioProcessor` rather than replacing assets or redesigning the mixer. Add a regression test that renders enough audio to cross loop boundaries and fails when the loop seam creates an outlier discontinuity compared with normal adjacent-sample deltas.

**Tech Stack:** Kotlin, JUnit4, Media3 `AudioProcessor`, PCM16 WAV sample playback

---

### Task 1: Add seam-click regression coverage

**Files:**
- Modify: `modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessorTest.kt`

**Step 1: Write the failing test**

Add a test that renders enough sample-backed audio to wrap a bed clip and compares the largest adjacent-sample delta against the clip's normal delta distribution. The test should fail for the current hard-wrap implementation.

**Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:repositories:testDebugUnitTest --tests "*ShiftyNoiseAudioProcessorTest.sample backed bed loop avoids seam sized discontinuities*"`

Expected: FAIL because the current bed loop produces a seam spike above the asserted threshold.

### Task 2: Make bed looping seamless

**Files:**
- Modify: `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessor.kt`

**Step 1: Write minimal implementation**

Adjust bed sample playback so that:
- interpolation wraps from the last sample to the first sample instead of clamping at the clip end
- a short crossfade window is applied around bed-loop wraps so the transition is blended instead of hard-reset

Keep overlay behavior unchanged.

**Step 2: Run test to verify it passes**

Run: `./gradlew :modules:services:repositories:testDebugUnitTest --tests "*ShiftyNoiseAudioProcessorTest.sample backed bed loop avoids seam sized discontinuities*"`

Expected: PASS

### Task 3: Verify no regressions in nearby behavior

**Files:**
- Test: `modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessorTest.kt`

**Step 1: Run the focused test class**

Run: `./gradlew :modules:services:repositories:testDebugUnitTest --tests "*ShiftyNoiseAudioProcessorTest*"`

Expected: PASS
