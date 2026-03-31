# Phase 0 Research: Playback Listening Practice Filters

## Research Inputs

- Feature spec: `/Users/dev/code/merlinran/pocket-casts-android/main/specs/001-listening-practice-filters/spec.md`
- Existing player effects UI: `modules/features/player/src/main/java/.../EffectsFragment.kt`
- Existing playback pipeline: `modules/services/repositories/src/main/java/.../ShiftyAudioProcessorChain.kt`, `ShiftyRenderersFactory.kt`, `SimplePlayer.kt`, `CastPlayer.kt`

## Decision 1: Filter selection is single-active at any time

- Decision: Only one listening-practice filter is active at a time; newest selection replaces the previous one.
- Rationale: The feature spec already defines conflict handling via "latest selection takes priority" and this reduces cognitive/load complexity for users and playback processing.
- Alternatives considered:
  - Allow multiple practice filters simultaneously: rejected due to compounded distortion and higher CPU/latency risk.
  - Force explicit "turn off current before selecting next": rejected due to slower workflow during active listening practice.

## Decision 2: Practice filters are session-scoped, not persisted as long-term podcast settings

- Decision: Treat practice-filter mode as active playback session state and reset to off when session ends or user disables filters.
- Rationale: The requested behavior is "during playback" and no requirement asks for cross-episode/device persistence; session scope avoids schema migration and sync complexity.
- Alternatives considered:
  - Persist per-podcast in database: rejected because it introduces migration/sync impact without explicit user value.
  - Persist globally in settings: rejected because transient practice intent is likely context-specific and could surprise users later.

## Decision 3: Local playback supports all three filters; cast playback degrades gracefully

- Decision: Apply noise/masking/low-pass only on local player pipeline. When playback is remote cast (or unsupported path), keep playback running and expose unavailable-state messaging.
- Rationale: Existing cast implementation only applies speed and explicitly does not support trim/boost-level processing, indicating limited remote audio effect capability.
- Alternatives considered:
  - Attempt cast-side DSP parity: rejected because cast receiver control surface does not expose equivalent custom processing in current architecture.
  - Block feature UI while casting: rejected because users still need state visibility and clear fallback behavior.

## Decision 4: Implement effects via Media3 audio processor chain extension

- Decision: Extend `ShiftyAudioProcessorChain` with additional processors/controls for noise injection, voice masking, and low-pass filtering.
- Rationale: Current architecture already centralizes trim-silence and speed handling in an audio processor chain and renderer factory; extension is consistent with current integration points.
- Alternatives considered:
  - Add effect logic in `SimplePlayer` only: rejected because effect processing belongs inside renderer/audio sink chain for frame-level audio handling.
  - Add standalone external DSP service module: rejected as over-scoped for a single playback feature.

## Decision 5: Keep existing Effects surface and add a dedicated practice-filter control group

- Decision: Add a practice-filter control section in the existing Effects UI flow and route state changes through `PlayerViewModel.saveEffects`-adjacent pathway (new state shape as needed).
- Rationale: Users already discover playback effects in this surface; reuse avoids navigation churn and aligns with existing analytics/state update patterns.
- Alternatives considered:
  - New separate screen: rejected due to extra navigation friction.
  - Hidden debug-only control: rejected because this is user-facing functionality.

## Decision 6: Performance and resilience targets for planning

- Decision: Plan against these measurable targets: audible filter transition under 1 second, no playback restart, and control responsiveness maintained under rapid toggles.
- Rationale: Matches spec FR-005/FR-007 and supports success criteria validation.
- Alternatives considered:
  - No explicit target: rejected because acceptance would be ambiguous.
  - Strictly lower threshold (<200ms): rejected as premature without baseline profiling.

## Clarification Status

All previously uncertain technical choices are now resolved for planning. No `NEEDS CLARIFICATION` markers remain.
