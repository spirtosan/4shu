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

    /** B.1.5 — GDPR export streaming (§7): pages through trail_points instead of loading
     *  the whole table, same ORDER BY as [getAll] so page order matches it exactly. */
    @Query("SELECT * FROM trail_points ORDER BY seq ASC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<TrailPoint>

    /** T13 Block E — my-trail viewer (§7 Phase 1 Block E): reverse-chronological. */
    @Query("SELECT * FROM trail_points ORDER BY seq DESC")
    suspend fun getAllDesc(): List<TrailPoint>

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

    // T13 Block D — status card (§6.6).
    @Query("SELECT COUNT(*) FROM trail_points")
    suspend fun getCount(): Int

    @Query("SELECT MIN(ts) FROM trail_points")
    suspend fun getOldestTs(): Long?

    @Query("SELECT MAX(ts) FROM trail_points")
    suspend fun getNewestTs(): Long?

    /** B.1.4 — status card health check: newest actual FIX (not event) timestamp, so a
     *  string of restart/event points alone doesn't get read as "still collecting". */
    @Query("SELECT MAX(ts) FROM trail_points WHERE kind = 'fix'")
    suspend fun getNewestFixTs(): Long?

    /** SPEC_T13_GLITCH_FILTER.md §detour — retroactively set the glitch flag on an
     *  already-stored point. The "detour" rule is non-causal (decided only once the NEXT
     *  fix arrives), and the collector persists first for durability, so it flags the
     *  previous point in place afterwards. Additive: only ever writes a reason onto a
     *  point the online path left clean. */
    @Query("UPDATE trail_points SET susp = :susp WHERE seq = :seq")
    suspend fun updateSusp(seq: Long, susp: String?)

        /** One-tap disable (§6.5): local wipe. Server-side trail-wipe rides Phase 2/3. */
    @Query("DELETE FROM trail_points")
    suspend fun deleteAll()
}
