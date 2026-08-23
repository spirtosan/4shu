package com.fshu.next.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailPointMapperTest {

    @Test
    fun `full fix point round-trips through the Room entity`() {
        val point = TrailPointData(
            seq = 123,
            kind = TrailPointKind.FIX,
            ts = 1752741000000,
            lat = 42.1354, lon = 24.7453, acc = 12.5, alt = 164.0, spd = 1.4, brg = 270.0,
            prov = "fused", mock = false, mot = "moving", susp = "jump",
            batt = 63, chg = false, net = "cell",
            cells = listOf(CellInfo(t = "lte", mcc = 284, mnc = 3, tac = 21901, ci = 123456789L, pci = 211, sig = -97, reg = true)),
            wifi = WifiInfo(
                conn = WifiAp(b = "aa:bb:cc:dd:ee:ff", s = "HomeNet", r = -52, f = 5180),
                scan = listOf(WifiAp(b = "11:22:33:44:55:66", s = "CafeX", r = -71, f = 2437))
            )
        )

        val entity = point.toEntity()

        assertEquals(point.seq, entity.seq)
        assertEquals(point, entity.toData())
    }

    @Test
    fun `event point with no cells or wifi round-trips through the Room entity with null JSON columns`() {
        val point = TrailPointData(
            seq = 124,
            kind = TrailPointKind.EVENT,
            ts = 1752741600000,
            ev = "shutdown",
            batt = 57, chg = false,
            last = LastFix(lat = 42.1354, lon = 24.7453, acc = 12.5, ts = 1752741000000)
        )

        val entity = point.toEntity()

        assertNull(entity.cellsJson)
        assertNull(entity.wifiJson)
        assertEquals(point, entity.toData())
    }

    @Test
    fun `susp flag defaults null and round-trips when set`() {
        val clean = TrailPointData(seq = 1, kind = TrailPointKind.FIX, ts = 0)
        assertNull(clean.toEntity().susp)
        assertNull(clean.toEntity().toData().susp)

        val flagged = TrailPointData(seq = 2, kind = TrailPointKind.FIX, ts = 0, susp = "jump")
        assertEquals("jump", flagged.toEntity().susp)
        assertEquals("jump", flagged.toEntity().toData().susp)
    }

    @Test
    fun `uploaded flag defaults false and is preserved when set`() {
        val point = TrailPointData(seq = 1, kind = TrailPointKind.FIX, ts = 0)

        assertEquals(false, point.toEntity().uploaded)
        assertEquals(true, point.toEntity(uploaded = true).uploaded)
    }
}
