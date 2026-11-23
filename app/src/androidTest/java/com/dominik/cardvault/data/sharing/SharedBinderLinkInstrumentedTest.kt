package com.dominik.cardvault.data.sharing

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Read-only manifest/URI checks: no activity launch, account, network or persisted state. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class SharedBinderLinkInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = "A".repeat(43)
    private val link = "https://domidex.web.app/share/$token"
    private val viewerClass get() = "${context.packageName}.SharedBinderActivity"

    @Test fun verifiedShareRouteTargetsOnlyTheSeparateResizableViewer() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
        assertEquals(listOf(viewerClass), matches.map { it.activityInfo.name })
        assertTrue(sharingFilterAutoVerified())
        val info = context.packageManager.getActivityInfo(ComponentName(context.packageName, viewerClass), 0)
        assertTrue(info.exported)
        // Release 27 deliberately leaves the public viewer resizable. This keeps
        // a shared binder usable on tablets and foldables instead of preserving
        // the earlier phone-only portrait lock.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, info.screenOrientation)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
        assertEquals(ActivityInfo.PERSIST_NEVER, info.persistableMode)
    }

    @Test fun unrelatedWebRoutesAndHttpAreNotClaimedByTheViewer() {
        for (uri in listOf("https://domidex.web.app/app", "https://domidex.web.app/admin",
            "https://evil.invalid/share/$token", "http://domidex.web.app/share/$token")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
            assertTrue(uri, context.packageManager.queryIntentActivities(intent, 0).isEmpty())
        }
    }

    @Test fun androidUriParsingDoesNotAdmitEncodedTokensQueriesOrFragments() {
        assertEquals(token, sharedBinderTokenFromUri(Uri.parse(link)))
        for (uri in listOf("$link?", "$link#", "$link/extra", link.dropLast(1) + "%41",
            link.replace("/share/", "/share%2F"), link.dropLast(1) + "B")) {
            assertNull(uri, sharedBinderTokenFromUri(Uri.parse(uri)))
        }
        assertNull(sharedBinderTokenFromUri(null))
    }

    private fun sharingFilterAutoVerified(): Boolean {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        context.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
            var inViewer = false
            while (manifest.eventType != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType == XmlPullParser.START_TAG) {
                    if (manifest.name == "activity") {
                        inViewer = manifest.getAttributeValue(androidNamespace, "name") in
                            listOf(".SharedBinderActivity", viewerClass)
                    } else if (inViewer && manifest.name == "intent-filter") {
                        return manifest.getAttributeBooleanValue(androidNamespace, "autoVerify", false)
                    }
                } else if (manifest.eventType == XmlPullParser.END_TAG && manifest.name == "activity") {
                    inViewer = false
                }
                manifest.next()
            }
        }
        return false
    }
}
