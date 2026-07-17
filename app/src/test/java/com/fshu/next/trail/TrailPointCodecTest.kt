package com.fshu.next.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailPointCodecTest {

    // SPEC_T13.md §2.1 fix example, full enrichment.
    @Test
    fun `full fix point round-trips through wire JSON`() {
        val point = TrailPointData(
            seq = 123,
            kind = TrailPointKind.FIX,
            ts = 1752741000000,
            lat = 42.1354, lon = 24.7453, acc = 12.5, alt = 164.0, spd = 1.4, brg = 270.0,
            prov = "fused", mock = false, mot = "moving",
            batt = 63, chg = false, net = "cell",
            cells = listOf(CellInfo(t = "lte", mcc = 284, mnc = 3, tac = 21901, ci = 123456789L, pci = 211, sig = -97, reg = true)),
            wifi = WifiInfo(
                conn = WifiAp(b = "aa:bb:cc:dd:ee:ff", s = "HomeNet", r = -52, f = 5180),
                scan = listOf(WifiAp(b = "11:22:33:44:55:66", s = "CafeX", r = -71, f = 2437))
            )
        )

        val json = TrailPointCodec.toJson(point)
        val parsed = TrailPointCodec.fromJson(json)

        assertEquals(point, parsed)
    }

    // SPEC_T13.md §2.1 event example — kind=event, ev + last snapshot, no top-level fix fields.
    @Test
    fun `event point round-trips and omits absent fix fields`() {
        val point = TrailPointData(
            seq = 124,
            kind = TrailPointKind.EVENT,
            ts = 1752741600000,
            ev = "shutdown",
            batt = 57, chg = false,
            last = LastFix(lat = 42.1354, lon = 24.7453, acc = 12.5, ts = 1752741000000)
        )

        val json = TrailPointCodec.toJson(point)
        val parsed = TrailPointCodec.fromJson(json)

        assertEquals(point, parsed)
        // Nullable fields must be omitted, not serialized as JSON null (§2.1).
        assertFalse(json.contains("\"lat\""))
        assertFalse(json.contains("\"cells\""))
        assertFalse(json.contains("\"wifi\""))
        assertTrue(json.contains("\"ev\":\"shutdown\""))
    }

    // No GPS fix → event-only heartbeat carrying cells/wifi and no lat/lon is valid (§2.1).
    @Test
    fun `no-fix heartbeat point round-trips`() {
        val point = TrailPointData(
            seq = 200,
            kind = TrailPointKind.EVENT,
            ts = 1752741900000,
            mot = "still",
            batt = 61, chg = false, net = "wifi",
            cells = listOf(CellInfo(t = "lte", reg = true)),
            wifi = WifiInfo(conn = WifiAp(b = "aa:bb:cc:dd:ee:ff", r = -60))
        )

        val parsed = TrailPointCodec.fromJson(TrailPointCodec.toJson(point))

        assertEquals(point, parsed)
    }
}
