package com.fshu.next.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fshu.next.data.local.entities.TrailPoint
import com.fshu.next.data.local.entities.TrailUploadState

@Dao
interface TrailDao {
    @Insert
    suspend fun insert(point: TrailPoint): Long

    @Query("SELECT * FROM trail_points ORDER BY seq ASC")
    suspend fun getAll(): List<TrailPoint>

    @Query("SELECT * FROM trail_points WHERE seq > :sinceSeq ORDER BY seq ASC")
    suspend fun getSince(sinceSeq: Long): List<TrailPoint>

    @Query("SELECT MAX(seq) FROM trail_points")
    suspend fun getMaxSeq(): Long?

    /** Frozen-clock retention (SPEC_T13.md §1.2): window measured from this device's
     *  own newest point, not from now — so a trail that stopped uploading survives. */
    @Query("DELETE FROM trail_points WHERE ts < (SELECT MAX(ts) FROM trail_points) - :retentionMs")
    suspend fun purgeOlderThanFrozenWindow(retentionMs: Long)

    @Query("SELECT * FROM trail_upload_state WHERE guardianDevice = :guardianDevice LIMIT 1")
    suspend fun getUploadWatermark(guardianDevice: String): TrailUploadState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUploadWatermark(state: TrailUploadState)
}
