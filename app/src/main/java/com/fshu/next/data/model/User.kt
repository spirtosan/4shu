package com.fshu.next.data.model

data class User(
    val username: String,
    val online: Boolean = false,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val lastSeen: Long? = null,
    val nickname: String? = null,
    val isGroup: Boolean = false,
    val groupId: String? = null,
) {
    /** Display name — nickname if set, otherwise username. */
    val displayName: String get() = if (!nickname.isNullOrBlank()) nickname else username
}
