package com.fshu.next.util

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Singleton event bus: service writes, activities collect.
object MessageBus {
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    suspend fun emit(event: JsonObject) = _events.emit(event)
    fun tryEmit(event: JsonObject) { _events.tryEmit(event) }
}
