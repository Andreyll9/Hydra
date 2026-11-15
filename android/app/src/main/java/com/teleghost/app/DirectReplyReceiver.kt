package com.teleghost.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import org.json.JSONObject

class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = results.getCharSequence("key_text_reply")?.toString()?.trim() ?: return
        val senderNick = intent.getStringExtra("sender_nick") ?: ""
        val notifId = intent.getIntExtra("notif_id", 0)

        if (replyText.isNotEmpty()) {
            MainActivity.handleDirectReplyFromNotification(senderNick, replyText)
        }
        if (notifId != 0) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }
    }
}
