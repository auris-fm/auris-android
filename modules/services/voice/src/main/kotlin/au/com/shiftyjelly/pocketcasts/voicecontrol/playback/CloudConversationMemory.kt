package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteConversationEntry

/**
 * Bounded in-memory recent-conversation context (cloud-assistant.md): the
 * client may supply up to four `{role, text}` entries (≤8 KiB) with a turn.
 * Nothing is persisted across process restarts and no server-side session
 * state is implied. Thread-safe: turns complete on different coroutines.
 */
class CloudConversationMemory(
    private val maxEntries: Int = MAX_STORED_ENTRIES,
) {
    private val lock = Any()
    private val entries = ArrayDeque<CloudRouteConversationEntry>()

    /** Newest-last snapshot of the recent conversation. */
    fun recent(): List<CloudRouteConversationEntry> = synchronized(lock) { entries.toList() }

    /** Records one completed exchange (user utterance + assistant answer). */
    fun record(userRequest: String, assistantAnswer: String) {
        if (userRequest.isBlank() && assistantAnswer.isBlank()) return
        synchronized(lock) {
            if (userRequest.isNotBlank()) {
                entries.addLast(
                    CloudRouteConversationEntry(CloudRouteConversationEntry.ROLE_USER, userRequest),
                )
            }
            if (assistantAnswer.isNotBlank()) {
                entries.addLast(
                    CloudRouteConversationEntry(
                        CloudRouteConversationEntry.ROLE_ASSISTANT,
                        assistantAnswer,
                    ),
                )
            }
            while (entries.size > maxEntries) entries.removeFirst()
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    private companion object {
        /** Two exchanges — the spec's four entries. */
        const val MAX_STORED_ENTRIES = 4
    }
}
