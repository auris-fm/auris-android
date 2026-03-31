# Contract: Playback Practice Filters

## Purpose

Define the user-facing and internal interaction contract for selecting and applying listening-practice filters during playback.

## Scope

- Effects controls in player surface.
- ViewModel action/state contract for filter selection.
- Playback manager application behavior and fallback handling.

## UI Control Contract

### Available filter options

| Control ID | User label | Contract value |
|-----------|------------|----------------|
| `filter_off` | Off | `OFF` |
| `filter_noise` | Add noise | `BACKGROUND_NOISE` |
| `filter_masking` | Mask voice | `VOICE_MASKING` |
| `filter_low_pass` | Low-pass | `LOW_PASS` |

Rules:
- Exactly one option is selected at a time.
- Selecting a new option replaces the previous active option.
- If unsupported in current player mode, selection remains visible but marked unavailable with guidance.

## ViewModel Contract

### Input actions

| Action | Payload | Preconditions |
|--------|---------|---------------|
| `SelectPracticeFilter` | `PracticeFilterType` | Playback session exists |
| `DisablePracticeFilter` | none (`OFF`) | Playback session exists |
| `RefreshPracticeFilterAvailability` | player mode | Called when player mode changes |

### Output state

| Field | Type | Meaning |
|-------|------|---------|
| `activeFilter` | enum | Current selected filter |
| `applyStatus` | enum | Apply lifecycle (`PENDING`, `APPLIED`, failure states) |
| `isControlEnabled` | boolean | Whether control input is currently allowed |
| `statusMessage` | string? | Human-readable failure/availability message |

State guarantees:
- New selection emits `PENDING` before final `APPLIED` or failure result.
- Final state emission must occur within 1 second for normal local playback.
- Playback-control state remains interactive while filter transitions execute.

## Playback Application Contract

| Condition | Required behavior |
|-----------|-------------------|
| Local playback + supported filter | Apply selected filter without restarting playback |
| Cast/unsupported playback + practice filter selected | Keep playback running; do not crash; publish unavailable status |
| Rapid repeated selections | Debounce/coalesce to latest selection outcome |
| Processing failure during apply | Preserve playback continuity and emit failure message |

## Analytics/Event Contract

Events to capture per selection:
- selected filter type
- player mode (`LOCAL` vs `CAST/REMOTE`)
- result (`APPLIED`, `FAILED_UNSUPPORTED`, `FAILED_PROCESSING`)
- transition time bucket (`<=1s`, `>1s`)

## Acceptance Mapping

- Supports FR-001 through FR-010 from feature spec.
- Provides contract-level checks for SC-001 through SC-003; SC-004 is measured via product feedback instrumentation.

## Implementation Mapping

- UI contract constants are represented by `PracticeFilterUiContract.kt`.
- ViewModel contract is exposed via `PlayerViewModel.selectPracticeFilter`, `disablePracticeFilter`, and `practiceFilterUiStateLive`.
- Playback application contract is enforced by `PlaybackManager.updatePracticeFilter` with local/remote fallback behavior.
