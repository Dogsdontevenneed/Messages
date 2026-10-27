package org.fossify.messages.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.fossify.messages.R
import org.fossify.messages.receivers.MarkAsReadReceiver

/**
 * 把验证码 / 取件码送到 Honor 灵动胶囊。
 *
 * 实测结论（Honor MagicOS 11 / Android 17）：
 *  - 走 AOSP 的 promoted ongoing 通道即可，Honor 会原样渲染成自家胶囊
 *  - shortCriticalText 显示在胶囊上，宽度约 6 个半角字符
 *  - 前台不显示；锁屏只显示锁图标；同包多条只堆叠
 *  - 点击展开可显示标题/正文/按钮
 */
object IslandNotifier {

    const val TAG = "SmsIsland"

    private const val CHANNEL_ID = "island_codes"
    private const val CHANNEL_ID_QUIET = "island_codes_quiet"
    private const val CHANNEL_NAME = "验证码胶囊"
    private const val CHANNEL_NAME_QUIET = "验证码胶囊（静音调试）"

    /** 供 CodeExpireReceiver 定位并改写这条通知 */
    const val NOTIFICATION_ID = 0x15A1
    const val KEY_CODE = "island_code"

    /** 超时后「超时」这两个字在胶囊上停留多久 */
    private const val EXPIRE_LINGER_MS = 60 * 1000L

    private const val ACTION_COPY = "org.fossify.messages.action.COPY_CODE"

    const val EXTRA_CODE = "extra_code"

    fun notifyIfCode(context: Context, senderName: String, body: String, threadId: Long = 0L) {
        val hit = OtpParser.parse(body)
        if (hit == null) {
            Log.i(TAG, "ISLAND skip no code, len=${body.length}")
            return
        }
        Log.i(TAG, "ISLAND hit kind=${hit.kind} code=${hit.code} island=${hit.islandText} ttl=${hit.ttlMs}")
        notify(context, hit, senderName, body, threadId = threadId)
    }

    fun notify(
        context: Context,
        hit: OtpParser.Hit,
        senderName: String,
        body: String,
        quiet: Boolean = false,
        noIcon: Boolean = false,
        color: Int = 0,
        iconText: String? = null,
        threadId: Long = 0L,
        expired: Boolean = false
    ): Boolean {
        if (Build.VERSION.SDK_INT < 36) {
            Log.w(TAG, "ISLAND skip sdk=${Build.VERSION.SDK_INT} < 36")
            return false
        }
        val channelId = ensureChannel(context, quiet)

        val kindLabel = if (hit.kind == OtpParser.Kind.OTP) "验证码" else "取件码"
        val title = "$kindLabel ${hit.code}"

        val copyIntent = Intent(context, CopyCodeReceiver::class.java).apply {
            action = ACTION_COPY
            putExtra(EXTRA_CODE, hit.code)
        }
        val copyPending = PendingIntent.getBroadcast(
            context,
            hit.code.hashCode(),
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 胶囊左侧只接受图片，不接受文本。这里把类型词画成位图当图标用，
        // 这样未展开时就能看出是验证码还是取件码（右侧仍是具体的码）。
        val effectiveIconText = iconText ?: when (hit.kind) {
            OtpParser.Kind.OTP -> "验证码"
            OtpParser.Kind.PICKUP -> "取件码"
        }
        val icon = if (noIcon) transparentIcon() else textIcon(effectiveIconText)

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis() + if (expired) EXPIRE_LINGER_MS else hit.ttlMs)
            // 原通知用 3 倍 TTL 兜底：万一精确闹钟没能触发，也不至于永久驻留
            .setTimeoutAfter(if (expired) EXPIRE_LINGER_MS else hit.ttlMs * 3)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(null, "复制$kindLabel", copyPending).build()
            )

