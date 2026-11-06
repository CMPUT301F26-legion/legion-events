package com.example.legion_events

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.data.createQrBitmap
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.CampsiteQr
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.example.legion_events.view.QrCaptureActivity
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.client.android.Intents
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the actual scanner result contract and router with controlled results and fake Firestore reads. */
class QrScanNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val camera = TestCameraRegistry()
    private lateinit var navigation: NavHostController
    private lateinit var restoration: StateRestorationTester
    private val site = Campsite("pine #1", "alice", "Pine Ridge", "Banff", 6, 6500)
    private var detailReads = 0

    @Test fun scanOpensDetailsOnceAndBackPreservesSearch() {
        openSearch()
        compose.onNodeWithText("Search campsites").performTextInput("Banff")
        compose.onNodeWithText("Search campsites").performImeAction()
        compose.onNodeWithText("4+ guests").performClick()
        compose.onNodeWithContentDescription("Scan campsite QR code").performClick()
        compose.runOnIdle { assertEquals(1, camera.launches) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(1, camera.launches)
            camera.finish(Activity.RESULT_OK, Intent().putExtra(Intents.Scan.RESULT, CampsiteQr.encode(site.id))
                .putExtra(Intents.Scan.RESULT_FORMAT, "QR_CODE"))
        }
        waitForText("Back to search")
        compose.runOnIdle {
            assertEquals("campsite/{campsiteId}", navigation.currentDestination?.route)
            assertEquals(site.id, navigation.currentBackStackEntry?.arguments?.getString("campsiteId"))
            assertEquals("home", navigation.previousBackStackEntry?.destination?.route)
            assertEquals(1, detailReads)
        }
        compose.onNodeWithText("Back to search").performClick()
        waitForText("Explore campsites")
        compose.onNodeWithText("Search campsites").assertTextContains("Banff")
        compose.onNodeWithText("4+ guests").assertIsSelected()
        compose.runOnIdle { assertEquals(1, camera.launches) }
    }

    @Test fun invalidCodeAndDeniedPermissionOfferRecoveryAndCancellationReturnsHome() {
        openSearch()
        compose.onNodeWithContentDescription("Scan campsite QR code").performClick()
        compose.runOnIdle {
            camera.finish(Activity.RESULT_OK, Intent().putExtra(Intents.Scan.RESULT, "https://example.com"))
        }
        compose.onNodeWithText("This is not a campsite QR code. Scan a code shared from a campsite listing.").assertIsDisplayed()
        compose.onNodeWithText("Retry scan").performClick()
        compose.runOnIdle {
            assertEquals(2, camera.launches)
            camera.finish(Activity.RESULT_CANCELED, Intent().putExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true))
        }
        compose.onNodeWithText("Camera permission is needed to scan a campsite QR code.").assertIsDisplayed()
        compose.onNodeWithText("Open app settings").assertIsDisplayed()
        compose.onNodeWithText("Retry scan").performClick()
        compose.runOnIdle { camera.finish(Activity.RESULT_CANCELED, null) }
        waitForText("Explore campsites")
        compose.runOnIdle {
            assertEquals(0, detailReads)
            assertNull(navigation.previousBackStackEntry)
        }
    }

    @Test fun qrGeneratedByTheOwnerCanBeDecodedByZxing() {
        val bitmap = createQrBitmap(CampsiteQr.encode(site.id), 400)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        assertEquals(site.id, CampsiteQr.decode(MultiFormatReader().decode(image).text))
        bitmap.recycle()
    }

    /** Starts the real navigation graph with a fake camera result registry and repository data. */
    private fun openSearch() {
        lateinit var onboarding: OnboardingController
        compose.runOnUiThread {
            onboarding = OnboardingController(object : UserRepository {
                override suspend fun initializeUser() = UserProfile("alice")
            })
        }
        val campsites = object : CampsiteRepository {
            override fun observeCampsites() = flowOf(listOf(site))
        }
        val details = object : CampsiteDetailsRepository {
            override suspend fun getCampsite(campsiteId: String): Campsite {
                assertEquals(site.id, campsiteId)
                detailReads++
                return site
            }
            override suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?) = emptyList<Photo>()
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = camera }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            navigation = rememberNavController()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                LegioneventsTheme { AppNavigation(onboarding, navigation, campsites, details) }
            }
        }
        waitForText("Pine Ridge")
    }

    /** Waits for asynchronous controller and navigation state to appear in Compose. */
    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Captures camera launches, checking their QR-only options before supplying Android activity results. */
    private class TestCameraRegistry : ActivityResultRegistry() {
        var launches = 0
        private var pendingRequest = 0

        /** Records a camera launch without opening hardware during deterministic navigation tests. */
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val intent = contract.createIntent(context, input)
            assertEquals(QrCaptureActivity::class.java.name, intent.component?.className)
            assertEquals("QR_CODE", intent.getStringExtra(Intents.Scan.FORMATS))
            assertFalse(intent.getBooleanExtra(Intents.Scan.BARCODE_IMAGE_ENABLED, true))
            pendingRequest = requestCode
            launches++
        }

        /** Delivers a result through ScanContract, including cancellation and permission-denial intents. */
        fun finish(resultCode: Int, result: Intent?) { dispatchResult(pendingRequest, resultCode, result) }
    }
}
