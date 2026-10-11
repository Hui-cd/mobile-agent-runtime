package ai.mobileagent.virtualdisplay

/**
 * 安全窗口 / 凭据 / 锁屏的 fail-closed 判定。纯策略，不依赖 Android 框架，便于 JVM 单测。
 *
 * 语义：
 * - secure 窗口（FLAG_SECURE）出现时截图必然不可靠（黑图），必须把该观察判为
 *   FAIL_CLOSED_SECURE_WINDOW 并请求用户接管，不得把黑图当作成功证据；
 * - 凭据/验证码/支付类语义出现时拒绝注入并请求用户接管；
 * - 锁屏/keyguard 出现时不得尝试绕过。
 */
sealed interface FailClosedDecision {
    data object Proceed : FailClosedDecision

    /** 停止设备动作，请求用户接管；调用方必须保留底层 failure code。 */
    data class RequireUserTakeover(val errorCode: String, val detail: String) : FailClosedDecision
}

data class WindowSignal(
    val hasSecureFlag: Boolean,
    val showsKeyguard: Boolean,
    val credentialKeywordHits: List<String>,
    val captchaKeywordHits: List<String>,
    val paymentKeywordHits: List<String>,
    val treeUnreadable: Boolean,
) {
    companion object {
        fun clean() = WindowSignal(
            hasSecureFlag = false,
            showsKeyguard = false,
            credentialKeywordHits = emptyList(),
            captchaKeywordHits = emptyList(),
            paymentKeywordHits = emptyList(),
            treeUnreadable = false,
        )
    }
}

object SecureWindowPolicy {

    fun evaluate(signal: WindowSignal, wantsScreenshot: Boolean, wantsInputInjection: Boolean): FailClosedDecision {
        if (signal.showsKeyguard) {
            return FailClosedDecision.RequireUserTakeover("DEVICE_LOCKED", "keyguard is showing; unlock and take over")
        }
        if (signal.hasSecureFlag && (wantsScreenshot || wantsInputInjection)) {
            return FailClosedDecision.RequireUserTakeover(
                "FAIL_CLOSED_SECURE_WINDOW",
                "secure window present; observation/input would be black or rejected",
            )
        }
        if (wantsInputInjection && signal.credentialKeywordHits.isNotEmpty()) {
            return FailClosedDecision.RequireUserTakeover(
                "FAIL_CLOSED_CREDENTIAL",
                "credential surface: ${signal.credentialKeywordHits.joinToString(",") { redactKeyword(it) }}",
            )
        }
        // 验证码/支付面与主动被动无关：任何设备工具调用都必须停止并请求用户接管。
        if (signal.captchaKeywordHits.isNotEmpty()) {
            return FailClosedDecision.RequireUserTakeover(
                "FAIL_CLOSED_CAPTCHA",
                "captcha surface present; user must solve",
            )
        }
        if (signal.paymentKeywordHits.isNotEmpty()) {
            return FailClosedDecision.RequireUserTakeover(
                "FAIL_CLOSED_PAYMENT",
                "payment surface present; approval + user takeover required",
            )
        }
        if (signal.treeUnreadable) {
            return FailClosedDecision.RequireUserTakeover("UNREADABLE_PAGE", "cannot build a trustworthy UI tree")
        }
        return FailClosedDecision.Proceed
    }

    /** 关键词不进错误 detail 的原文，避免凭据类文字进入日志。 */
    private fun redactKeyword(keyword: String): String = "<redacted:${keyword.length}>"
}
