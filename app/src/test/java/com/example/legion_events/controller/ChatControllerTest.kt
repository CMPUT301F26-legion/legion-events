/*
 * ChatControllerTest.kt
 *
 * Purpose: Verify private chat drafts, live replies, lifecycle cleanup, and stable-ID send retries.
 * Role: Local JVM controller tests using in-memory conversations and message streams.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.*
import com.example.legion_events.model.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Exercises explicit sends without a Firestore connection or real Android lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = FakeMessages()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun openingAndRecreationNeverSendAndLiveRepliesAppearOnlyWhileResumed() = runTest(dispatcher) {
        val controller = controller(); controller.resume(); runCurrent()
        assertEquals(1, repository.starts); assertEquals(1, repository.listeners)
        controller.setDraft("Hello")
        assertSame(controller, controller())
        repository.messages.value = listOf(ChatMessage("reply", "thread", "owner", "alice", "Welcome", false, Timestamp(1, 0), null))
        runCurrent()
        assertEquals("Welcome", controller.state.value.messages.single().text)
        controller.pause(); runCurrent()
        assertEquals(0, repository.listeners)
        controller.resume(); runCurrent()
        assertEquals(1, repository.listeners)
        assertEquals(0, repository.sends)
    }

    @Test fun uncertainSendLocksItsBodyAndRestorationRetriesTheSameIdExplicitly() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val controller = controller(saved); runCurrent()
        controller.setDraft(" Hello "); repository.failSend = true
        controller.send(); controller.send(); runCurrent()
        assertEquals(1, repository.sends)
        assertNotNull(controller.state.value.sendError)
        controller.setDraft("Different")
        assertEquals(" Hello ", controller.state.value.draft)
        val restoredState = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        store.clear(); runCurrent()
        val restored = controller(restoredState); runCurrent()
        assertEquals(1, repository.sends)
        assertNotNull(restored.state.value.sendError)
        repository.failSend = false
        restored.send(); runCurrent()
        assertEquals(2, repository.sends)
        assertEquals(1, repository.ids.distinct().size)
        assertEquals("", restored.state.value.draft)
        assertNull(restored.state.value.sendError)
    }

    @Test fun failedAccessAndListenerReadsOfferRetryWithoutSending() = runTest(dispatcher) {
        repository.available = false
        val controller = controller(); runCurrent()
        assertNotNull(controller.state.value.error)
        controller.setDraft("Hi"); controller.send(); runCurrent()
        assertEquals(0, repository.sends)
        repository.available = true
        controller.retry(); controller.resume(); runCurrent()
        assertNotNull(controller.state.value.thread)
        controller.setDraft("x".repeat(MAX_CHAT_LENGTH + 1))
        assertEquals("Hi", controller.state.value.draft)
    }

    @Test fun conversationListSubscriptionsAreReplacedAndRemovedOnPause() = runTest(dispatcher) {
        val controller = MessagesController(repository)
        controller.resume(); runCurrent()
        assertEquals(1, repository.listListeners)
        controller.resume(); runCurrent()
        assertEquals(1, repository.listListeners)
        assertEquals("thread", controller.state.value.conversations.single().conversation.id)
        controller.pause(); runCurrent()
        assertEquals(0, repository.listListeners)
    }

    /** Creates the controller in a real navigation-like ViewModelStore with a restorable draft. */
    private fun controller(saved: SavedStateHandle = SavedStateHandle()): ChatController = ViewModelProvider.create(store, viewModelFactory {
        initializer { ChatController(repository, saved, campsiteId = "pine") }
    })[ChatController::class]

    /** Counts subscriptions and explicit sends, with a simulated uncertain commit result. */
    private class FakeMessages : MessageRepository {
        val summary = ConversationSummary(Conversation("thread", "pine", "alice", "owner", "", null, null), "Pine Ridge", "alice")
        val messages = MutableStateFlow(emptyList<ChatMessage>())
        var starts = 0; var sends = 0; var listeners = 0; var listListeners = 0
        var available = true; var failSend = false
        val ids = mutableListOf<String>()
        override suspend fun startConversation(campsiteId: String): String { starts++; return "thread" }
        override suspend fun getConversation(conversationId: String) = summary.takeIf { available }
        override fun observeMessages(conversationId: String) = messages.onStart { listeners++ }.onCompletion { listeners-- }
        override fun observeConversations() = flow {
            listListeners++
            try { emit(listOf(summary)); awaitCancellation() } finally { listListeners-- }
        }
        override suspend fun send(conversationId: String, messageId: String, text: String) {
            sends++; ids += messageId
            if (failSend) error("Response lost")
        }
    }
}
