package au.com.shiftyjelly.pocketcasts.sharedtest

import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task

/**
 * A gms [Task] that never touches Android classes.
 *
 * WHY THIS EXISTS: the REAL `Tasks.forResult` / `TaskCompletionSource` implementations route
 * `addOnCompleteListener` through gms `TaskExecutors`, which require the Android runtime. On the
 * plain JVM the first touch throws `NoClassDefFoundError` / `ExceptionInInitializerError` INSIDE
 * whatever unguarded coroutine called it, and the uncaught exception is attributed by `runTest` to
 * whichever test class runs NEXT — an order-dependent suite-level flake
 * ("UncaughtExceptionsBeforeTest"), measured in the voice module (reviewer-documented in
 * RealServiceTestBindings: "not hypothetical").
 *
 * The fake completes listeners synchronously, which is all a `suspendCancellableCoroutine`
 * bridge requires. Use [JvmTaskSource] where gms `TaskCompletionSource` would park a fetch.
 */
open class JvmTask<T : Any>(private var completion: Result<T>? = null) : Task<T>() {
    private val listeners = mutableListOf<OnCompleteListener<T>>()

    fun complete(value: T) = completeWith(Result.success(value))

    fun completeWith(outcome: Result<T>) {
        completion = outcome
        listeners.toList().forEach { it.onComplete(this) }
    }

    override fun isComplete(): Boolean = completion != null
    override fun isSuccessful(): Boolean = completion?.isSuccess == true
    override fun isCanceled(): Boolean = false
    override fun getException(): Exception? = completion?.exceptionOrNull() as? Exception
    override fun getResult(): T = completion!!.getOrThrow()

    @Suppress("UNCHECKED_CAST")
    override fun <X : Throwable> getResult(xClass: Class<X>): T = getResult()
    override fun addOnCompleteListener(listener: OnCompleteListener<T>): Task<T> {
        listeners += listener
        completion?.let { listener.onComplete(this) }
        return this
    }
    override fun addOnCompleteListener(
        executor: java.util.concurrent.Executor,
        listener: OnCompleteListener<T>,
    ): Task<T> = addOnCompleteListener(listener)
    override fun addOnSuccessListener(listener: OnSuccessListener<in T>): Task<T> {
        completion?.let { c -> c.getOrNull()?.let { listener.onSuccess(it) } }
        return this
    }
    override fun addOnSuccessListener(
        executor: java.util.concurrent.Executor,
        listener: OnSuccessListener<in T>,
    ): Task<T> = addOnSuccessListener(listener)
    override fun addOnFailureListener(listener: OnFailureListener): Task<T> {
        completion?.exceptionOrNull()?.let { if (it is Exception) listener.onFailure(it) }
        return this
    }
    override fun addOnFailureListener(
        executor: java.util.concurrent.Executor,
        listener: OnFailureListener,
    ): Task<T> = addOnFailureListener(listener)

    // Activity overloads: throw — no JVM test may attach an Activity-scoped listener.
    override fun addOnSuccessListener(
        activity: android.app.Activity,
        listener: OnSuccessListener<in T>,
    ): Task<T> = throw UnsupportedOperationException("Activity listeners are not available on the JVM")
    override fun addOnFailureListener(
        activity: android.app.Activity,
        listener: OnFailureListener,
    ): Task<T> = throw UnsupportedOperationException("Activity listeners are not available on the JVM")
}

/** The pure-JVM stand-in for gms TaskCompletionSource. */
class JvmTaskSource<T : Any> {
    val task = JvmTask<T>()
    fun trySetResult(value: T): Boolean = runCatching { task.complete(value) }.isSuccess
    fun trySetException(exception: Exception): Boolean = runCatching { task.completeWith(Result.failure(exception)) }.isSuccess
}
