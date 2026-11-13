/*
 * FirebaseAdminRepositoryTest.kt
 *
 * Purpose: Verify that admin removals change only the fields the admin security rules allow.
 * Role: Local JVM tests for the data layer using mocked Firebase SDK boundaries.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Checks the real write implementation at mocked Firebase boundaries; never contacts Firestore. */
class FirebaseAdminRepositoryTest {
    private val firestore = mock(FirebaseFirestore::class.java)
    private val campsites = mock(CollectionReference::class.java)
    private val users = mock(CollectionReference::class.java)
    private val repository = FirebaseAdminRepository(firestore)

    @Before fun setUp() {
        `when`(firestore.collection("campsites")).thenReturn(campsites)
        `when`(firestore.collection("users")).thenReturn(users)
    }

    @Test fun removeCampsiteChangesOnlyActiveAndUpdatedAt() = runTest {
        val document = mock(DocumentReference::class.java)
        `when`(campsites.document("lake")).thenReturn(document)
        `when`(document.update(anyMap())).thenReturn(Tasks.forResult(null))
        repository.removeCampsite("lake")
        verify(document).update(mapOf("active" to false, "updatedAt" to FieldValue.serverTimestamp()))
    }

    @Test fun removeProfileChangesOnlyDisabledAndUpdatedAt() = runTest {
        val document = mock(DocumentReference::class.java)
        `when`(users.document("jill")).thenReturn(document)
        `when`(document.update(anyMap())).thenReturn(Tasks.forResult(null))
        repository.removeProfile("jill")
        verify(document).update(mapOf("disabled" to true, "updatedAt" to FieldValue.serverTimestamp()))
    }

    @Test fun removeOwnerDisablesProfileAndActiveCampsitesInOneBatch() = runTest {
        val profile = mock(DocumentReference::class.java)
        `when`(users.document("jane")).thenReturn(profile)
        val query = mock(Query::class.java)
        `when`(campsites.whereEqualTo("ownerId", "jane")).thenReturn(query)
        val active = listing(true)
        val removed = listing(false)
        val snapshot = mock(QuerySnapshot::class.java)
        `when`(snapshot.documents).thenReturn(listOf(active, removed))
        `when`(query.get()).thenReturn(Tasks.forResult(snapshot))
        val batch = mock(WriteBatch::class.java)
        `when`(firestore.batch()).thenReturn(batch)
        `when`(batch.commit()).thenReturn(Tasks.forResult(null))

        assertEquals(1, repository.removeOwner("jane"))
        verify(batch).update(profile, mapOf("disabled" to true, "updatedAt" to FieldValue.serverTimestamp()))
        verify(batch).update(active.reference, mapOf("active" to false, "updatedAt" to FieldValue.serverTimestamp()))
        verify(batch, never()).update(removed.reference, mapOf("active" to false, "updatedAt" to FieldValue.serverTimestamp()))
        verify(batch).commit()
    }

    /** A campsite document with its own reference and the given `active` flag. */
    private fun listing(isActive: Boolean): DocumentSnapshot {
        val document = mock(DocumentSnapshot::class.java)
        `when`(document.getBoolean("active")).thenReturn(isActive)
        `when`(document.reference).thenReturn(mock(DocumentReference::class.java))
        return document
    }
}
