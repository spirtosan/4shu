package com.fshu.next.poll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private val optA = PollOption("opt-a", "American", 0)
private val optB = PollOption("opt-b", "Bulgarian", 1)
private val optC = PollOption("opt-c", "Chinese", 2)

private fun singleDef(status: PollStatus = PollStatus.OPEN) =
    PollDefinition("Where to eat?", PollMode.SINGLE, status, listOf(optA, optB, optC))

private fun multiDef(status: PollStatus = PollStatus.OPEN) =
    PollDefinition("Which toppings?", PollMode.MULTI, status, listOf(optA, optB, optC))

class PollTallyTest {

    @Test
    fun `single-select tally counts one vote per voter per option`() {
        val ballots = listOf(
            Ballot("alice", listOf("opt-a")),
            Ballot("bob", listOf("opt-a")),
            Ballot("carol", listOf("opt-b")),
        )

        val result = PollTally.tally(singleDef(), ballots)

        assertEquals(mapOf("opt-a" to 2, "opt-b" to 1, "opt-c" to 0), result.counts)
        assertEquals(listOf("alice", "bob"), result.votersByOption["opt-a"])
        assertEquals(listOf("carol"), result.votersByOption["opt-b"])
        assertEquals(emptyList<String>(), result.votersByOption["opt-c"])
    }

    @Test
    fun `multi-select tally counts a voter under every option they picked`() {
        val ballots = listOf(
            Ballot("alice", listOf("opt-a", "opt-c")),
            Ballot("bob", listOf("opt-a")),
        )

        val result = PollTally.tally(multiDef(), ballots)

        assertEquals(mapOf("opt-a" to 2, "opt-b" to 0, "opt-c" to 1), result.counts)
        assertEquals(listOf("alice", "bob"), result.votersByOption["opt-a"])
        assertEquals(listOf("alice"), result.votersByOption["opt-c"])
    }

    @Test
    fun `re-vote replacement - tally sees only the final upserted ballot`() {
        // Server upsert already collapsed alice's A-then-B re-vote into one row
        // by the time this reaches the client — tally must not double-count.
        val ballots = listOf(
            Ballot("alice", listOf("opt-b")),
            Ballot("bob", listOf("opt-a")),
        )

        val result = PollTally.tally(singleDef(), ballots)

        assertEquals(1, result.counts["opt-a"])
        assertEquals(1, result.counts["opt-b"])
        assertEquals(listOf("bob"), result.votersByOption["opt-a"])
        assertEquals(listOf("alice"), result.votersByOption["opt-b"])
    }

    @Test
    fun `duplicate voterId in ballots list is asserted, not silently deduped`() {
        val ballots = listOf(
            Ballot("alice", listOf("opt-a")),
            Ballot("alice", listOf("opt-b")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            PollTally.tally(singleDef(), ballots)
        }
    }

    @Test
    fun `ballots referencing unknown or removed options are ignored`() {
        val ballots = listOf(
            Ballot("alice", listOf("opt-a", "opt-deleted")),
            Ballot("bob", listOf("opt-deleted")),
        )

        val result = PollTally.tally(singleDef(), ballots)

        assertEquals(1, result.counts["opt-a"])
        assertEquals(listOf("alice"), result.votersByOption["opt-a"])
        // The unknown option never appears in the result at all.
        assertEquals(false, result.counts.containsKey("opt-deleted"))
    }

    @Test
    fun `closed poll still tallies - close is client-honored on the write path only`() {
        val ballots = listOf(
            Ballot("alice", listOf("opt-a")),
            Ballot("bob", listOf("opt-b")),
        )

        val openResult = PollTally.tally(singleDef(PollStatus.OPEN), ballots)
        val closedResult = PollTally.tally(singleDef(PollStatus.CLOSED), ballots)

        assertEquals(openResult.counts, closedResult.counts)
        assertEquals(openResult.votersByOption, closedResult.votersByOption)
    }

    @Test
    fun `empty selected is an explicit abstain, not counted`() {
        val ballots = listOf(Ballot("alice", emptyList()))

        val result = PollTally.tally(singleDef(), ballots)

        assertEquals(mapOf("opt-a" to 0, "opt-b" to 0, "opt-c" to 0), result.counts)
    }
}
