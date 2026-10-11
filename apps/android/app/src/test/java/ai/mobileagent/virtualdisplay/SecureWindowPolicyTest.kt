package ai.mobileagent.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureWindowPolicyTest {

    @Test
    fun `clean window proceeds`() {
        val decision = SecureWindowPolicy.evaluate(WindowSignal.clean(), wantsScreenshot = true, wantsInputInjection = true)
        assertEquals(FailClosedDecision.Proceed, decision)
    }

    @Test
    fun `secure window fails closed for screenshot and injection`() {
        val signal = WindowSignal.clean().copy(hasSecureFlag = true)
        assertEquals(
            "FAIL_CLOSED_SECURE_WINDOW",
            (SecureWindowPolicy.evaluate(signal, wantsScreenshot = true, wantsInputInjection = false) as FailClosedDecision.RequireUserTakeover).errorCode,
        )
        assertEquals(
            "FAIL_CLOSED_SECURE_WINDOW",
            (SecureWindowPolicy.evaluate(signal, wantsScreenshot = false, wantsInputInjection = true) as FailClosedDecision.RequireUserTakeover).errorCode,
        )
    }

    @Test
    fun `credential keyword blocks input injection but not passive observation`() {
        val signal = WindowSignal.clean().copy(credentialKeywordHits = listOf("password"))
        assertEquals(
            "FAIL_CLOSED_CREDENTIAL",
            (SecureWindowPolicy.evaluate(signal, wantsScreenshot = false, wantsInputInjection = true) as FailClosedDecision.RequireUserTakeover).errorCode,
        )
        assertEquals(FailClosedDecision.Proceed, SecureWindowPolicy.evaluate(signal, wantsScreenshot = true, wantsInputInjection = false))
    }

    @Test
    fun `captcha and payment surfaces always require takeover`() {
        val captcha = WindowSignal.clean().copy(captchaKeywordHits = listOf("captcha"))
        val payment = WindowSignal.clean().copy(paymentKeywordHits = listOf("checkout"))
        assertEquals(
            "FAIL_CLOSED_CAPTCHA",
            (SecureWindowPolicy.evaluate(captcha, wantsScreenshot = false, wantsInputInjection = false) as FailClosedDecision.RequireUserTakeover).errorCode,
        )
        assertEquals(
            "FAIL_CLOSED_PAYMENT",
            (SecureWindowPolicy.evaluate(payment, wantsScreenshot = true, wantsInputInjection = false) as FailClosedDecision.RequireUserTakeover).errorCode,
        )
    }

    @Test
    fun `keyguard never proceeds`() {
        val decision = SecureWindowPolicy.evaluate(WindowSignal.clean().copy(showsKeyguard = true), false, false)
        assertEquals("DEVICE_LOCKED", (decision as FailClosedDecision.RequireUserTakeover).errorCode)
    }

    @Test
    fun `credential detail redacts keyword content`() {
        val signal = WindowSignal.clean().copy(credentialKeywordHits = listOf("hunter2"))
        val decision = SecureWindowPolicy.evaluate(signal, wantsScreenshot = false, wantsInputInjection = true) as FailClosedDecision.RequireUserTakeover
        assertTrue(decision.detail.contains("<redacted:"))
        assertTrue(!decision.detail.contains("hunter2"))
    }
}
