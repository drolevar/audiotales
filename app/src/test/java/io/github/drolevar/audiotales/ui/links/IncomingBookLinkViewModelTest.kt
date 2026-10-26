package io.github.drolevar.audiotales.ui.links

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.github.drolevar.audiotales.data.online.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingBookLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val source = FakeSource()
    private val url = "https://provider.example/api?link=provider.example&id=42"
    private val target = IncomingBookTarget(null, "source", "42")
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    private fun model(handle: SavedStateHandle = SavedStateHandle()) = IncomingBookLinkViewModel(source, handle).also { vm ->
        stores += ViewModelStore().also { it.put("links", vm) }
        vm.restore()
    }
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun IncomingBookLinkViewModel.confirm() = confirmRegistration((state.value as IncomingLinkState.ConfirmRegistration).deliveryId)

    @Test fun unknownSourceWaitsForExplicitConsentAndCancelDoesNotRegister() = runTest(dispatcher) {
        source.result = IncomingBookResolution.NeedsRegistration("https://provider.example/")
        val vm = model(); vm.accept(url); runCurrent()
        assertEquals("provider.example", (vm.state.value as IncomingLinkState.ConfirmRegistration).host)
        assertEquals(0, source.registrations)
        vm.dismiss(); runCurrent()
        assertEquals(IncomingLinkState.Idle, vm.state.value)
        assertEquals(0, source.registrations)
    }

    @Test fun confirmationRegistersThenResolvesAndConsumedDeliveryDoesNotReplayButNewDeliveryWorks() = runTest(dispatcher) {
        source.result = IncomingBookResolution.NeedsRegistration("https://provider.example/")
        val handle = SavedStateHandle()
        val vm = model(handle); vm.accept(url); runCurrent()
        source.result = IncomingBookResolution.Ready(target)
        vm.confirm(); runCurrent()
        val ready = vm.state.value as IncomingLinkState.Ready
        assertEquals(target, ready.target)
        assertEquals(1, source.registrations)
        vm.consumed("stale"); assertEquals(ready, vm.state.value)
        vm.consumed(ready.deliveryId)
        assertEquals(IncomingLinkState.Idle, model(snapshot(handle)).state.value)
        vm.accept(url); runCurrent()
        assertNotEquals(ready.deliveryId, (vm.state.value as IncomingLinkState.Ready).deliveryId)
        assertEquals(1, source.registrations)
    }

    @Test fun repeatedPendingDeliveryDoesNotDuplicateWorkAndReadySurvivesProcessStateRestoration() = runTest(dispatcher) {
        source.result = IncomingBookResolution.Ready(target.copy(bookId = "historical"))
        val handle = SavedStateHandle()
        val vm = model(handle); vm.accept(url); vm.accept(url); runCurrent()
        assertEquals(1, source.calls)
        val ready = vm.state.value
        assertEquals(ready, model(snapshot(handle)).state.value)
        assertEquals(1, source.calls)
    }

    @Test fun restoredConfirmationDoesNotRepeatAnEarlierRegistration() = runTest(dispatcher) {
        source.result = IncomingBookResolution.NeedsRegistration("https://provider.example/")
        val handle = SavedStateHandle()
        model(handle).accept(url); runCurrent()
        val restored = model(snapshot(handle)); runCurrent()
        assertTrue(restored.state.value is IncomingLinkState.ConfirmRegistration)
        assertEquals(0, source.registrations)
    }

    @Test fun malformedInputNeverCallsSourceAndHasNoRetry() = runTest(dispatcher) {
        val vm = model(); vm.accept("intent://evil"); runCurrent()
        assertEquals(IncomingLinkState.Error(OnlineBookError.INVALID_LINK, false), vm.state.value)
        assertEquals(0, source.calls)
    }

    @Test fun networkFailureCanRetryWithoutRegisteringOrSaving() = runTest(dispatcher) {
        source.resolve = { throw OnlineBookException(OnlineBookError.NETWORK) }
        val vm = model(); vm.accept(url); runCurrent()
        assertEquals(IncomingLinkState.Error(OnlineBookError.NETWORK, true), vm.state.value)
        source.resolve = { IncomingBookResolution.Ready(target) }
        vm.retry(); runCurrent()
        assertEquals(target, (vm.state.value as IncomingLinkState.Ready).target)
        assertEquals(0, source.registrations)
    }

    @Test fun restoredErrorWaitsForRetryAndDoesNotAutomaticallyCallProvider() = runTest(dispatcher) {
        source.resolve = { throw OnlineBookException(OnlineBookError.NETWORK) }
        val handle = SavedStateHandle()
        val vm = model(handle); vm.accept(url); runCurrent()
        val restored = model(snapshot(handle)); runCurrent()
        assertEquals(vm.state.value, restored.state.value)
        assertEquals(1, source.calls)
    }

    @Test fun staleConfirmationCannotRegisterANewerDelivery() = runTest(dispatcher) {
        val vm = model(); vm.accept(url); runCurrent()
        val old = vm.state.value as IncomingLinkState.ConfirmRegistration
        vm.accept(url.replace("provider.example", "other.example")); runCurrent()
        vm.confirmRegistration(old.deliveryId); runCurrent()
        assertEquals(0, source.registrations)
        assertTrue(vm.state.value is IncomingLinkState.ConfirmRegistration)
    }

    @Test fun cancelledOrSupersededUncooperativeResponseCannotPublishLateNavigationOrConfirmation() = runTest(dispatcher) {
        val late = CompletableDeferred<IncomingBookResolution>()
        source.resolve = { withContext(NonCancellable) { late.await() } }
        val vm = model(); vm.accept(url); runCurrent()
        vm.dismiss()
        source.resolve = { IncomingBookResolution.Ready(target.copy(remoteId = "43")) }
        vm.accept(url.replace("id=42", "id=43")); runCurrent()
        val current = vm.state.value
        late.complete(IncomingBookResolution.NeedsRegistration("https://old.example/")); runCurrent()
        assertEquals(current, vm.state.value)
        assertEquals("43", (current as IncomingLinkState.Ready).target.remoteId)
        assertEquals(0, source.registrations)
    }

    @Test fun cancelDuringRegistrationCannotResolveOrPublishAfterLateCompletion() = runTest(dispatcher) {
        source.result = IncomingBookResolution.NeedsRegistration("https://provider.example/")
        val late = CompletableDeferred<Unit>()
        source.registration = { withContext(NonCancellable) { late.await() } }
        val vm = model(); vm.accept(url); runCurrent()
        vm.confirm(); runCurrent()
        vm.dismiss()
        late.complete(Unit); runCurrent()
        assertEquals(IncomingLinkState.Idle, vm.state.value)
        assertEquals(1, source.calls)
    }

    private class FakeSource : IncomingBookLinkSource {
        var calls = 0
        var registrations = 0
        var result: IncomingBookResolution = IncomingBookResolution.NeedsRegistration("https://provider.example/")
        var resolve: suspend (IncomingBookLink) -> IncomingBookResolution = { result }
        var registration: suspend () -> Unit = {}
        override suspend fun resolve(request: IncomingBookLink): IncomingBookResolution { calls++; return resolve.invoke(request) }
        override suspend fun register(request: IncomingBookLink) { registrations++; registration() }
    }
}
