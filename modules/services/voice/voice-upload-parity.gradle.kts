// TASK-SCOPED PARITY PIN (spec 6d6ea0cd): the approved Worker revision is pinned in THIS module's
// test tasks — not a global init script, which is hidden shared state and cannot establish another
// runner's approved-pin evidence. Resolution order:
//   1. -PaurisCloudWorkerRoot=<approved pinned worktree>  (explicit, reproducible invocation)
//   2. AURIS_CLOUD_WORKER_ROOT environment variable       (existing contract)
//   3. the approved pin this repo is currently reviewed against (3dd0c91d)
// The parity tests still FAIL (not skip) when the pin check fails.
val pinDefault = "/tmp/auris-cloud-pinned-3dd0c91d/worker"
val pinValue: String = providers.gradleProperty("aurisCloudWorkerRoot")
    .orElse(providers.environmentVariable("AURIS_CLOUD_WORKER_ROOT"))
    .orElse(pinDefault)
    .get()
tasks.withType<Test>().configureEach {
    environment("AURIS_CLOUD_WORKER_ROOT", pinValue)
    // EXPLICIT per-key tracking of the six joined-run loopback inputs (supervisor driverEnv keys).
    // Each providers.environmentVariable read is a named configuration-cache input: when a key's
    // value changes between invocations, Gradle invalidates the stored entry and reconfigures, so
    // the serialized Test environment carries THIS invocation's values. Absent keys are set to the
    // empty string - deliberately NOT falling back to any inherited value - and the driver treats
    // blanks as absent (System.getenv(...)?.takeIf { it.isNotBlank() }).
    val loopbackKeys = listOf(
        "AURIS_LOOPBACK_UPLOAD", "AURIS_LOOPBACK_ACCOUNT", "AURIS_LOOPBACK_OBSERVE",
        "AURIS_LOOPBACK_BEARER", "AURIS_LOOPBACK_ACCOUNT_ID", "AURIS_LOOPBACK_DISCLOSURE",
    )
    loopbackKeys.associateWith { key -> providers.environmentVariable(key).getOrElse("") }
        .forEach { (key, value) -> environment(key, value) }
}

// OWNER a793f239: the ordinary code-test suite is SELF-CONTAINED and never fails because an
// external repository is absent. The REAL-Worker parity tests (category
// upload.WorkerParity) are excluded from every ordinary Test task; they run only when the
// suite is EXPLICITLY invoked with -PaurisIncludeWorkerParity, and then they still FAIL
// honestly (never skip) when the pinned-worker authority is missing.
val includeWorkerParity = providers.gradleProperty("aurisIncludeWorkerParity").isPresent
tasks.withType<Test>().configureEach {
    useJUnit {
        if (!includeWorkerParity) {
            excludeCategories("au.com.shiftyjelly.pocketcasts.voicecontrol.upload.WorkerParity")
        }
    }
}
