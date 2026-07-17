package com.fshu.next.trail

import com.google.gson.Gson

/**
 * Wire (de)serialization for a single trail point, exactly per SPEC_T13.md §2.1.
 * Plain Gson data-class mapping is sufficient here (unlike PollParser's manual
 * JsonObject walk, which has to cope with heterogeneous list-item kinds) — a
 * TrailPointData is always one fixed shape, and Gson already omits null fields by
 * default, matching the spec's "nullable fields omitted when unavailable" rule.
 */
object TrailPointCodec {
    private val gson = Gson()

    fun toJson(point: TrailPointData): String = gson.toJson(point)

    fun fromJson(json: String): TrailPointData = gson.fromJson(json, TrailPointData::class.java)
}
