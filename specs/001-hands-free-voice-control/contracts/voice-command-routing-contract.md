# Contract: Voice Command Routing and Arbitration

## Purpose
Define deterministic behavior for command capture, recognition arbitration, and execution.

## Inputs
- Voice command utterance context during active session.
- Local recognizer output (intent + confidence + timestamp).
- Cloud recognizer output (intent + confidence + timestamp) when connectivity permits.
- Playback state (`Active`, `Paused`, `Stopped`).

## Routing Rules
1. If playback is `Active`, command intake mode is `Continuous`.
2. If playback is not `Active`, command intake mode is `WakeWord`.
3. For recognized supported core commands, proceed to arbitration and execute one action.
4. Unsupported advanced commands return `Unsupported` with safe user guidance.

## Arbitration Rules
1. Start arbitration window at command intake start.
2. Deadline is 1000ms.
3. If cloud result arrives by deadline, select cloud result.
4. Otherwise, select local result.
5. Execute exactly one selected result.
6. Any late non-selected result MUST be ignored for execution.

## Output
- Single `IntentResolutionOutcome` per command:
  - `Executed`
  - `Unsupported`
  - `FailedSafely`

## Failure Behavior
- Command pipeline failures MUST not crash playback.
- Failure MUST provide user-understandable feedback.
- Failed command MUST preserve current playback continuity.
