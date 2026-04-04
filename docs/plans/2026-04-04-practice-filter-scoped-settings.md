# Practice Filter Scoped Settings Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make practice filters follow the existing `All podcasts` and `This podcast` playback-effects scopes.

**Architecture:** Reuse the existing `overrideGlobalEffects` split. Global practice filters remain in `Settings`, while per-podcast practice filters are stored on `Podcast` and written through `PodcastDao`/`PodcastManager`. `PlaybackState` and the player UI then resolve the effective practice filters from the same scope rule already used by playback speed, trim, and volume boost.

**Tech Stack:** Kotlin, Room, Hilt, RxJava/LiveData ViewModel flows, JUnit4, Mockito.

---

### Task 1: Add failing scope-selection tests

**Files:**
- Modify: `modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackStateTest.kt`
- Create or modify: `modules/features/player/src/test/java/au/com/shiftyjelly/pocketcasts/player/viewmodel/PlayerViewModelTest.kt`
- Reference: `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackState.kt`
- Reference: `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/viewmodel/PlayerViewModel.kt`

**Step 1: Write the failing test**

- Add a playback-state test proving:
  - global practice filters are used when `overrideGlobalEffects = false`
  - podcast practice filters are used when `overrideGlobalEffects = true`
- Add a ViewModel test proving:
  - saving from `All podcasts` writes `Settings.globalPracticeFilters`
  - saving from `This podcast` writes podcast-scoped practice filters

**Step 2: Run test to verify it fails**

Run the focused repositories/player test targets.
Expected: FAIL because only the global practice-filter setting exists today.

**Step 3: Write minimal implementation**

- Add the minimum storage and branching logic needed for the new tests.

**Step 4: Run test to verify it passes**

Run the same focused test targets.
Expected: PASS.

### Task 2: Add per-podcast practice-filter persistence

**Files:**
- Modify: `modules/services/model/src/main/java/au/com/shiftyjelly/pocketcasts/models/entity/Podcast.kt`
- Modify: `modules/services/model/src/main/java/au/com/shiftyjelly/pocketcasts/models/db/dao/PodcastDao.kt`
- Modify: `modules/services/model/src/main/java/au/com/shiftyjelly/pocketcasts/models/db/AppDatabase.kt`
- Modify: `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/podcast/PodcastManager.kt`
- Modify: `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/podcast/PodcastManagerImpl.kt`

**Step 1: Write the failing test**

- Extend repository-level tests so a podcast-scoped practice-filter update path is required.

**Step 2: Run test to verify it fails**

Run the focused repositories test target.
Expected: FAIL because `Podcast` has no practice-filter fields or update API.

**Step 3: Write minimal implementation**

- Add podcast entity fields plus a derived `practiceFilters` property.
- Add DAO update queries and repository methods.
- Add the Room migration and bump the DB version.

**Step 4: Run test to verify it passes**

Run the same focused repositories test target.
Expected: PASS.

### Task 3: Wire effective scope into playback and the player UI

**Files:**
- Modify: `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackState.kt`
- Modify: `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/viewmodel/PlayerViewModel.kt`

**Step 1: Write the failing test**

- Add or extend tests so:
  - the practice-filter UI state reflects the active tab/podcast scope
  - toggling the tab changes which stored values are shown
  - saving updates the active player with the correct effective scope

**Step 2: Run test to verify it fails**

Run the focused player/repositories tests.
Expected: FAIL because practice filters currently ignore the tab scope.

**Step 3: Write minimal implementation**

- Resolve effective practice filters from `overrideGlobalEffects`.
- Save to global or podcast-scoped persistence using the existing effects tab selection.
- Keep the player update path unchanged apart from feeding it the correctly scoped filters.

**Step 4: Run test to verify it passes**

Run the same focused test targets.
Expected: PASS.

### Task 4: Verify migrations and regressions

**Files:**
- Modify: tests only if regressions appear

**Step 1: Run focused tests**

Run the exact repositories/player tests covering:
- `PlaybackStateTest`
- ViewModel tests for scoped practice filters
- Existing `ShiftyNoiseAudioProcessorTest`
- Existing `PracticeNoiseUiMapperTest`

**Step 2: Run broader guardrails**

Run the smallest module test tasks that cover the edited code paths.

**Step 3: Manual verification**

- Open Effects for a podcast during playback.
- Change practice filters in `All podcasts`, switch away and back, and confirm the global values persist.
- Switch to `This podcast`, choose different practice filters, and confirm they persist for that podcast only.
- Move between podcasts and confirm the active player follows the same scope rule as playback speed/trim/volume boost.
