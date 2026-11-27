/*
 * ProfilePhotoNavigationTest.kt
 *
 * Purpose: Verify profile image picking, encoding, replacement, removal, and retry on S11.
 * Role: Android UI tests through the shared router, with an intercepted system picker and fake profile.
 * Outstanding issues: None.
 */
package com.example.legion_events

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.*
import com.example.legion_events.model.*
import com.example.legion_events.ui.theme.LegioneventsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Exercises a real content URI and image encoder without touching Firebase or device galleries. */
class ProfilePhotoNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.cacheDir, "qr/profile-image-test.png")
    private val profiles = FakeProfile()
    private val picker = Picker()
    private lateinit var restoration: StateRestorationTester

    @After fun cleanUp() { file.delete() }

    @Test fun addChangeAndRemoveImagesPreserveUnsavedContactEditsAndDoNotRepeatAfterRestoration() {
        open()
        compose.onNodeWithText("Alex").performTextReplacement("Unsaved name")
        picker.uri = imageUri()
        compose.onNodeWithText("Add image").performClick()
        waitFor("Change image")
        waitForImage()
        compose.runOnIdle {
            assertEquals(1, profiles.writes)
            assertTrue(checkNotNull(profiles.image).imageBase64.length <= 280_000)
            assertEquals("image/jpeg", profiles.image?.mimeType)
            assertEquals("user", profiles.image?.targetType)
        }
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Change image")
        compose.runOnIdle { assertEquals(1, profiles.writes); assertEquals(1, picker.launches) }
        compose.onNodeWithText("Change image").performClick()
        compose.waitUntil(5_000) { profiles.writes == 2 }
        waitFor("Change image")
        compose.onNodeWithText("Remove image").performClick()
        waitFor("Add image")
        compose.onNodeWithText("Unsaved name").assertExists()
        compose.runOnIdle { assertEquals(1, profiles.removals); assertNull(profiles.image) }
    }

    @Test fun cancelledPickerDoesNothingAndFailedImageSavesRetryTheSamePhotoId() {
        open(dark = true)
        compose.onNodeWithText("Add image").performClick()
        compose.runOnIdle { assertEquals(0, profiles.writes) }
        picker.uri = imageUri()
        profiles.fail = true
        compose.onNodeWithText("Add image").performClick()
        waitFor("Retry image")
        compose.runOnIdle { assertNull(profiles.image); profiles.fail = false }
        compose.onNodeWithText("Retry image").performClick()
        waitFor("Change image")
        compose.runOnIdle { assertEquals(2, profiles.writes); assertEquals(1, profiles.ids.distinct().size) }
    }

    /** Starts the profile tab with synthetic account and profile data and a fake activity-result owner. */
    private fun open(dark: Boolean = false) {
        lateinit var account: OnboardingController
        compose.runOnUiThread { account = OnboardingController(object : UserRepository { override suspend fun initializeUser() = UserProfile("alice") }) }
        val campsites = object : CampsiteRepository { override fun observeCampsites() = flowOf(emptyList<Campsite>()) }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                LegioneventsTheme(darkTheme = dark) { AppNavigation(account, campsites = campsites, profiles = profiles) }
            }
        }
        waitFor("Explore campsites")
        compose.onNodeWithText("Profile").performClick()
        waitFor("Add image")
        compose.onNodeWithText("Add image").assertIsEnabled()
    }

    /** Generates an isolated PNG so the production encoder can read, resize, and compress a real image. */
    private fun imageUri(): Uri {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Waits for decoded image rendering rather than only the save callback. */
    private fun waitForImage() { compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Your profile image").fetchSemanticsNodes().isNotEmpty() } }
    /** Waits for asynchronous controller state to appear. */
    private fun waitFor(text: String) { compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }

    /** Returns a selected URI or cancellation without launching a real gallery application. */
    private inner class Picker : ActivityResultRegistry() {
        var uri: Uri? = null; var launches = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            dispatchResult(requestCode, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, uri?.let { Intent().setData(it) })
        }
    }

    /** Keeps one image while rejecting unrelated contact and account writes from these tests. */
    private class FakeProfile : ProfileRepository {
        var image: Photo? = null; var fail = false; var writes = 0; var removals = 0
        val ids = mutableListOf<String>()
        override suspend fun getPhoto() = image
        override suspend fun setPhoto(photoId: String, jpegBase64: String, previousPhotoId: String?): Photo {
            writes++; ids += photoId
            if (fail) error("Unavailable")
            return Photo(photoId, "alice", "user", "alice", jpegBase64, "image/jpeg").also { image = it }
        }
        override suspend fun removePhoto(photoId: String) { removals++; image = null }
        override suspend fun getProfile() = UserProfile("alice", "Alex")
        override suspend fun updateContact(name: String, email: String, phone: String): Unit = error("Unexpected contact save")
        override suspend fun setNotificationsEnabled(enabled: Boolean): Unit = error("Unexpected preference save")
        override suspend fun deleteProfile(): Unit = error("Unexpected account delete")
    }
}
