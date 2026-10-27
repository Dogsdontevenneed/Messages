package org.fossify.messages.helpers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 验证码 / 取件码到期闹钟。
 *
 * 短信正文里写了有效期（"5分钟内有效"）时，[IslandNotifier] 会在到期时刻发这个广播，
 * 由本接收器把胶囊右侧的文字从具体的码改成"超时"。
 *
 * 为什么要校验 code：通知 id 是固定的，连着来两条短信时后一条会覆盖前一条。
 * 到期的闹钟如果无脑改写，会把新来的验证码错标成"超时"。
 */
class CodeExpireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(IslandNotifier.KEY_CODE).orEmpty()
        if (code.isBlank()) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val current = manager.activeNotifications
            .firstOrNull { it.id == IslandNotifier.NOTIFICATION_ID }
            ?.notification?.extras?.getString(IslandNotifier.KEY_CODE)

        if (current != code) {
            Log.i(IslandNotifier.TAG, "EXPIRE skip stale code=$code current=$current")
            return
        }

        val kind = runCatching {
            OtpParser.Kind.valueOf(intent.getStringExtra("kind") ?: OtpParser.Kind.OTP.name)
        }.getOrDefault(OtpParser.Kind.OTP)

        // islandText 传什么已经不重要 —— expired=true 时统一显示"超时"
        val expiredHit = OtpParser.Hit(kind, code, "超时", 0L)
        Log.i(IslandNotifier.TAG, "EXPIRE fire code=$code kind=$kind")

        IslandNotifier.notify(
            context = context,
            hit = expiredHit,
            senderName = intent.getStringExtra("sender") ?: "",
            body = intent.getStringExtra("body") ?: "",
            threadId = intent.getLongExtra("thread", 0L),
            expired = true
        )
    }
}
