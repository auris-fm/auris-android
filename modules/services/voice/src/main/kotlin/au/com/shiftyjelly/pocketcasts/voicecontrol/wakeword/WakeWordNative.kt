package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The EXTERNAL-NATIVE boundary of wake-word detection.
 *
 * WHY THIS EXISTS. `OpenWakeWordDetector` touches the JNI object directly, which means merely
 * CONSTRUCTING the detector loads `libonnxruntime`. The class is a lazy Hilt singleton, so the load
 * lands wherever the engine is first touched — including inside teardown — and on a JVM without the
 * native runtime that is an `UnsatisfiedLinkError` (an Error, so the detector's `catch (Exception)`
 * does not contain it). Routing the native calls through this adapter keeps the detector's policy
 * unchanged while making the native touch a swappable boundary.
 *
 * CONTRACT: implementations carry the same preconditions and return conventions as the JNI methods
 * they forward to (`nativeInit` false on failure, `nativeDetect` negative on error). No policy lives
 * here.
 */
interface WakeWordNative {
    fun nativeInit(melModel: ByteArray, embedModel: ByteArray, classifierModel: ByteArray, threshold: Float): Boolean

    fun nativeDetect(samples: FloatArray, sampleRateHz: Int, outOffset: FloatArray): Float

    fun nativeRelease()
}

/** The production adapter: the JNI object, loaded on first touch of this class only. */
@Singleton
class JniWakeWordNative @Inject constructor() : WakeWordNative {
    override fun nativeInit(melModel: ByteArray, embedModel: ByteArray, classifierModel: ByteArray, threshold: Float): Boolean = WakeWordJni.nativeInit(melModel, embedModel, classifierModel, threshold)

    override fun nativeDetect(samples: FloatArray, sampleRateHz: Int, outOffset: FloatArray): Float = WakeWordJni.nativeDetect(samples, sampleRateHz, outOffset)

    override fun nativeRelease() = WakeWordJni.nativeRelease()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WakeWordNativeModule {
    @Binds
    @Singleton
    abstract fun bindWakeWordNative(impl: JniWakeWordNative): WakeWordNative
}
