# Hang repro: server-terminal did not complete the transport flow

Reviewer finding (b4830f10): the standalone composition control hung instead of
passing. Root cause found: `WebSocketCloudTurnTransport.onMessage(text)` emitted
server-originated terminals (`Error`/`Done`) via `emitOrFail`, which only does
`trySend` and never finishes the channel — so the `callbackFlow` never completed
and `routeToCloud` stayed suspended in `events.collect` forever after feedback
selection.

Repro (transport fix reverted to ce732f319):

    ./gradlew :modules:services:repositories:testDebugUnitTest \
      --tests "*WebSocketCloudTurnTransportRefusalTest.flow completes after terminal event*"

- `flow-completion-hang-pre-fix.txt` — exact command output (BUILD FAILED, test timeout)
- `TEST-refusal-pre-fix.xml` — raw JUnit XML: tests="1" failures="1"
  (withTimeout(5s) inside the probe fires: the flow never completes after the
  terminal event)

Fix: server terminals now go through `emitTerminal` (enqueue + channel.close()).
Regression control kept in the suite as
`WebSocketCloudTurnTransportRefusalTest."flow completes after terminal event"`.
