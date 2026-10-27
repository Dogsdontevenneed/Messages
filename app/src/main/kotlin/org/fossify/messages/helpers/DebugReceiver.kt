package org.fossify.messages.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import org.fossify.messages.extensions.getMessages

/**
 * PC 调试入口：让 adb 直接喂一条"假短信"，走与真实短信完全相同的
 * 解析 + 上岛链路（IslandNotifier -> promoted 通知 -> Honor 胶囊）。
 *
 * 之所以不走 SMS_DELIVER 广播：那条路要构造真实 PDU（UCS2 编码），
 * 手工拼装容易出错且不可靠。这里直接调同一套业务代码，
 * 只有"系统投递"这一段不同，其余完全一致。
 *
 * 用法：
 *   adb shell am broadcast -a org.fossify.messages.DEBUG_SMS \
 *       --es body "【测试】您的验证码是 246810，5分钟内有效。"
 *
 * 参数：
 *   --es b64      短信正文的 base64（推荐，避免中文/空格被设备 shell 切碎）
 *   --es body     短信正文（仅限纯 ASCII 且不含空格）
 *   --es sender   发件人（可选，默认"调试"）
 *   --ez dry      true = 只解析、不发通知（调规则用，不打扰状态栏）
 *   --ez quiet    true = 通知静音（不响铃不振动）
 *   --ez clear    true = 清掉当前胶囊通知
 *
 * 为什么优先用 b64：`adb shell am broadcast --es body 中文 带空格` 到设备后
 * 会被 shell 按空格分词，后半截会被当成包名过滤器，
 * 导致 body 被截断且广播投递不到本应用。base64 是纯 ASCII，不存在这个问题。
 *
 * 解析结果通过 ordered broadcast 的 result data 回传，
 * 在 adb 命令输出里能直接看到，PC 端脚本可以据此判断成功与否。
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 只在可调试的包装配。正式分发（release）后这个入口自动失效，
        // 不需要额外删代码。
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "DEBUG_IGNORED not debuggable")
            return
        }

        if (intent.getBooleanExtra("clear", false)) {
            IslandNotifier.clear(context)
            Log.i(TAG, "DEBUG_CLEAR ok")
            setResultData("CLEAR ok")
            return
        }

        // 诊断：直接调业务查询，确认某个 thread 到底能读到几条消息
        val probeThread = intent.getStringExtra("probe")?.toLongOrNull()
        if (probeThread != null) {
            val msgs = context.getMessages(probeThread)
            val uri = android.provider.Telephony.Sms.CONTENT_URI
            val resolver = context.contentResolver
            fun countOf(selection: String?, args: Array<String>?, order: String?): String {
                return runCatching {
                    resolver.query(uri, arrayOf("_id"), selection, args, order)?.use { it.count.toString() }
                        ?: "null"
                }.getOrElse { "err:${it.javaClass.simpleName}:${it.message}" }
            }
            val withLimit = countOf("thread_id = ?", arrayOf(probeThread.toString()), "date DESC LIMIT 50")
            val noLimit = countOf("thread_id = ?", arrayOf(probeThread.toString()), "date DESC")
            val inline = countOf("thread_id = $probeThread", null, null)
            val all = countOf(null, null, null)
            val vcode = countOf("body LIKE '%验证码%'", null, null)
            val pickup = countOf("body LIKE '%取件码%'", null, null)
            val visibleIds = mutableListOf<String>()
            runCatching {
                resolver.query(uri, arrayOf("_id"), "_id >= 1340", null, "_id ASC")?.use { c ->
                    while (c.moveToNext()) visibleIds.add(c.getLong(0).toString())
                }
            }
            val inboxAll = runCatching {
                resolver.query(android.provider.Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), null, null, null)
                    ?.use { it.count.toString() } ?: "null"
            }.getOrElse { "err" }
            Log.i(
                TAG,
                "PROBE thread=$probeThread getMessages=${msgs.size} withLimit=$withLimit noLimit=$noLimit " +
                    "inline=$inline | all=$all vcode=$vcode pickup=$pickup"
            )
            setResultData(
                "PROBE $probeThread gm=${msgs.size} | all=$all vcode=$vcode pickup=$pickup ids=${visibleIds.joinToString(",")}"
            )
            return
        }


        // 预览会话头像配色，不用等到会话自然进入那个状态
        intent.getStringExtra("tone")?.let { raw ->
            ConversationState.debugForceTone = when (raw.lowercase()) {
                "att", "attention", "orange" -> AvatarFactory.Tone.ATTENTION
                "recent", "apricot" -> AvatarFactory.Tone.RECENT
                "out", "outgoing", "green" -> AvatarFactory.Tone.OUTGOING
                "quiet", "blue" -> AvatarFactory.Tone.QUIET
                else -> null
            }
            Log.i(TAG, "DEBUG_TONE ${ConversationState.debugForceTone}")
            setResultData("TONE ${ConversationState.debugForceTone}")
            return
        }

        // 读剪贴板：用来验证"复制"按钮是否真的写进去了
        if (intent.getBooleanExtra("readclip", false)) {
            val mgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = mgr.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
            Log.i(TAG, "DEBUG_CLIP content=$text")
            setResultData("CLIP $text")
            return
        }

        val body = decodeBody(intent)
        if (body.isBlank()) {
            Log.w(TAG, "DEBUG_EMPTY body missing")
            setResultData("ERR body missing")
            return
        }

        val sender = intent.getStringExtra("sender") ?: "调试"
        val dry = intent.getBooleanExtra("dry", false)
        val quiet = intent.getBooleanExtra("quiet", false)

        // 直接走通知链路，用来验证通知横幅的大图标（不走胶囊）
        if (intent.getBooleanExtra("banner", false)) {
            NotificationHelper(context).showMessageNotification(
                messageId = System.currentTimeMillis(),
                isMms = false,
                address = sender,
                body = body,
                threadId = intent.getLongExtra("threadid", 999L),
                bitmap = null,
                sender = decodeExtra(intent, "name") ?: sender
            )
            Log.i(TAG, "DEBUG_BANNER ok sender=$sender")
            setResultData("BANNER ok")
            return
        }

        var hit = OtpParser.parse(body)
        val islandOverride = decodeExtra(intent, "island")
        val iconText = decodeExtra(intent, "icontext")
        // 允许覆盖胶囊上显示的文字 / 图标，用来测量宽度上限与文案效果。
        // 即使 body 里没有码，只要给了覆盖参数也照样发通知（纯样式实验用）。
        if (hit == null && (islandOverride != null || iconText != null)) {
            val placeholder = islandOverride ?: "000000"
            hit = OtpParser.Hit(
                OtpParser.Kind.OTP, placeholder, placeholder, 5 * 60 * 1000L
            )
        }
        islandOverride?.let { hit = hit?.copy(islandText = it) }
        // 覆盖有效期，方便几秒钟就能测到"超时"
        val ttlSec = intent.getIntExtra("ttlsec", 0)
        if (ttlSec > 0) hit = hit?.copy(ttlMs = ttlSec * 1000L)
        val summary = if (hit == null) {
            "PARSE none"
        } else {
            "PARSE kind=${hit.kind} code=${hit.code} island=${hit.islandText} ttl=${hit.ttlMs}"
        }
        Log.i(TAG, "DEBUG_BODY len=${body.length}")
        Log.i(TAG, summary)

        if (dry || hit == null) {
            setResultData(summary)
            return
        }

        val ok = IslandNotifier.notify(
            context, hit, sender, body,
            quiet = quiet,
            noIcon = intent.getBooleanExtra("noicon", false),
            color = intent.getIntExtra("color", 0),
            iconText = iconText,
            // 默认给个非 0 值，保证调试时「已读」按钮会出现
            threadId = intent.getLongExtra("threadid", 1L)
        )
        Log.i(TAG, "DEBUG_NOTIFY ok=$ok")
        setResultData("$summary notify=$ok")
    }

    /** 优先取 base64（避免 shell 分词与编码问题），回退到纯文本 */
    private fun decodeBody(intent: Intent): String = decodeExtra(intent, "b64")
        ?: intent.getStringExtra("body").orEmpty()

    /** 读取一个 base64 编码的字符串 extra */
    private fun decodeExtra(intent: Intent, key: String): String? {
        val b64 = intent.getStringExtra(key) ?: return null
        if (b64.isBlank()) return null
        return runCatching {
            String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "DEBUG decode '$key' failed: ${it.message}")
            null
        }
    }

    companion object {
        const val TAG = "SmsIsland"
        const val ACTION = "org.fossify.messages.DEBUG_SMS"
    }
}
