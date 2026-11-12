package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import android.util.Log

/**
 * The sharing choice in the PLATFORM settings store (spec/owner call, 2026-11-12): one
 * [SharedPreferences] file, one key set per account — state, epoch, disclosure version — written
 * with `commit()`.
 *
 * WHY (the owner Pixel, 2026-11-12): the previous hand-built file (unique temp, atomic rename,
 * database-grade directory fsync, per-account persist-failure latch) failed on a runtime whose
 * directory-sync surface was unsupported: every persist "failed", the latch set, and the Settings
 * switch read off even though the record was on disk and the server had accepted every tap. The
 * spec never asked for that machinery; the platform store already writes atomically. A failed
 * `commit()` is only logged — prefs keep the new value in memory, so there is NO latch and no
 * failure-reason plumbing to delete.
 *
 * THE SAME RULES as the old store, unchanged: per-account keys so no account inherits another's
 * choice; only ENROLLED with real provenance admits ([SavedSharingChoiceStore.enrolledChoiceFor]).
 */
class SharedPreferencesChoicePersistence(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : SharingChoicePersistence {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(account: String): SavedSharingChoice? {
        // ONE snapshot (reviewer nit ddf08a2cc7-a): three separate getString calls could interleave
        // with a concurrent write and mix old and new fields. prefs.all is one consistent map.
        val snapshot = prefs.all
        fun field(name: String) = snapshot[key(account, name)] as? String
        val state = field(FIELD_STATE) ?: return null
        val parsed = SharingChoiceState.entries.firstOrNull { it.name == state } ?: return null
        val epoch = field(FIELD_EPOCH)?.takeIf { it != ABSENT }
        val disclosure = field(FIELD_DISCLOSURE)?.takeIf { it != ABSENT }
        return SavedSharingChoice(
            account = account,
            state = parsed,
            epoch = epoch,
            acceptedDisclosureVersion = disclosure,
        )
    }

    override fun write(choice: SavedSharingChoice): Boolean {
        val editor = prefs.edit()
            .putString(key(choice.account, FIELD_STATE), choice.state.name)
        if (choice.state == SharingChoiceState.ENROLLED) {
            editor.putString(key(choice.account, FIELD_EPOCH), choice.epoch ?: ABSENT)
                .putString(key(choice.account, FIELD_DISCLOSURE), choice.acceptedDisclosureVersion ?: ABSENT)
        } else {
            editor.remove(key(choice.account, FIELD_EPOCH))
                .remove(key(choice.account, FIELD_DISCLOSURE))
        }
        // A failed commit is NOT logged here: the account API logs it once on the record path
        // (reviewer nit ddf08a2cc7-c). This store is silent.
        return editor.commit()
    }

    private fun key(account: String, field: String) = "$account.$field"

    private companion object {
        const val PREFS = "voice_sharing_choice"
        const val FIELD_STATE = "state"
        const val FIELD_EPOCH = "epoch"
        const val FIELD_DISCLOSURE = "disclosure_version"
        const val ABSENT = "-"
        const val TAG = "VoiceSharingChoice"
    }
}

/**
 * The durable local stop in the SAME platform store (small state, spec item 3): one boolean. The
 * previous hand-built marker file carried the same temp/rename/directory-sync machinery that
 * blocked the choice persist on the owner Pixel; a stop is one bit and belongs in prefs.
 */
class SharedPreferencesWithdrawalIntentPersistence(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : WithdrawalIntentPersistence {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): Boolean = prefs.getBoolean(KEY_STOPPED, false)

    override fun write(requested: Boolean): Boolean {
        val ok = prefs.edit().putBoolean(KEY_STOPPED, requested).commit()
        if (!ok) {
            Log.w(TAG, "voice-sharing local stop persist: prefs commit failed; the in-memory value stands")
        }
        return ok
    }

    private companion object {
        const val PREFS = "voice_sharing_local_stop"
        const val KEY_STOPPED = "stopped"
        const val TAG = "VoiceSharingChoice"
    }
}
