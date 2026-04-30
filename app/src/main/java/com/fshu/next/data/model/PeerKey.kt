package com.fshu.next.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peer_keys")
data class PeerKey(
    @PrimaryKey val username: String,
    val publicKey: String   // hex-encoded X25519 public key (32 bytes = 64 hex chars)
)
