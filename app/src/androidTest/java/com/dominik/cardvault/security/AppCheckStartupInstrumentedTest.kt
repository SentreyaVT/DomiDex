package com.dominik.cardvault.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.BuildConfig
import com.dominik.cardvault.DomiDexApplication
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run only with the default-off build in the isolated offline emulator, no real attestation. */
@RunWith(AndroidJUnit4::class)
class AppCheckStartupInstrumentedTest {
    @Test fun defaultOffBuildStartsTheRegisteredApplication() {
        assertFalse("This fixture validates the default-off rollout", BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
        assertTrue(ApplicationProvider.getApplicationContext<Application>() is DomiDexApplication)
    }

    @Test fun defaultOffPrivateRequestsRemainIdenticalAndWithoutAnAppCheckHeader() {
        assertFalse(BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
        val request = Request.Builder().url("https://domidex.web.app/api/binder-shares?binderId=fixture").build()
        repeat(1_000) {
            val unchanged = DomiDexAppCheck.attachCachedHeader(request)
            assertSame(request, unchanged)
            assertNull(unchanged.header(CachedAppCheckHeader.HEADER))
        }
    }
}
