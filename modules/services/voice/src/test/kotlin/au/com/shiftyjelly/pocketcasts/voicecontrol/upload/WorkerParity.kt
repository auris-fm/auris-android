package au.com.shiftyjelly.pocketcasts.voicecontrol.upload

/**
 * Marks the real-Worker parity tests (the ordinary code-test suite must be
 * self-contained; these tests execute the pinned approved Worker tree and node toolchain).
 *
 * The ordinary `testDebugUnitTest` task EXCLUDES this category so absent external prerequisites
 * never fail a unit run. The explicitly invoked variant runs via
 * `./gradlew :modules:services:voice:testDebugUnitTest -PaurisIncludeWorkerParity`, which
 * includes only this category and still FAILS honestly (never skips) when the pinned-worker
 * authority is missing.
 */
interface WorkerParity
