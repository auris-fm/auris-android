package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime

import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SessionPreparationMetrics(
    val sessionCreateMs: Long,
    val staticPrefillMs: Long,
)

data class PreparedSessionResult<T>(
    val value: T,
    val sessionWaitMs: Long,
)

class PreparedFunctionGemmaSessionPool internal constructor(
    private val runtime: FunctionGemmaRuntime,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val beforePrepareEngineLock: () -> Unit,
) : Closeable {
    constructor(
        runtime: FunctionGemmaRuntime,
        scope: CoroutineScope,
        elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    ) : this(runtime, scope, elapsedRealtimeMs, {})

    val backend get() = runtime.backend

    private val consumerMutex = Mutex()
    private val engineLock = ReentrantLock()
    private val stateLock = Any()

    private var staticPrefix: String? = null
    private var availability = CompletableDeferred<FunctionGemmaSession>()
    private var preparedSession: FunctionGemmaSession? = null
    private var replacementJob: Job? = null
    private var isClosed = false

    suspend fun prepare(prefix: String): SessionPreparationMetrics {
        synchronized(stateLock) {
            checkOpen()
            check(staticPrefix == null) { "FunctionGemma session pool is already prepared" }
            staticPrefix = prefix
        }

        beforePrepareEngineLock()
        return engineLock.withLock {
            synchronized(stateLock) {
                checkOpen()
            }
            val (session, metrics) = createPreparedSession(prefix)
            publishPreparedSession(session, availability)
            metrics
        }
    }

    suspend fun <T> consume(
        block: (FunctionGemmaSession) -> T,
    ): PreparedSessionResult<T> = consumerMutex.withLock {
        val waitStart = elapsedRealtimeMs()
        val signalledSession = availability.await()
        val sessionWaitMs = elapsedRealtimeMs() - waitStart

        engineLock.withLock {
            val session = claimPreparedSession(signalledSession)
            try {
                PreparedSessionResult(
                    value = block(session),
                    sessionWaitMs = sessionWaitMs,
                )
            } finally {
                session.close()
                scheduleReplacement()
            }
        }
    }

    override fun close() {
        val job: Job?
        val waitingAvailability: CompletableDeferred<FunctionGemmaSession>
        synchronized(stateLock) {
            if (isClosed) return
            isClosed = true
            job = replacementJob
            replacementJob = null
            waitingAvailability = availability
            staticPrefix = null
        }

        job?.cancel()
        waitingAvailability.cancel(CancellationException("FunctionGemma session pool closed"))

        engineLock.withLock {
            val session = synchronized(stateLock) {
                preparedSession.also { preparedSession = null }
            }
            try {
                session?.close()
            } finally {
                runtime.close()
            }
        }
    }

    private fun claimPreparedSession(
        signalledSession: FunctionGemmaSession,
    ): FunctionGemmaSession = synchronized(stateLock) {
        checkOpen()
        check(preparedSession === signalledSession) { "Prepared FunctionGemma session is no longer available" }
        preparedSession = null
        availability = CompletableDeferred()
        signalledSession
    }

    private fun scheduleReplacement() {
        val targetAvailability: CompletableDeferred<FunctionGemmaSession>
        val prefix: String
        synchronized(stateLock) {
            if (isClosed) return
            prefix = checkNotNull(staticPrefix) { "FunctionGemma session pool has not been prepared" }
            targetAvailability = availability
        }

        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            engineLock.withLock {
                synchronized(stateLock) {
                    checkOpen()
                }
                val (session) = createPreparedSession(prefix)
                publishPreparedSession(session, targetAvailability)
            }
        }
        job.invokeOnCompletion { cause ->
            synchronized(stateLock) {
                if (replacementJob === job) {
                    replacementJob = null
                }
                if (cause != null && !isClosed && availability === targetAvailability) {
                    targetAvailability.completeExceptionally(cause)
                }
            }
        }

        val shouldStart = synchronized(stateLock) {
            if (isClosed) {
                false
            } else {
                replacementJob = job
                true
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun createPreparedSession(
        prefix: String,
    ): Pair<FunctionGemmaSession, SessionPreparationMetrics> {
        val createStart = elapsedRealtimeMs()
        val session = runtime.createSession()
        val sessionCreateMs = elapsedRealtimeMs() - createStart
        return try {
            val prefillStart = elapsedRealtimeMs()
            session.prefill(prefix)
            val staticPrefillMs = elapsedRealtimeMs() - prefillStart
            session to SessionPreparationMetrics(
                sessionCreateMs = sessionCreateMs,
                staticPrefillMs = staticPrefillMs,
            )
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private fun publishPreparedSession(
        session: FunctionGemmaSession,
        targetAvailability: CompletableDeferred<FunctionGemmaSession>,
    ) {
        val published = synchronized(stateLock) {
            if (isClosed || availability !== targetAvailability) {
                false
            } else {
                check(preparedSession == null) { "FunctionGemma session pool capacity exceeded" }
                preparedSession = session
                targetAvailability.complete(session)
                true
            }
        }
        if (!published) {
            session.close()
            throw CancellationException("FunctionGemma session pool closed")
        }
    }

    private fun checkOpen() {
        check(!isClosed) { "FunctionGemma session pool is closed" }
    }
}
