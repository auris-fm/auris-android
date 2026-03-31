# Practice Noise Volume Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the current background-noise toggle and advanced tuning controls with a single `Noise volume` slider while keeping live playback updates and environment selection intact.

**Architecture:** Keep `PracticeFilters` as the playback contract, but treat `noiseIntensity` as the only user-facing background-noise control. In the player UI, derive background-noise enabled state from slider progress and keep the other noise processor knobs at stable defaults. In playback, continue using the existing noise processor and environment selector so this remains a bounded UI/state simplification instead of a DSP rewrite.

**Tech Stack:** Kotlin, Android XML views, View Binding, LiveData/Rx-backed ViewModel state, Media3 audio processors, JUnit4.

---

### Task 1: Add failing UI/state tests for single-slider noise control

**Files:**
- Modify: `modules/features/player/src/test/...` (existing player tests, or create focused effects/practice-filter test file)
- Reference: `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt`
- Reference: `modules/services/model/src/main/java/au/com/shiftyjelly/pocketcasts/models/to/PracticeFilterState.kt`

**Step 1: Write the failing test**

- Add coverage for converting UI values into `PracticeFilters`:
  - `Noise volume = 0` produces `isBackgroundNoiseEnabled = false`
  - `Noise volume > 0` produces `isBackgroundNoiseEnabled = true`
  - noise mode selection is preserved

**Step 2: Run test to verify it fails**

Run the smallest relevant player test task or focused test target.
Expected: FAIL because the current UI still depends on `switchPracticeNoise` and the old tuning controls.

**Step 3: Write minimal implementation**

- Extract or adjust the UI-to-state mapping so a single slider drives `noiseIntensity` and derived enabled state.

**Step 4: Run test to verify it passes**

Run the same focused player test target.
Expected: PASS.

**Step 5: Commit**

```bash
git add modules/features/player/src/test modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt
git commit -m "test: cover practice noise volume mapping"
```

### Task 2: Update Effects UI to use one `Noise volume` slider

**Files:**
- Modify: `modules/features/player/src/main/res/layout/fragment_effects.xml`
- Modify: `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt`
- Modify: `modules/services/localization/src/main/res/values/strings.xml` (or the relevant localization module string file)

**Step 1: Write the failing test**

- Add/extend a test that expects the noise section to:
  - omit the background-noise switch
  - show a `Noise volume` label + seekbar
  - stop referencing eventfulness/spatial labels in state updates

**Step 2: Run test to verify it fails**

Run the focused player test target.
Expected: FAIL because the layout and fragment still expose the old controls.

**Step 3: Write minimal implementation**

- Remove the noise switch and advanced tuning views from the layout.
- Add a single seekbar and label for `Noise volume`.
- Update `EffectsFragment` binding/setup/update logic to read and render the new control.
- Keep mask/low-pass toggles unchanged.

**Step 4: Run test to verify it passes**

Run the same focused player test target.
Expected: PASS.

**Step 5: Commit**

```bash
git add modules/features/player/src/main/res/layout/fragment_effects.xml modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt modules/services/localization/src/main/res/values/strings.xml
git commit -m "feat: simplify practice noise controls"
```

### Task 3: Add failing playback regression tests for volume-driven noise behavior

**Files:**
- Modify: `modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessorTest.kt`
- Reference: `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessor.kt`

**Step 1: Write the failing test**

- Add coverage that `intensity = 0f` produces passthrough-equivalent output when noise is enabled by state plumbing.
- Keep or tighten the existing energy-scaling assertion for higher intensity values.

**Step 2: Run test to verify it fails**

Run the focused repositories test target for `ShiftyNoiseAudioProcessorTest`.
Expected: FAIL if the processor still emits audible noise at zero volume.

**Step 3: Write minimal implementation**

- Ensure zero intensity fully suppresses generated background noise without breaking pass-through safety.
- Keep non-zero intensity behavior unchanged except for any minimal adjustments needed to make the zero-volume rule exact.

**Step 4: Run test to verify it passes**

Run the same focused repositories test target.
Expected: PASS.

**Step 5: Commit**

```bash
git add modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessorTest.kt modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessor.kt
git commit -m "test: lock zero-volume practice noise behavior"
```

### Task 4: Verify end-to-end behavior

**Files:**
- Modify: none, verification only unless regressions appear

**Step 1: Run focused tests**

Run the smallest relevant tasks for:
- player module tests that cover practice-noise mapping/UI
- repositories tests covering `ShiftyNoiseAudioProcessorTest`

**Step 2: Run broader guardrails**

Run compile or module test tasks that cover the edited code paths.

**Step 3: Manual verification**

- Open Effects during playback.
- Confirm `Noise volume` at `0%` removes noise.
- Raise `Noise volume` above `0%` and confirm noise starts immediately.
- Switch environments and confirm playback stays uninterrupted.

**Step 4: Commit**

```bash
git add -A
git commit -m "chore: verify practice noise volume simplification"
```
