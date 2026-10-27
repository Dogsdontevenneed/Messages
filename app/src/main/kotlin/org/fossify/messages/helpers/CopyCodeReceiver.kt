package org.fossify.messages.helpers

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/** 点击胶囊展开卡片上的"复制"按钮后执行 */
class CopyCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(IslandNotifier.EXTRA_CODE).orEmpty()
        if (code.isBlank()) {
            Log.w(IslandNotifier.TAG, "COPY skip empty code")
            return
        }

        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val ok = runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("code", code))
            // 回读校验，确认真的写进去了
            val readBack = manager.primaryClip?.getItemAt(0)?.text?.toString()
            readBack == code
        }.onFailure {
            Log.e(IslandNotifier.TAG, "COPY failed", it)
        }.getOrDefault(false)

        Log.i(IslandNotifier.TAG, "COPY code=$code ok=$ok")
        Toast.makeText(context, if (ok) "已复制 $code" else "复制失败", Toast.LENGTH_SHORT).show()
    }
}