        // 「已读」：直接复用 Fossify 的 MarkAsReadReceiver ——
        // 它负责标记短信已读、收掉普通通知、刷新会话列表，我们额外让它一并收起胶囊。
        if (threadId != 0L) {
            val readIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
                action = MARK_AS_READ
                putExtra(THREAD_ID, threadId)
            }
            val readPending = PendingIntent.getBroadcast(
                context,
                threadId.toInt(),
                readIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(null, "已读", readPending).build()
            )
        }

        if (color != 0) {
            builder.setColor(color)
        }

        // 上岛的关键两步：
        // setShortCriticalText 从 API 36 起可用，直接调用；
        // setRequestPromotedOngoing 是 36.1/37 才追加的，用反射避免被 compileSdk 卡住。
        val capsuleText = if (expired) "超时" else hit.islandText
        runCatching { builder.setShortCriticalText(capsuleText) }
            .onFailure { Log.w(TAG, "ISLAND setShortCriticalText failed: ${it.message}") }
        runCatching {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        }.onFailure { Log.w(TAG, "ISLAND setRequestPromotedOngoing failed: ${it.message}") }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching {
            // 把码写进 extras，过期闹钟触发时用它确认"当前这条还是不是同一个码"
            builder.addExtras(android.os.Bundle().apply { putString(KEY_CODE, hit.code) })
            manager.notify(NOTIFICATION_ID, builder.build())
            // 回读一次，确认系统真的收下了（promoted 标志由系统置位）
            val posted = manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }
            Log.i(
                TAG,
                "ISLAND posted id=$NOTIFICATION_ID promotedFlag=" +
                    (posted != null && (posted.notification.flags and 0x40000) != 0) +
                    " channelPromoted=" + (posted?.notification?.hasPromotableCharacteristics() ?: false)
            )
            if (!expired) {
                scheduleExpire(context, hit, senderName, body, threadId)
            }
            true
        }.onFailure {
            Log.e(TAG, "ISLAND notify failed", it)
        }.getOrDefault(false)
    }

    /**
     * 到期闹钟：短信里写了"5分钟内有效"就在 5 分钟后把胶囊右侧改成"超时"。
     * 用 AlarmManager 而不是协程，因为进程随时可能被系统回收。
     */
    private fun scheduleExpire(
        context: Context,
        hit: OtpParser.Hit,
        senderName: String,
        body: String,
        threadId: Long
    ) {
        if (hit.ttlMs <= 0) return
        val intent = Intent(context, CodeExpireReceiver::class.java).apply {
            putExtra(KEY_CODE, hit.code)
            putExtra("kind", hit.kind.name)
            putExtra("sender", senderName)
            putExtra("body", body)
            putExtra("thread", threadId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            hit.code.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + hit.ttlMs
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val ok = runCatching {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }.onFailure {
            // 没有精确闹钟权限时降级，最多晚几分钟，功能不受影响
            Log.w(TAG, "ISLAND exact alarm denied, fallback: ${it.message}")
            runCatching { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pending) }
        }.isSuccess
        Log.i(TAG, "ISLAND expire scheduled in ${hit.ttlMs}ms exact=$ok code=${hit.code}")
    }

    /**
     * 把文字画成位图当图标用。
     * 胶囊左侧只接受 drawable/bitmap，不接受文本；
     * 汉字没法用 vector path 手写，所以用 Canvas 渲染成图片。
     */
    private fun textIcon(text: String): android.graphics.drawable.Icon {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val heightPx = (24 * dm.density).toInt().coerceIn(48, 192)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            // 与荣耀胶囊里系统文字的实测主色一致（从截图采样：系统文字 #CBCCCF，
            // 我们的位图原本是纯白 #FFFFFF，两者并排时色差明显）。
            color = 0xFFCBCCCF.toInt()
            textSize = heightPx * 0.82f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val pad = heightPx / 8
        val widthPx = (paint.measureText(text).toInt() + pad * 2).coerceIn(heightPx, heightPx * 8)
        val bmp = android.graphics.Bitmap.createBitmap(
            widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        val fm = paint.fontMetrics
        val baseline = heightPx / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, pad.toFloat(), baseline, paint)
        Log.i(TAG, "ISLAND textIcon '$text' size=${widthPx}x$heightPx")
        return android.graphics.drawable.Icon.createWithBitmap(bmp)
    }

    /** 1x1 透明图标：用来测试"去掉图标后文本区能宽多少" */
    private fun transparentIcon(): android.graphics.drawable.Icon {
        val bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        return android.graphics.drawable.Icon.createWithBitmap(bmp)
    }

    /** 清掉胶囊通知（调试用，也用于验证码被读取后主动收起） */
    fun clear(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    /**
     * 返回实际使用的渠道 id。
     * quiet 走独立渠道：Android 8+ 通知是否响铃由渠道决定，
     * Notification 级别的 setSound(null) 会被忽略，只能靠渠道静音。
     */
    private fun ensureChannel(context: Context, quiet: Boolean): String {
        if (Build.VERSION.SDK_INT < 26) return if (quiet) CHANNEL_ID_QUIET else CHANNEL_ID
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = if (quiet) CHANNEL_ID_QUIET else CHANNEL_ID
        if (manager.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(
                id,
                if (quiet) CHANNEL_NAME_QUIET else CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (quiet) setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
        return id
    }
}
