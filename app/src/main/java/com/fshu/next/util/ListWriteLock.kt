package com.fshu.next.util

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes local Room read-modify-write cycles on a list row's `content`/`listVersion`
 * columns (T5 Phase 2 Block C.1). Without this, ChatViewModel.voteOption()'s optimistic write
 * and FshuService.persistListState()'s inbound-echo write run on independent, unsynchronized
 * coroutines and can race — the device receives its own group list-edit echoed back over the
 * same WebSocket, and if that echo's write lands mid-voteOption, the optimistic write can
 * clobber it with a stale merge while listVersion is already bumped, so no later same-version
 * push corrects it.
 *
 * A single app-wide lock is sufficient: list-row writes are human-tap-paced, so contention is
 * negligible, and neither locked section calls into the other (the Mutex is not reentrant).
 * NEVER hold this across WebSocketClient.send() or any other network/suspend wait — only around
 * the local Room read → merge → write.
 */
object ListWriteLock {
    val mutex = Mutex()
}
