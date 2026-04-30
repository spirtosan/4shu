package com.fshu.next.service

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.util.Prefs

class FshuFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.d("FCM", "New token: ${token.take(20)}...")
        Prefs.setFcmToken(this, token)
        // If already connected, send token to server immediately
        if (WebSocketClient.isConnected) {
            WebSocketClient.send(mapOf("type" to "fcm-token", "token" to token))
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM", "Push received — waking FshuService")
        // Silent wake-up push — just ensure FshuService is running
        val intent = Intent(this, FshuService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
