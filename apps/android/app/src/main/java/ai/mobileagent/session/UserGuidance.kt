package ai.mobileagent.session

import org.json.JSONObject

object UserGuidance {
    fun step(name: String, args: JSONObject): String = when (name) {
        "device_observe" -> "正在查看当前页面"
        "device_act" -> when (args.optString("action")) {
            "click", "long_press" -> "正在选择页面上的目标"
            "input" -> "正在填写内容"
            "scroll", "swipe" -> "正在浏览更多内容"
            "back" -> "正在返回上一页"
            "home" -> "正在返回桌面"
            else -> "正在操作页面"
        }
        "device_invoke" -> when (args.optString("capability")) {
            "open_app" -> "正在打开应用"
            "open_settings" -> "正在打开系统设置"
            "open_url", "deep_link" -> "正在打开链接"
            "navigate" -> "正在打开导航"
            "dial" -> "正在打开拨号界面"
            "share" -> "正在打开分享面板"
            else -> "正在调用手机功能"
        }
        else -> "正在处理任务"
    }
    fun readiness(model: Boolean, control: Boolean, notifications: Boolean): String? = when {
        !model -> "请先在模型设置中保存密钥。"
        !control -> "请先开启设备控制：在系统无障碍设置中启用 Mobile Agent。"
        !notifications -> "请先开启通知，以便操作其他应用时查看进度和停止任务。"
        else -> null
    }

    fun error(message: String?): String = when {
        message.orEmpty().contains("DEVICE_LOCKED") -> "手机已锁定。请解锁后重新发起任务。"
        message.orEmpty().contains("SCREEN_NOT_INTERACTIVE") -> "屏幕已关闭。请点亮并解锁手机后重试。"
        message.orEmpty().contains("ACCESSIBILITY") -> "设备控制暂时不可用。请检查系统无障碍设置，然后重试。"
        message.orEmpty().contains("HTTP 401") || message.orEmpty().contains("HTTP 403") -> "模型连接被拒绝。请检查密钥和模型权限。"
        message.orEmpty().contains("HTTP 429") -> "模型服务暂时限制请求。请稍后重试或检查账户额度。"
        message.orEmpty().contains("MODEL_API_ERROR") -> "模型服务未能返回结果。请检查模型设置或稍后重试。"
        message.orEmpty().contains("STEP_LIMIT") -> "任务步骤过多，已停止。请缩小目标后重新发起任务。"
        else -> "任务未能继续。请检查网络和目标应用状态，再重试。"
    }
}
