package ai.mobileagent.virtualdisplay

/** Authorization is readiness to connect, not evidence that a display already exists. */
object ShizukuAccessPolicy {
    fun requireConnectable(sdk: Int, running: Boolean, authorized: Boolean) {
        check(sdk >= 34) { "HIDDEN_EXECUTION_REQUIRES_ANDROID_14" }
        check(running) { "SHIZUKU_UNAVAILABLE" }
        check(authorized) { "SHIZUKU_NOT_AUTHORIZED" }
    }
}
