package org.fossify.messages.helpers

/**
 * Turns conversation state into an avatar tone, and keeps the one piece of state that is not on the
 * [org.fossify.messages.models.Conversation] row: whether the newest message was sent by us.
 *
 * That flag needs a grouped read over the messages table, so it is refreshed once per conversation
 * list load rather than per row. Adding it to the Conversation entity would have meant a Room
 * migration, which is not worth it for a colour.
 */
object ConversationState {

    private const val RECENT_WINDOW_SECONDS = 24 * 60 * 60

    @Volatile
    private var outgoingThreadIds: Set<Long> = emptySet()

    fun updateOutgoingThreads(ids: Collection<Long>) {
        outgoingThreadIds = ids.toHashSet()
    }

    fun isOutgoing(threadId: Long): Boolean = threadId in outgoingThreadIds

    /**
     * Debug-only override, set from [DebugReceiver]. Lets a tone be previewed on the real list
     * without waiting for a conversation to naturally reach that state.
     */
    @Volatile
    var debugForceTone: AvatarFactory.Tone? = null

    fun toneFor(threadId: Long, read: Boolean, unreadCount: Int, dateSeconds: Int): AvatarFactory.Tone {
        debugForceTone?.let { return it }
        val ageSeconds = System.currentTimeMillis() / 1000 - dateSeconds
        return when {
            // anything still unread needs attention, no matter how old it is
            !read || unreadCount > 0 -> AvatarFactory.Tone.ATTENTION
            ageSeconds <= RECENT_WINDOW_SECONDS -> AvatarFactory.Tone.RECENT
            isOutgoing(threadId) -> AvatarFactory.Tone.OUTGOING
            else -> AvatarFactory.Tone.QUIET
        }
    }
}
