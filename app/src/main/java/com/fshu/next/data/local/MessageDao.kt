package com.fshu.next.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.fshu.next.data.model.Message

@Dao
interface MessageDao {
    @Query("""
        SELECT * FROM messages
        WHERE (`from` = :a AND `to` = :b) OR (`from` = :b AND `to` = :a)
        ORDER BY timestamp ASC
    """)
    fun getConversation(a: String, b: String): LiveData<List<Message>>

    /** Returns all messages received FROM [peer] that carry a valid remoteId. */
    @Query("SELECT * FROM messages WHERE `from` = :peer AND `to` = :me AND remoteId > 0")
    suspend fun getReceivedFrom(peer: String, me: String): List<Message>

    /** Returns the auto-generated row ID. */
    @Insert
    suspend fun insert(message: Message): Long

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /**
     * Like [updateStatus] but only advances status forward (SENDING→SENT→DELIVERED→READ).
     * Prevents a race where a 'delivered' coroutine executes before 'ack' and then 'ack'
     * downgrades the status back to SENT.
     */
    @Query("""UPDATE messages SET status = :status WHERE id = :id AND (
        (:status = 'SENT'      AND status = 'SENDING') OR
        (:status = 'DELIVERED' AND status IN ('SENDING','SENT')) OR
        (:status = 'READ'      AND status IN ('SENDING','SENT','DELIVERED'))
    )""")
    suspend fun upgradeStatus(id: Long, status: String)

    /** Returns sent messages still in SENDING state with a timestamp older than [cutoff]. */
    @Query("SELECT * FROM messages WHERE isSent = 1 AND status = 'SENDING' AND timestamp < :cutoff")
    suspend fun getStaleSending(cutoff: Long): List<Message>

    /** Returns the most recent message in the conversation with [peer], or null if none. */
    @Query("""
        SELECT * FROM messages
        WHERE (`from` = :peer AND `to` = :me) OR (`from` = :me AND `to` = :peer)
        ORDER BY timestamp DESC LIMIT 1
    """)
    suspend fun getLastMessage(peer: String, me: String): Message?

    /** Returns the list message with the given [listId], or null if not found. */
    @Query("SELECT * FROM messages WHERE listId = :listId LIMIT 1")
    suspend fun getByListId(listId: String): Message?

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    /** Updates content and version for a list — called when list-state arrives from server. */
    @Query("UPDATE messages SET content = :content, listVersion = :version WHERE listId = :listId")
    suspend fun updateListState(listId: String, content: String, version: Int)

    /** Updates only the version for a list — called when list-ack arrives from server. */
    @Query("UPDATE messages SET listVersion = :version WHERE listId = :listId")
    suspend fun updateListVersion(listId: String, version: Int)

    /** Returns all list messages — used to build listVersions map for ping. */
    @Query("SELECT * FROM messages WHERE type = 'list' AND listId IS NOT NULL")
    suspend fun getAllLists(): List<Message>

    /** Finds a sent location-request message whose content contains [requestId]. */
    @Query("SELECT * FROM messages WHERE type = 'location-request' AND isSent = 1 AND content LIKE '%' || :requestId || '%' LIMIT 1")
    suspend fun getSentLocationRequest(requestId: String): Message?

    /** Finds a received location-request message whose content contains [requestId]. */
    @Query("SELECT * FROM messages WHERE type = 'location-request' AND isSent = 0 AND content LIKE '%' || :requestId || '%' LIMIT 1")
    suspend fun getRecvLocationRequest(requestId: String): Message?

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): Message?

    /** Finds any message with this remoteId — used to deduplicate received history responses. */
    @Query("SELECT * FROM messages WHERE remoteId = :remoteId AND remoteId > 0 LIMIT 1")
    suspend fun getByRemoteId(remoteId: Long): Message?

    /** Finds a sent message with matching peer and approximate timestamp — used to deduplicate sent history responses. */
    @Query("SELECT * FROM messages WHERE isSent = 1 AND `to` = :to AND timestamp BETWEEN :tsMin AND :tsMax LIMIT 1")
    suspend fun getSentNear(to: String, tsMin: Long, tsMax: Long): Message?

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM messages WHERE tempId = :tempId LIMIT 1")
    suspend fun getByTempId(tempId: String): Message?

    /** Set status=SENT and fileId on ack for a binary file upload. */
    @Query("UPDATE messages SET status = 'SENT', fileId = :fileId WHERE tempId = :tempId AND status = 'SENDING'")
    suspend fun updateOnFileAck(tempId: String, fileId: String)

    /** Set localUri once the binary file has been downloaded and saved. */
    @Query("UPDATE messages SET localUri = :localUri WHERE fileId = :fileId")
    suspend fun updateFileLocalUri(fileId: String, localUri: String)
}
