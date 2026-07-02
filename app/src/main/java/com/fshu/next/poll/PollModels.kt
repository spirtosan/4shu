package com.fshu.next.poll

// SPEC_T5.md v2 §3b — single/multi chosen at creation, immutable.
enum class PollMode { SINGLE, MULTI }

// SPEC_T5.md v2 §3b — client-honored, not server-enforced (§5 Decision 3).
enum class PollStatus { OPEN, CLOSED }

// SPEC_T5.md v2 §3c. optionId is the inner `option_id` field packed into the
// item's `text`, not the outer list-item `id` — ballots reference optionId.
data class PollOption(
    val optionId: String,
    val label: String,
    val position: Int
)

data class PollDefinition(
    val question: String,
    val mode: PollMode,
    val status: PollStatus,
    // Ordered by `position` ascending (SPEC_T5.md v2 §3c).
    val options: List<PollOption>
)

// SPEC_T5.md v2 §3d. One row per voter: voterId is the outer list-item `id`
// (item_id, format "ballot:<username>") with the "ballot:" prefix already
// stripped by PollParser — the bare username. `selected` holds every chosen
// optionId in one ballot (not one item per option).
data class Ballot(
    val voterId: String,
    val selected: List<String>
)

data class PollTallyResult(
    // optionId -> vote count (ballots referencing unknown/removed options excluded).
    val counts: Map<String, Int>,
    // optionId -> named voters, in ballot-list order (v1 is named-only, SPEC_T5.md v2 §5 Decision 2).
    val votersByOption: Map<String, List<String>>
)
