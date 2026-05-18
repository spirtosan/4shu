package com.fshu.next.data.local.dao

import androidx.room.*
import com.fshu.next.data.local.entities.Contact

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE owner = :owner AND status = 'accepted'")
    fun getAcceptedContacts(owner: String): List<Contact>

    @Query("SELECT * FROM contacts WHERE owner = :owner AND status = 'pending'")
    fun getPendingSent(owner: String): List<Contact>

    @Query("SELECT * FROM contacts WHERE contact = :contact AND status = 'pending'")
    fun getPendingReceived(contact: String): List<Contact>

    @Query("SELECT * FROM contacts WHERE owner = :owner AND contact = :contact LIMIT 1")
    fun getContact(owner: String, contact: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(contact: Contact)

    @Query("DELETE FROM contacts WHERE (owner = :a AND contact = :b) OR (owner = :b AND contact = :a)")
    fun deleteRelationship(a: String, b: String)

    @Query("DELETE FROM contacts WHERE owner = :owner AND contact = :contact")
    fun deleteOne(owner: String, contact: String)

    @Query("DELETE FROM contacts WHERE status = 'pending' AND expiresAt < :now")
    fun expireOld(now: Long)

    @Query("UPDATE contacts SET allow_emergency_call = :allow WHERE owner = :owner AND contact = :contact")
    suspend fun setEmergencyAllow(owner: String, contact: String, allow: Int)

    @Query("SELECT allow_emergency_call FROM contacts WHERE owner = :owner AND contact = :contact")
    suspend fun getEmergencyAllow(owner: String, contact: String): Int?

    @Query("UPDATE contacts SET allow_emergency_location = :allow WHERE owner = :owner AND contact = :contact")
    suspend fun setEmergencyLocationAllow(owner: String, contact: String, allow: Int)

    @Query("SELECT allow_emergency_location FROM contacts WHERE owner = :owner AND contact = :contact")
    suspend fun getEmergencyLocationAllow(owner: String, contact: String): Int?
}
