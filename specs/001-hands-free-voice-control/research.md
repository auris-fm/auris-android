# Research: Hands-Free Voice Control

## Decision: Parallel local/cloud recognition with bounded arbitration
- Decision: Run local and cloud recognizers in parallel when online; wait up to 1 second,
  choose cloud only if returned in time, otherwise choose local; execute exactly one action.
- Rationale: Preserves deterministic behavior and avoids double-actions while still benefiting
  from cloud quality when latency is acceptable.
- Alternatives considered: Local-first immediate execution with later override (rejected due to
  unintended second actions); cloud-only online (rejected due to latency/outage risk).

## Decision: Listening mode tied to playback state
- Decision: Use continuous listening only during active playback; use wake-word activation when
  playback is inactive.
- Rationale: Balances hands-free convenience with privacy, battery, and accidental-trigger risk.
- Alternatives considered: Always-on regardless of playback state (rejected due to battery/privacy
  impact); wake-word only always (rejected because it weakens in-playback ergonomics).

## Decision: Retention policy with opt-out
- Decision: Retain anonymized voice samples up to 30 days; retention enabled by default with
  user opt-out that applies immediately to newly captured samples.
- Rationale: Supports iterative quality improvements while preserving user control and minimizing
  long-term storage exposure.
- Alternatives considered: No retention (rejected because it limits quality analysis);
  non-optional retention (rejected for privacy/trust risk).

## Decision: Core command set and advanced-command posture
- Decision: Prioritize reliable support for core commands (skip, rewind, speed up/down, next
  episode and equivalent intents); unsupported advanced commands fail safely with guidance.
- Rationale: Meets MVP reliability goals and prevents scope creep into poorly specified advanced
  behaviors.
- Alternatives considered: Broad advanced command support in phase one (rejected due to unclear
  scope and elevated correctness risk).

## Decision: Verification strategy
- Decision: Validate via spotless, targeted unit tests in touched modules, and instrumentation
  for end-to-end command routing where state transitions are critical.
- Rationale: Fast feedback for logic correctness plus confidence on real Android lifecycle/input
  behavior.
- Alternatives considered: Unit-only validation (rejected for lifecycle risk);
  instrumentation-only validation (rejected for slower iteration and lower developer throughput).

## Clarification Resolution Status
All technical context fields are concretely resolved; no `NEEDS CLARIFICATION` items remain.
