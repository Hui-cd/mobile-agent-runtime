package ai.mobileagent.runtime

import org.junit.Assert.*
import org.junit.Test

class AppCatalogTest {
    private val apps = listOf(
        LaunchableApp("vendor.reader.one", "Reader"),
        LaunchableApp("vendor.reader.two", "Reader"),
        LaunchableApp("vendor.unknown.app", "陌生应用"),
    )
    @Test fun `arbitrary installed labels and exact packages resolve without an alias table`() {
        assertEquals("vendor.unknown.app", AppCatalog.resolve(apps, "陌生应用"))
        assertEquals("vendor.reader.two", AppCatalog.resolve(apps, "vendor.reader.two"))
    }
    @Test fun `duplicate application names require exact package`() {
        val error = assertThrows(IllegalStateException::class.java) { AppCatalog.resolve(apps, "Reader") }
        assertTrue(error.message!!.startsWith("APP_AMBIGUOUS"))
    }
    @Test fun `unknown names and empty labels cannot silently resolve`() {
        assertThrows(IllegalStateException::class.java) { AppCatalog.resolve(apps + LaunchableApp("empty", ""), "不存在") }
        assertThrows(IllegalArgumentException::class.java) { AppCatalog.resolve(apps, " ") }
    }
}
