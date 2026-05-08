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
    val avatarPath: String? = null,
    val personalAvatar: String? = null,
    val trustLevel: String = "contact",
    val email: String? = null,
    val phone: String? = null,
    val bio: String? = null,
    val discoverable: Int = 1,
    val showAvatar: Int = 1,
    val showNickname: Int = 1,
    val emailSearchable: Int = 1,
    val phoneSearchable: Int = 1,
) {
    /** Display name — nickname if set, otherwise username. */
    val displayName: String get() = if (!nickname.isNullOrBlank()) nickname else username
}
