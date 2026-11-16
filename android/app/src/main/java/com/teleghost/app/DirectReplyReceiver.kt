package com.teleghost.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra("notif_id", 0)
        val senderNick = intent.getStringExtra("sender_nick") ?: ""
        val callAction = intent.getStringExtra("call_action") ?: ""

        if (callAction == "accept_call" || callAction == "decline_call") {
            if (notifId != 0) NotificationManagerCompat.from(context).cancel(notifId)
            if (callAction == "accept_call") {
                val actIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(actIntent)
            }
            MainActivity.handleCallNotificationAction(senderNick, callAction)
            return
        }

        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = results.getCharSequence("key_text_reply")?.toString()?.trim() ?: return
        if (replyText.isNotEmpty()) {
            MainActivity.handleDirectReplyFromNotification(senderNick, replyText)
        }
        if (notifId != 0) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }
    }
}
