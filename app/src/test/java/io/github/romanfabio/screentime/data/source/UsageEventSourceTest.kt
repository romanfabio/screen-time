package io.github.romanfabio.screentime.data.source

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The minimum supported Android version is 9 (API 28); some AppOps calls only exist from API 29. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UsageEventSourceTest {

    private fun source() = UsageEventSource(ApplicationProvider.getApplicationContext<Context>())

    @Test
    @Config(sdk = [28])
    fun `usage access check works on the minimum supported version`() {
        source().hasUsageAccess() // the value depends on the simulated AppOps; the point is that it does not throw
    }

    @Test
    @Config(sdk = [34])
    fun `usage access check works on a recent version`() {
        source().hasUsageAccess()
    }
}
