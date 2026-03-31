# Practice Filter Tuning Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make practice filters tunable from the effects UI, ensure Low-pass is visible/selectable, and keep playback stable.

**Architecture:** Keep practice filter type and tuning strength in playback state, expose through PlayerViewModel UI state, and apply to Shifty audio processors via renderer chain. Use one shared slider (0..100%) with per-filter DSP mapping.

**Tech Stack:** Kotlin, Android XML views, Media3 audio processors, Rx/LiveData state flow.

---

### Task 1: State and API plumbing
- Add practice filter strength to playback state + manager update paths.
- Thread strength through Player interface, SimplePlayer, and ShiftyRenderersFactory/AudioProcessorChain.

### Task 2: DSP tuning behavior
- Add tunable strength properties to Noise/VoiceMasking/LowPass processors.
- Make Noise and VoiceMasking longer and more random with randomized segment timing.

### Task 3: Effects UI controls
- Ensure all practice filter options fit in the toggle row (including Low-pass).
- Add a slider for tuning strength and wire it to ViewModel/PlaybackManager.

### Task 4: Verification
- Update/add processor tests for strength API and alias safety.
- Run focused repositories unit tests, reinstall debug APK, and verify no playback exceptions in logcat.
