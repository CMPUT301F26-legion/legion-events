/*
 * FirebaseProfilePhotoRepositoryTest.kt
 *
 * Purpose: Verify atomic profile image replacement, removal, ownership, and uncertain retries.
 * Role: Local JVM tests of FirebaseProfileRepository at mocked SDK boundaries.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/** Checks only photo/pointer writes; never connects to Firebase. */
class FirebaseProfilePhotoRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val users = mock(CollectionReference::class.java)
    private val photos = mock(CollectionReference::class.java)
    private val profile = mock(DocumentReference::class.java)
    private val old = mock(DocumentReference::class.java)
    private val fresh = mock(DocumentReference::class.java)
    private val transaction = mock(Transaction::class.java)
    private val repository = FirebaseProfileRepository(auth, firestore)

    @Before fun setUp() {
        `when`(auth.currentUser).thenReturn(user); `when`(user.uid).thenReturn("alice")
        `when`(firestore.collection("users")).thenReturn(users); `when`(users.document("alice")).thenReturn(profile)
        `when`(firestore.collection("photos")).thenReturn(photos)
        `when`(photos.document("old")).thenReturn(old); `when`(photos.document("new")).thenReturn(fresh)
        val person = doc(mapOf("photoId" to "old", "disabled" to false), profile)
        val image = image()
        val missing = doc(emptyMap(), fresh, false)
        `when`(profile.get()).thenReturn(Tasks.forResult(person)); `when`(old.get()).thenReturn(Tasks.forResult(image))
        `when`(transaction.get(profile)).thenReturn(person); `when`(transaction.get(old)).thenReturn(image)
        `when`(transaction.get(fresh)).thenReturn(missing)
        doAnswer { invocation ->
            try { invocation.getArgument<Transaction.Function<Unit>>(0).apply(transaction); Tasks.forResult(Unit) }
            catch (failure: Exception) { Tasks.forException<Unit>(failure) }
        }.`when`(firestore).runTransaction<Unit>(any())
    }

    @Test fun replacementWritesExactPhotoFieldsAndOnlyTheProfilePointerAndTime() = runTest {
        val saved = repository.setPhoto("new", "jpeg", "old")
        assertEquals("new", saved.id); assertEquals("alice", saved.targetId)
        verify(transaction).set(fresh, mapOf("ownerId" to "alice", "targetType" to "user", "targetId" to "alice",
            "imageBase64" to "jpeg", "mimeType" to "image/jpeg", "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        verify(transaction).update(profile, mapOf("photoId" to "new", "updatedAt" to FieldValue.serverTimestamp()))
        verify(transaction).delete(old)
    }

    @Test fun retryOfCommittedReplacementDoesNotCreateOrDeleteAgain() = runTest {
        val person = doc(mapOf("photoId" to "new", "disabled" to false), profile)
        val image = image(id = "new", reference = fresh, bytes = "jpeg")
        `when`(transaction.get(profile)).thenReturn(person); `when`(transaction.get(fresh)).thenReturn(image)
        assertEquals("new", repository.setPhoto("new", "jpeg", "old").id)
        verify(transaction, never()).set(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(transaction, never()).delete(any(DocumentReference::class.java))
    }

    @Test fun removalClearsThePointerAndOwnedImageButRepeatedRemovalDoesNothing() = runTest {
        repository.removePhoto("old")
        verify(transaction).update(profile, mapOf("photoId" to null, "updatedAt" to FieldValue.serverTimestamp()))
        verify(transaction).delete(old)
        val removed = doc(mapOf("photoId" to null, "disabled" to false), profile)
        `when`(transaction.get(profile)).thenReturn(removed)
        repository.removePhoto("old")
        verify(transaction, times(1)).delete(old)
    }

    @Test fun staleRemovalDoesNotDeleteANewerReplacement() = runTest {
        val person = doc(mapOf("photoId" to "new", "disabled" to false), profile)
        `when`(transaction.get(profile)).thenReturn(person)
        fails { repository.removePhoto("old") }
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(transaction, never()).delete(any(DocumentReference::class.java))
    }

    @Test fun replacementRejectsAStaleDisplayedPhotoInsteadOfOverwritingAnotherSession() = runTest {
        fails { repository.setPhoto("new", "jpeg", "stale-photo") }
        fails { repository.setPhoto("new", "jpeg", null) }
        verify(transaction, never()).set(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(transaction, never()).delete(any(DocumentReference::class.java))
    }

    @Test fun foreignAndCampsitePhotosAreNeverDisplayedOrDeletedAsProfileImages() = runTest {
        for (fields in listOf(mapOf("ownerId" to "bob"), mapOf("targetType" to "campsite"), mapOf("targetId" to "bob"))) {
            val foreign = image(extra = fields)
            `when`(old.get()).thenReturn(Tasks.forResult(foreign)); `when`(transaction.get(old)).thenReturn(foreign)
            assertNull(repository.getPhoto())
            repository.removePhoto("old")
        }
        verify(transaction, never()).delete(old)
    }

    @Test fun accountChangesAndInvalidInputsDoNotWrite() = runTest {
        fails { repository.setPhoto("nested/path", "jpeg", "old") }
        fails { repository.setPhoto("new", "", "old") }
        fails { repository.setPhoto("new", "x".repeat(280_001), "old") }
        val owned = image()
        `when`(old.get()).thenAnswer { `when`(auth.currentUser).thenReturn(null); Tasks.forResult(owned) }
        fails { repository.getPhoto() }
        fails { repository.setPhoto("new", "jpeg", "old") }
        verify(transaction, never()).set(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    /** Builds a profile-target photo, allowing a test to replace individual ownership fields. */
    private fun image(id: String = "old", reference: DocumentReference = old, bytes: String = "old-jpeg", extra: Map<String, Any?> = emptyMap()) =
        doc(mapOf("ownerId" to "alice", "targetType" to "user", "targetId" to "alice", "imageBase64" to bytes, "mimeType" to "image/jpeg") + extra,
            reference, id = id)

    /** Supplies a Firestore snapshot with a real mocked reference for delete assertions. */
    private fun doc(fields: Map<String, Any?>, reference: DocumentReference, exists: Boolean = true, id: String = "alice") =
        mock(DocumentSnapshot::class.java).also { snapshot ->
            `when`(snapshot.exists()).thenReturn(exists); `when`(snapshot.reference).thenReturn(reference); `when`(snapshot.id).thenReturn(id)
            fields.forEach { (key, value) -> `when`(snapshot.get(key)).thenReturn(value) }
        }

    /** Asserts a failed suspend operation rather than silently accepting a write. */
    private suspend fun fails(action: suspend () -> Unit) { try { action() } catch (_: Exception) { return }; fail("Expected failure") }
}
