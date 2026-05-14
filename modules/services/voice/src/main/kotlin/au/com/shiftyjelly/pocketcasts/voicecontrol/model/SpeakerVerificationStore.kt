package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import timber.log.Timber

@Singleton
class SpeakerVerificationStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "voice_control_speaker"
        private const val KEY_EMBEDDING = "speaker_embedding"
        private const val KEY_TIMESTAMP = "speaker_timestamp"
    }

    fun isEnrolled(): Boolean = prefs.contains(KEY_EMBEDDING)

    fun getEmbedding(): FloatArray? {
        val json = prefs.getString(KEY_EMBEDDING, null) ?: return null
        return try {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse stored embedding")
            null
        }
    }

    fun saveEmbedding(e: FloatArray) {
        prefs.edit().putString(KEY_EMBEDDING, JSONArray(e.toList()).toString()).apply()
    }

    fun saveEnrollmentTimestamp(t: Long) = prefs.edit().putLong(KEY_TIMESTAMP, t).apply()
    fun getEnrollmentTimestamp(): Long = prefs.getLong(KEY_TIMESTAMP, 0L)

    fun clear() = prefs.edit().remove(KEY_EMBEDDING).remove(KEY_TIMESTAMP).apply()
}
