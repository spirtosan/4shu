package com.fshu.next.poll

/**
 * Tallies a poll's ballots against its definition's options.
 *
 * Re-vote handling (SPEC_T5.md v2 §4): the server upserts a voter's ballot on
 * their stable per-voter item_id, so [ballots] is expected to already contain
 * at most one entry per voterId (the latest, in server arrival order) — no
 * dedup happens here. That invariant is asserted, not silently repaired: a
 * duplicate voterId means an upstream bug, not a case to paper over.
 *
 * Ballots referencing an unknown/removed option_id are ignored for that
 * option only (the rest of the ballot, if any, still counts).
 *
 * Poll status is irrelevant here: closed-poll enforcement is client-honored
 * on the write path (SPEC_T5.md v2 §5 Decision 3), not in tallying — this
 * function just renders whatever ballot state it's given.
 */
object PollTally {

    fun tally(definition: PollDefinition, ballots: List<Ballot>): PollTallyResult {
        val voterIds = ballots.map { it.voterId }
        require(voterIds.size == voterIds.toSet().size) {
            "duplicate voterId in ballots — upsert invariant violated: " +
                voterIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        }

        val validOptionIds = definition.options.map { it.optionId }.toSet()

        val counts = LinkedHashMap<String, Int>()
        val votersByOption = LinkedHashMap<String, MutableList<String>>()
        for (option in definition.options) {
            counts[option.optionId] = 0
            votersByOption[option.optionId] = mutableListOf()
        }

        for (ballot in ballots) {
            for (optionId in ballot.selected) {
                if (optionId !in validOptionIds) continue
                counts[optionId] = (counts[optionId] ?: 0) + 1
                votersByOption.getOrPut(optionId) { mutableListOf() }.add(ballot.voterId)
            }
        }

        return PollTallyResult(counts, votersByOption)
    }
}
