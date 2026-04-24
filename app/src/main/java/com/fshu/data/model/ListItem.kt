package com.fshu.data.model

data class ListItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val checkedBy: String? = null,
    val checkedAt: Long? = null,
    val deletedAt: Long? = null
)
