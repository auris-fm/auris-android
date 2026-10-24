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
}
