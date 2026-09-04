package ai.mobileagent.benchmark

enum class LoginState(val wireValue: String) {
    SIGNED_IN("signed_in"),
    SIGNED_OUT("signed_out"),
    NOT_APPLICABLE("not_applicable"),
    UNKNOWN("unknown"),
}

object LoginStateProbe {
    fun classify(packageName: String?, visibleTexts: Collection<String>): LoginState {
        if (packageName == null || packageName == "ai.mobileagent") return LoginState.NOT_APPLICABLE
        if (packageName != WECHAT_PACKAGE) return LoginState.UNKNOWN
        val texts = visibleTexts.map(String::trim).filter(String::isNotEmpty).toSet()
        if (WECHAT_SIGNED_IN_TABS.all(texts::contains)) return LoginState.SIGNED_IN
        if (WECHAT_SIGNED_OUT_MARKERS.all(texts::contains)) return LoginState.SIGNED_OUT
        return LoginState.UNKNOWN
    }

    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private val WECHAT_SIGNED_IN_TABS = setOf("微信", "通讯录", "发现", "我")
    private val WECHAT_SIGNED_OUT_MARKERS = setOf("登录", "注册")
}

class LoginStateTracker {
    var first: LoginState? = null
        private set
    var last: LoginState? = null
        private set
    private var determinateObservations = 0
    private var observedLoss = false

    fun record(packageName: String?, visibleTexts: Collection<String>): LoginState {
        val state = LoginStateProbe.classify(packageName, visibleTexts)
        if (state == LoginState.SIGNED_IN || state == LoginState.SIGNED_OUT) {
            if (first == LoginState.SIGNED_IN && state == LoginState.SIGNED_OUT) observedLoss = true
            if (first == null) first = state
            last = state
            determinateObservations += 1
        }
        return state
    }

    val loginLost: Boolean?
        get() = when {
            first != LoginState.SIGNED_IN -> null
            observedLoss -> true
            last == LoginState.SIGNED_IN && determinateObservations >= 2 -> false
            else -> null
        }
}
