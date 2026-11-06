package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.concurrent.atomic.AtomicReference

/** In-memory [SharingChoicePersistence] for tests that do not exercise the file format. */
class InMemoryChoicePersistence : SharingChoicePersistence {
    private val stored = java.util.concurrent.ConcurrentHashMap<String, SavedSharingChoice>()
    var failWrites: Boolean = false

    override fun read(account: String): SavedSharingChoice? = stored[account]

    override fun write(choice: SavedSharingChoice): Boolean {
        if (failWrites) return false
        stored[choice.account] = choice
        return true
    }
}

/** Builds a [SavedSharingChoiceStore] over the given persistence. */
fun choiceStore(persistence: SharingChoicePersistence = InMemoryChoicePersistence()) = SavedSharingChoiceStore(persistence)
