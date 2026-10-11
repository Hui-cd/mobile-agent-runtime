package ai.mobileagent.agent

import ai.mobileagent.model.ApprovalRequest
import org.json.JSONObject

/**
 * 决定一个工具调用是否必须先经用户确认，并把确认内容结构化为
 * 「做什么（action）、对谁（target）、有什么后果（consequence）」。
 * 返回 null 表示该调用属于 v0 只读/低风险范围，无需确认。
 */
object ApprovalPolicy {
    private val RISKY_KEYWORDS = listOf("支付", "购买", "下单", "发送", "删除", "提交订单", "确认付款")
    private val ENGLISH_RISK = Regex("(?<![a-z])(send|delete|pay|purchase|buy|share|place order|submit order|confirm payment)(?![a-z])", RegexOption.IGNORE_CASE)

    fun approvalFor(name: String, args: JSONObject): ApprovalRequest? = when (name) {
        "device_invoke" -> invokeApproval(args)
        "device_act" -> actApproval(args)
        else -> null
    }

    private fun invokeApproval(args: JSONObject): ApprovalRequest? = when (args.optString("capability")) {
        "dial" -> {
            val number = args.optJSONObject("params")?.optString("number").orEmpty()
            ApprovalRequest(
                action = "拨打电话",
                target = number.ifBlank { "未指定号码" },
                consequence = "将打开拨号界面并显示该号码；实际呼叫仍需在拨号应用确认，可能产生通话费用。",
            )
        }
        "share" -> {
            val params = args.optJSONObject("params") ?: JSONObject()
            val content = params.optString("text").ifBlank { params.optString("url") }.take(60)
            ApprovalRequest(
                action = "分享内容",
                target = content.ifBlank { "未指定内容" },
                consequence = "内容会交给系统分享面板，由你选择的应用接收；内容离开本应用后不再受控。",
            )
        }
        else -> null
    }

    private fun actApproval(args: JSONObject): ApprovalRequest? {
        val action = args.optString("action")
        if (action !in setOf("click", "long_press")) return null
        val target = args.optJSONObject("target") ?: JSONObject()
        val readableTarget = listOf("text", "content_description")
            .map(target::optString)
            .firstOrNull(String::isNotBlank)
        val searchableTarget = listOf(
            target.optString("text"),
            target.optString("content_description"),
            target.optString("resource_id"),
        ).joinToString(" ")
        val matched = (RISKY_KEYWORDS.filter(searchableTarget::contains) +
            ENGLISH_RISK.findAll(searchableTarget).map { it.value }.toList()).distinct()
        if (matched.isEmpty() && readableTarget != null) return null
        return ApprovalRequest(
            action = "界面操作：$action",
            target = readableTarget?.take(60)
                ?: target.optString("resource_id").take(60).ifBlank { "无法识别的界面目标" },
            consequence = if (matched.isEmpty()) {
                "目标没有可供核对的文字，无法确认是否会产生外部影响。"
            } else {
                "目标包含「${matched.joinToString("、")}」，可能产生不可撤销的实际后果（付款、下单、发送或删除）。"
            },
        )
    }
}
