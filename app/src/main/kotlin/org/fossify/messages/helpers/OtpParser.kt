package org.fossify.messages.helpers

/**
 * 验证码 / 取件码识别。
 *
 * 设计约束（2026-10 在 Honor MagicOS 11 上实测）：
 *  - 灵动胶囊的 shortCriticalText 可用宽度约 6 个半角字符，超出会被左侧裁切
 *  - 因此 islandText 必须 <= 6 字符；完整内容放在通知正文/展开卡片里
 */
object OtpParser {

    enum class Kind { OTP, PICKUP }

    data class Hit(
        val kind: Kind,
        /** 完整码，用于复制 */
        val code: String,
        /** 胶囊上显示的文字，<= 6 字符 */
        val islandText: String,
        /** 通知自动消失的时长 */
        val ttlMs: Long
    )

    /** 验证码关键词，命中即认为是验证码类短信 */
    private val OTP_KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "确认码", "登录码", "验证代码",
        "动态密码", "一次性密码", "安全码", "激活码", "授权码",
        "verification code", "verify code", "otp", "passcode", "code is"
    )

    /**
     * 取件码强关键词：出现这些词，这条短信基本就是在通知取件。
     * 命中强关键词时，任何形态的码都认。
     */
    private val PICKUP_STRONG = listOf(
        "取件码", "取货码", "提货码", "领件码", "取件", "取快递", "取包裹",
        "快递柜", "驿站", "待取", "已到站", "揽件码"
    )

    /**
     * 取件码弱关键词：只是快递公司/平台名。
     * 仅命中弱关键词时，必须同时存在三段式码（如 8-3-2048）才判为取件码，
     * 否则"【京东】您的订单已发货 12345678"会被误判成取件码。
     */
    private val PICKUP_WEAK = listOf(
        "丰巢", "菜鸟", "兔喜", "妈妈驿站", "快宝", "驿站", "快邮",
        "顺丰", "京东", "中通", "圆通", "申通", "韵达", "极兔", "德邦", "邮政", "EMS"
    )

    /** 4-8 位连续数字，前后不能再接数字（避免从运单号中间截一段） */
    private val PLAIN_CODE = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    /** 三段式取件码，如 1-3-9030 / 8-3-2048 / 12-4-5678 */
    private val SEGMENT_CODE = Regex("""(?<!\d)(\d{1,3}-\d{1,3}-\d{3,6})(?!\d)""")

    /** “5分钟内有效” 之类 */
    private val TTL_MINUTES = Regex("""(\d{1,2})\s*分(钟)?内""")

    private const val OTP_DEFAULT_TTL = 5 * 60 * 1000L
    private const val PICKUP_TTL = 24 * 60 * 60 * 1000L
    private const val ISLAND_MAX = 6

    /** 关键词之后多少字符内找码。太短会漏（"验证码为： 246810"），太长会误抓后续数字 */
    private const val SEARCH_WINDOW = 80

    fun parse(body: String): Hit? {
        if (body.isBlank()) return null

        val hasStrongPickup = PICKUP_STRONG.any { body.contains(it, ignoreCase = true) }
        val hasWeakPickup = PICKUP_WEAK.any { body.contains(it, ignoreCase = true) }
        val isOtp = OTP_KEYWORDS.any { body.contains(it, ignoreCase = true) }

        // ── 取件码 ──
        // 强关键词：三段式优先，其次关键词后的数字，最后全文兜底
        if (hasStrongPickup) {
            SEGMENT_CODE.find(body)?.let { m ->
                return Hit(Kind.PICKUP, m.groupValues[1], tailDigits(m.groupValues[1]), PICKUP_TTL)
            }
            codeAfterKeyword(body, PICKUP_STRONG)?.let { code ->
                return Hit(Kind.PICKUP, code, code.takeLast(4), PICKUP_TTL)
            }
            PLAIN_CODE.find(body)?.let { m ->
                return Hit(Kind.PICKUP, m.groupValues[1], m.groupValues[1].takeLast(4), PICKUP_TTL)
            }
        }
        // 只有弱关键词（快递公司名）：必须出现三段式才算，防止运单号误判
        if (hasWeakPickup && !hasStrongPickup) {
            SEGMENT_CODE.find(body)?.let { m ->
                return Hit(Kind.PICKUP, m.groupValues[1], tailDigits(m.groupValues[1]), PICKUP_TTL)
            }
        }

        // ── 验证码 ──
        // 关键改进：优先在“验证码/校验码…”之后找，避免抓到短信里的订单号、客服电话
        if (isOtp) {
            val code = codeAfterKeyword(body, OTP_KEYWORDS)
                ?: PLAIN_CODE.find(body)?.groupValues?.get(1)
                ?: return null
            return Hit(Kind.OTP, code, code.take(ISLAND_MAX), ttlFrom(body))
        }

        return null
    }

    /**
     * 在最早出现的关键词之后 [SEARCH_WINDOW] 字符内找第一个码。
     * 关键词本身不含数字，所以窗口里第一个 4-8 位数字就是要找的码。
     */
    private fun codeAfterKeyword(body: String, keywords: List<String>): String? {
        val idx = keywords
            .mapNotNull { k -> body.indexOf(k, ignoreCase = true).takeIf { it >= 0 } }
            .minOrNull() ?: return null
        val end = minOf(body.length, idx + SEARCH_WINDOW)
        if (end <= idx) return null
        val window = body.substring(idx, end)
        return PLAIN_CODE.find(window)?.groupValues?.get(1)
    }

    private fun ttlFrom(body: String): Long {
        TTL_MINUTES.find(body)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: 0
            if (min in 1..60) return min * 60 * 1000L
        }
        return OTP_DEFAULT_TTL
    }

    /** 三段式取件码在胶囊上只显示末段，保证 <= 6 字符 */
    private fun tailDigits(code: String): String {
        val last = code.substringAfterLast('-')
        return if (last.length <= ISLAND_MAX) last else last.takeLast(ISLAND_MAX)
    }
}
