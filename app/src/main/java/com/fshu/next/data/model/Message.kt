package com.fshu.next.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val from: String,
    val to: String,
    val content: String,
    val type: String,           // "text" | "file"
    val filename: String? = null,
    val mimeType: String? = null,
    val localUri: String? = null,  // content:// URI of the saved/picked file
    val timestamp: Long = System.currentTimeMillis(),
    val isSent: Boolean = false,
    // SENDING → SENT → DELIVERED → READ; only displayed on sent bubbles.
    val status: String = "SENDING",
    // The sender's Room ID carried in the wire message and stored on received
    // messages so we can reference it when sending read receipts back.
    val remoteId: Long = 0,
    // Reply reference — all three are null when this message is not a reply.
    val replyToId: Long? = null,
    val replyToSender: String? = null,
    val replyToContent: String? = null,
    // Todo-list identifier — set for type="list" messages; null otherwise.
    val listId: String? = null,
    // Timestamp of the last successful list-update sync (sent or received); null = never synced.
    val lastSynced: Long? = null,
    // Server-authoritative version counter for this list; null until first server ack.
    val listVersion: Int? = null,
    // Username of the list creator (owner); null for non-list messages.
    val listOwner: String? = null,
    // Client-generated UUID sent with file uploads — matched to server ack.
    val tempId: String? = null,
    // Server-assigned file UUID — used to request binary download.
    val fileId: String? = null,
    // Epoch ms of last server-confirmed edit; 0 = never edited.
    val editedAt: Long = 0,
    // JSON array of {from, emoji} — stored as string; "" means no reactions.
    val reactions: String = "",
    // Voice message duration in seconds; 0 for non-voice messages.
    val voiceDuration: Int = 0,
    // Compact JSON float array of amplitude samples 0..1; null for non-voice messages.
    val voiceWaveform: String? = null,
    // Group ID for group messages; null for 1-1 messages.
    val groupId: String? = null,
    // True when message arrived from a non-contact (routed to request inbox).
    val isRequest: Boolean = false,
    // True when content is a raw encrypted blob (key was missing at receive time). Retry on key arrival.
    val encryptedBlob: Boolean = false
)
