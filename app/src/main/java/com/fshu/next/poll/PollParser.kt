package com.fshu.next.poll

import com.fshu.next.data.model.ListItem
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

data class ParsedPoll(
    val definition: PollDefinition,
    val ballots: List<Ballot>
)

/**
 * Parses a type:"poll" list's list_items into a [PollDefinition] and its ballots.
 *
 * Meta placement (SPEC_T5.md v2 §3b, locked 2026-07-02): poll meta (question/mode/
 * status) is packed as its own list item with kind:"meta", same convention as
 * options/ballots — Phase 1 added no server-side column for it, and Phase 2 must
 * not touch the server, so list_items is the only available carrier.
 *
 * Ballot item_id format (SPEC_T5.md v2 §3d, locked 2026-07-02): "ballot:<username>".
 * The prefix guarantees no user-derived string collides with the meta id or any
 * option_id in the (list_id, item_id) PK; usernames may themselves contain ':', so
 * only the leading "ballot:" occurrence is stripped, never a split on ':'.
 */
object PollParser {

    private const val KIND_META = "meta"
    private const val KIND_OPTION = "option"
    private const val KIND_BALLOT = "ballot"
    private const val BALLOT_ID_PREFIX = "ballot:"

    fun parse(items: List<ListItem>): ParsedPoll {
        val active = items.filter { it.deletedAt == null }

        var metaId: String? = null
        var question: String? = null
        var mode: PollMode? = null
        var status: PollStatus? = null

        val options = LinkedHashMap<String, PollOption>()
        val ballots = mutableListOf<Ballot>()

        for (item in active) {
            val obj = try {
                JsonParser.parseString(item.text).asJsonObject
            } catch (e: JsonSyntaxException) {
                continue
            } catch (e: IllegalStateException) {
                continue
            }

            when (obj.get("kind")?.asString) {
                KIND_META -> {
                    require(metaId == null || metaId == item.id) {
                        "poll has more than one meta item: $metaId and ${item.id}"
                    }
                    metaId = item.id
                    question = obj.get("question")?.asString
                    mode = when (obj.get("mode")?.asString) {
                        "single" -> PollMode.SINGLE
                        "multi" -> PollMode.MULTI
                        else -> throw IllegalArgumentException("poll meta has invalid/missing mode")
                    }
                    status = when (obj.get("status")?.asString) {
                        "open" -> PollStatus.OPEN
                        "closed" -> PollStatus.CLOSED
                        else -> throw IllegalArgumentException("poll meta has invalid/missing status")
                    }
                }
                KIND_OPTION -> {
                    val optionId = obj.get("option_id")?.asString
                        ?: throw IllegalArgumentException("poll option item ${item.id} missing option_id")
                    val label = obj.get("label")?.asString ?: ""
                    val position = obj.get("position")?.asInt ?: 0
                    // Last-write-wins per option_id, mirroring the upsert semantics
                    // used for ballots (SPEC_T5.md v2 §4) — same list_items storage.
                    options[optionId] = PollOption(optionId, label, position)
                }
                KIND_BALLOT -> {
                    val voterId = item.id.removePrefix(BALLOT_ID_PREFIX)
                    require(voterId != item.id) {
                        "ballot item_id missing '$BALLOT_ID_PREFIX' prefix: ${item.id}"
                    }
                    val selected = obj.getAsJsonArray("selected")
                        ?.map { it.asString }
                        ?: emptyList()
                    ballots.add(Ballot(voterId = voterId, selected = selected))
                }
                else -> continue // unknown/forward-compat kind — skip
            }
        }

        val q = question ?: throw IllegalArgumentException("poll missing meta item (question)")
        val m = mode ?: throw IllegalArgumentException("poll missing meta item (mode)")
        val s = status ?: throw IllegalArgumentException("poll missing meta item (status)")

        val definition = PollDefinition(
            question = q,
            mode = m,
            status = s,
            options = options.values.sortedBy { it.position }
        )
        return ParsedPoll(definition, ballots)
    }
}
