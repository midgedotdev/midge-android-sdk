package dev.midge.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogsTest {
    private val lines = listOf(
        "--------- beginning of main",
        "  1763072100.120  5122  5122 I AcmeShop: Loaded products: HTTP 200",
        "  1763072100.200  5122  5140 D VRI[ReportActivity]: visibilityChanged oldVisibility=true",
        "  1763072100.300  5122  5122 E AcmeShop: Checkout failed: HTTP 404",
        "  1763072100.400  5122  5133 W OpenGLRenderer: Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED",
        "  1763072100.500  5122  5122 W com.acme.Cart: stale price",
        "  1763072101.900  5122  5122 F AndroidRuntime: FATAL EXCEPTION: main",
        "  1763072105.000  5122  5122 I AcmeShop: after the recording",
    )

    @Test fun keepsTheAppsOwnLinesInTheWindowWithTheirLevels() {
        val events = AppLogs.parse(lines, 1763072100_000.0, 1763072102_000.0)
        assertEquals(
            listOf(
                "info [AcmeShop] Loaded products: HTTP 200",
                "error [AcmeShop] Checkout failed: HTTP 404",
                "warn [com.acme.Cart] stale price",
                "error [AndroidRuntime] FATAL EXCEPTION: main",
            ),
            events.map { "${it.getString("level")} ${it.getString("text")}" },
        )
        assertEquals(1763072100_120.0, events.first().getDouble("at"), 0.5)
    }

    @Test fun keepsOnlyTheNewestLinesOverTheLimit() {
        val events = AppLogs.parse(lines, 0.0, Double.MAX_VALUE, limit = 2)
        assertEquals(listOf("[AndroidRuntime] FATAL EXCEPTION: main", "[AcmeShop] after the recording"), events.map { it.getString("text") })
    }

    @Test fun frameworkTags() {
        assert(AppLogs.isFramework("ViewRootImpl"))
        assert(AppLogs.isFramework("VRI[MainActivity]"))
        assert(AppLogs.isFramework("cr_AwContents"))
        assert(!AppLogs.isFramework("AcmeShop"))
        assert(!AppLogs.isFramework("System.out"))
    }
}
