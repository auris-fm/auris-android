package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test

/**
 * DETERMINISTIC DISCRIMINATOR for the intermittent `cancel during the body` failure.
 *
 * Purpose: force each ordering of (dispatch registration, coroutine start, cancel) with an explicit
 * barrier, and record whether the retention charge is released. This is a PROBE, not a control:
 * it asserts nothing about production correctness yet. It exists to turn a hypothesis into either a
 * failing trace or a disproof.
 */
class CancelOrderingReproTest {

    @Test
    fun `probe - cancel issued before the launched coroutine is dispatched`() {
        val jobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        val scope = CoroutineScope(Dispatchers.IO)
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val job = scope.launch(Dispatchers.IO + Job(), start = CoroutineStart.LAZY) {
                started.set(true)
            }.also { jobs.add(it) }
            // Registered but NEVER started: this is the registration-before-dispatch window.
            println("PROBE lazy registered; isCancelled=${job.isCancelled} started=${started.get()}")
            job.cancel()
            println("PROBE after cancel; isCancelled=${job.isCancelled} started=${started.get()}")
            var ran = false
            job.invokeOnCompletion { cause ->
                ran = true
                println("PROBE completion cause=${cause?.javaClass?.simpleName}")
            }
            println("PROBE invokeOnCompletion-already-complete ran=$ran")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `probe - invokeOnCompletion registered on an already-failed job`() {
        val job = kotlinx.coroutines.Job()
        job.cancel(kotlinx.coroutines.CancellationException("probe"))
        var ran = false
        // If a job is ALREADY complete, invokeOnCompletion runs the handler immediately.
        job.invokeOnCompletion { cause ->
            ran = true
            println("PROBE immediate cause=${cause?.javaClass?.simpleName}")
        }
        println("PROBE already-complete handler ran=$ran")
    }
}
