package com.fshu.next.poll

import com.fshu.next.data.model.ListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private fun metaItem(id: String, question: String, mode: String, status: String) = ListItem(
    id = id,
    text = """{"kind":"meta","question":"$question","mode":"$mode","status":"$status"}"""
)

private fun optionItem(id: String, optionId: String, label: String, position: Int) = ListItem(
    id = id,
    text = """{"kind":"option","option_id":"$optionId","label":"$label","position":$position}"""
)

private fun ballotItem(voterUsername: String, selected: List<String>) = ListItem(
    id = "ballot:$voterUsername",
    text = """{"kind":"ballot","selected":[${selected.joinToString(",") { "\"$it\"" }}]}"""
)

class PollParserTest {

    @Test
    fun `parses meta, ordered options, and ballots`() {
        val items = listOf(
            optionItem("i-opt-b", "opt-b", "Bulgarian", 1),
            metaItem("i-meta", "Where to eat?", "single", "open"),
            optionItem("i-opt-a", "opt-a", "American", 0),
            ballotItem("alice", listOf("opt-a")),
        )

        val parsed = PollParser.parse(items)

        assertEquals("Where to eat?", parsed.definition.question)
        assertEquals(PollMode.SINGLE, parsed.definition.mode)
        assertEquals(PollStatus.OPEN, parsed.definition.status)
        // Ordered by position, not by input/arrival order.
        assertEquals(listOf("opt-a", "opt-b"), parsed.definition.options.map { it.optionId })
        assertEquals(listOf(Ballot("alice", listOf("opt-a"))), parsed.ballots)
    }

    @Test
    fun `soft-deleted items are excluded`() {
        val items = listOf(
            metaItem("i-meta", "Q", "single", "open"),
            optionItem("i-opt-a", "opt-a", "A", 0),
            ListItem(id = "ballot:bob", text = """{"kind":"ballot","selected":["opt-a"]}""", deletedAt = 1L),
        )

        val parsed = PollParser.parse(items)

        assertEquals(0, parsed.ballots.size)
    }

    @Test
    fun `ballot item_id strips only the leading 'ballot-' prefix, username may contain colons`() {
        val items = listOf(
            metaItem("i-meta", "Q", "single", "open"),
            optionItem("i-opt-a", "opt-a", "A", 0),
            ListItem(id = "ballot:alice:device2", text = """{"kind":"ballot","selected":["opt-a"]}"""),
        )

        val parsed = PollParser.parse(items)

        assertEquals(listOf("alice:device2"), parsed.ballots.map { it.voterId })
    }

    @Test
    fun `ballot item_id missing the 'ballot-' prefix throws`() {
        val items = listOf(
            metaItem("i-meta", "Q", "single", "open"),
            optionItem("i-opt-a", "opt-a", "A", 0),
            ListItem(id = "alice", text = """{"kind":"ballot","selected":["opt-a"]}"""),
        )

        assertThrows(IllegalArgumentException::class.java) { PollParser.parse(items) }
    }

    @Test
    fun `missing meta item throws`() {
        val items = listOf(optionItem("i-opt-a", "opt-a", "A", 0))
        assertThrows(IllegalArgumentException::class.java) { PollParser.parse(items) }
    }

    @Test
    fun `invalid mode throws`() {
        val items = listOf(
            metaItem("i-meta", "Q", "yes-and-no", "open"),
            optionItem("i-opt-a", "opt-a", "A", 0),
        )
        assertThrows(IllegalArgumentException::class.java) { PollParser.parse(items) }
    }

    @Test
    fun `unknown item kind is skipped, not fatal`() {
        val items = listOf(
            metaItem("i-meta", "Q", "single", "open"),
            optionItem("i-opt-a", "opt-a", "A", 0),
            ListItem(id = "future-thing", text = """{"kind":"reaction","emoji":"x"}"""),
        )

        val parsed = PollParser.parse(items)

        assertEquals("Q", parsed.definition.question)
        assertEquals(0, parsed.ballots.size)
    }
}
