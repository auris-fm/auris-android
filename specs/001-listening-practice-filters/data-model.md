# Phase 1 Data Model: Playback Listening Practice Filters

## Overview

The feature introduces session-level playback practice filter state that works alongside existing playback effects (speed, trim, volume boost) without requiring new long-term synced entities.

## Entities

### 1) PracticeFilterType

Represents which listening-practice effect is selected.

Fields:
- `value` (enum): `OFF`, `BACKGROUND_NOISE`, `VOICE_MASKING`, `LOW_PASS`
- `displayName` (string): localized user-facing label
- `availability` (enum): `AVAILABLE`, `UNAVAILABLE_LOCAL_ONLY`, `UNAVAILABLE_CURRENT_PLAYER`

Validation rules:
- Must be one of the four enum values.
- `OFF` must always be available.

### 2) ActivePracticeFilterState

Represents currently applied practice-filter mode for the active playback session.

Fields:
- `activeFilter` (`PracticeFilterType`): currently selected filter
- `lastChangedAt` (timestamp): when active filter last changed
- `applyStatus` (enum): `APPLIED`, `PENDING`, `FAILED_UNSUPPORTED`, `FAILED_PROCESSING`
- `message` (string, optional): user-visible explanation for fallback/failure

Validation rules:
- Only one `activeFilter` can be active at a time.
- `message` is required when `applyStatus` is a failure state.

### 3) PlaybackPracticeSession

Represents the runtime context for applying and switching filters while an episode is playing.

Fields:
- `sessionId` (string): runtime identifier tied to current playback session
- `episodeUuid` (string): currently playing episode identifier
- `playerMode` (enum): `LOCAL`, `CAST`, `OTHER_REMOTE`
- `isPlaying` (boolean): playback active/inactive
- `filterState` (`ActivePracticeFilterState`): current filter data

Validation rules:
- If `playerMode` is not local and chosen filter is unsupported, `applyStatus` must become an unsupported failure state while playback continues.
- If `isPlaying` becomes false and session is terminated, `activeFilter` resets to `OFF`.

## Relationships

- `PlaybackPracticeSession` contains one `ActivePracticeFilterState`.
- `ActivePracticeFilterState.activeFilter` references one `PracticeFilterType`.
- A listener has at most one active `PlaybackPracticeSession` at a time in the player context.

## State Transitions

1. `OFF/APPLIED` -> `BACKGROUND_NOISE/PENDING` -> `BACKGROUND_NOISE/APPLIED`
2. `BACKGROUND_NOISE/APPLIED` -> `VOICE_MASKING/PENDING` -> `VOICE_MASKING/APPLIED`
3. `ANY/APPLIED` -> `LOW_PASS/PENDING` -> `LOW_PASS/APPLIED`
4. `ANY/PENDING` -> `ANY/FAILED_UNSUPPORTED` (unsupported player mode) -> remain playing with previous or OFF filter
5. `ANY/APPLIED` -> `OFF/PENDING` -> `OFF/APPLIED`
6. Session end -> force `OFF/APPLIED`

## Derived Behavior

- "Latest selection wins": when a new filter is selected, prior pending/apply state is superseded.
- Filter changes are expected to settle to `APPLIED` within 1 second under normal local playback conditions.

## Implementation Mapping

- `PracticeFilterType` and `PracticeFilterApplyStatus` are implemented in `PracticeFilterState.kt`.
- Runtime playback state fields are carried on `PlaybackState` (`practiceFilterType`, `practiceFilterApplyStatus`, `practiceFilterMessage`).
