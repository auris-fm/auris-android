package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test

/**
 * DETERMINISTIC DISCRIMINATOR for the intermittent `cancel during the body` failure.
 *
 * PROBE, not a control (reviewer 5184236b): no retention charge, no job binding, no trigger, no
 * assertions about production behaviour — its only output is the recorded ordering, which is what
 * DISPROVED the earlier "registration race" hypothesis. Kept until the real cause is known.
 *
 * The failure it is meant to explain: `cancel during the body releases the charge via the job
 * binding` in EngineToProducerPublishTest failed once (1185-run), then passed 3/3 isolated and in
 * every full run afterwards. No failing trace has ever been captured.
 */
class CancelOrderingReproTest {

    @Test
    fun `probe - cancel issued before the launched coroutine is dispatched`() {
        val scope = CoroutineScope(Dispatchers.IO)
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val job = scope.launch(Dispatchers.IO + Job(), start = CoroutineStart.LAZY) {
                started.set(true)
            }
            println("PROBE lazy registered; isCancelled=${job.isCancelled} started=${started.get()}")
            job.cancel()
            println("PROBE after cancel; isCancelled=${job.isCancelled} started=${started.get()}")
            val causes = CopyOnWriteArrayList<String>()
            job.invokeOnCompletion { cause -> causes += (cause?.javaClass?.simpleName ?: "none") }
            println("PROBE completion handler fired=${causes.isNotEmpty()} cause=${causes.firstOrNull()}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `probe - invokeOnCompletion registered on an already-failed job`() {
        val job = Job()
        job.cancel(kotlinx.coroutines.CancellationException("probe"))
        var ran = false
        job.invokeOnCompletion { cause ->
            ran = true
            println("PROBE immediate cause=${cause?.javaClass?.simpleName}")
        }
        println("PROBE already-complete handler ran=$ran")
    }
}
