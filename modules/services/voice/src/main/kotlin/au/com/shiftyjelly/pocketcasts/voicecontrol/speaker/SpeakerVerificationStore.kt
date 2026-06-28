package au.com.shiftyjelly.pocketcasts.voicecontrol.speaker

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import timber.log.Timber

/** Persistent key-value storage for the voiceprint and metadata (~1 KB). */
@Singleton
class SpeakerVerificationStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getVoiceprint(): FloatArray? {
        val json = prefs.getString(KEY_EMBEDDING, null) ?: return null
        return try {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse stored voiceprint")
            null
        }
    }

    fun setVoiceprint(embedding: FloatArray) {
        val arr = JSONArray()
        for (v in embedding) arr.put(v.toDouble())
        prefs.edit()
            .putString(KEY_EMBEDDING, arr.toString())
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Timber.i("Voiceprint stored (%d dims)", embedding.size)
    }

    fun getThreshold(): Float = prefs.getFloat(KEY_THRESHOLD, 0.6f)

    fun setThreshold(threshold: Float) {
        prefs.edit().putFloat(KEY_THRESHOLD, threshold).apply()
    }

    fun isEnrolled(): Boolean = prefs.contains(KEY_EMBEDDING)

    fun getEnrollmentTimestamp(): Long = prefs.getLong(KEY_TIMESTAMP, 0L)

    fun clear() {
        prefs.edit().clear().apply()
        Timber.i("Voiceprint cleared")
    }

    companion object {
        private const val PREFS_NAME = "speaker_verification"
        private const val KEY_EMBEDDING = "enrolled_embedding"
        private const val KEY_TIMESTAMP = "enrollment_timestamp"
        private const val KEY_THRESHOLD = "threshold"
    }
}
