package com.ifafu.kyzz.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import com.ifafu.kyzz.data.model.LatLng
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MockLocationServiceTest {
    private lateinit var controller: ServiceController<MockLocationService>
    private lateinit var service: MockLocationService
    private lateinit var backend: FakeBackend
    private lateinit var ledger: FakeLedger
    private var now = 1_000_000_000L

    @Before fun setup() {
        backend = FakeBackend()
        ledger = FakeLedger()
        controller = Robolectric.buildService(MockLocationService::class.java)
        service = controller.get().apply {
            injectionBackend = backend
            cleanupLedger = ledger
            automaticUpdates = false
            monotonicClock = { now }
        }
        controller.create()
        service.stopMocking()
    }

    @After fun teardown() { controller.destroy() }

    private fun startSingle(lat: Double = 26.08, lng: Double = 119.23) {
        service.onStartCommand(MockLocationService.singleIntent(service, lat, lng), 0, 1)
    }

    private fun tick(seconds: Double) {
        now += (seconds * 1_000_000_000L).toLong()
        service.runLocationTick(now)
    }

    @Test fun bindingAloneNeverActivatesMockProviders() {
        service.onBind(Intent())
        assertTrue(backend.added.isEmpty())
        assertTrue(backend.samples.isEmpty())
        assertFalse(MockLocationService.isRunning)
    }

    @Test fun stopCommandCleansImmediatelyWhileServiceAndBinderRemainAliveAndCanRestart() {
        startSingle()
        val binder = service.onBind(Intent())
        assertTrue(MockLocationService.isRunning)
        service.onStartCommand(Intent().setAction(MockLocationService.ACTION_STOP), 0, 2)
        assertFalse(MockLocationService.isRunning)
        assertEquals(setOf("gps", "network"), backend.removed.toSet())
        val sampleCount = backend.samples.size
        tick(1.0)
        assertEquals(sampleCount, backend.samples.size)
        val notifications = shadowOf(service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        assertTrue(notifications.allNotifications.isEmpty())
        assertSame(service, (binder as MockLocationService.MockLocationBinder).getService())
        startSingle(25.0, 118.0)
        assertTrue(MockLocationService.isRunning)
        assertEquals(LatLng(25.0, 118.0), MockLocationService.state.value.position)
    }

    @Test fun failedStartupCanRetryOnSameInstanceAndNeverReportsRunning() {
        backend.addFailures.addAll(listOf("gps", "network"))
        startSingle()
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
        assertTrue(backend.samples.isEmpty())
        assertFalse(MockLocationService.isRunning)
        backend.addFailures.clear()
        startSingle()
        assertEquals(MockLocationStatus.RUNNING, MockLocationService.state.value.status)
        assertEquals(2, backend.samples.size)
    }

    @Test fun providerCreationWithoutAnyAcceptedSampleIsNotSuccessfulStartup() {
        backend.injectFailures.addAll(listOf("gps", "network"))
        startSingle()
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
        assertFalse(MockLocationService.isRunning)
        assertEquals(setOf("gps", "network"), backend.removed.toSet())
    }

    @Test fun partialAvailabilityReportsDegradedAndRecovers() {
        backend.addFailures.add("network")
        startSingle()
        assertEquals(MockLocationStatus.DEGRADED, MockLocationService.state.value.status)
        backend.addFailures.clear()
        repeat(5) { tick(1.0) }
        assertEquals(MockLocationStatus.RUNNING, MockLocationService.state.value.status)
    }

    @Test fun revokedMockAuthorizationImmediatelyStopsAndFutureTicksCannotInject() {
        startSingle()
        backend.denied.add("gps")
        tick(0.1)
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
        assertFalse(MockLocationService.isRunning)
        assertEquals(setOf("gps", "network"), backend.removed.toSet())
        val count = backend.samples.size
        tick(1.0)
        assertEquals(count, backend.samples.size)
    }

    @Test fun removedProviderIsRecreatedInsteadOfRemainingPermanentlyDegraded() {
        startSingle()
        backend.injectFailures.add("network")
        tick(0.1)
        assertEquals(MockLocationStatus.DEGRADED, MockLocationService.state.value.status)
        backend.injectFailures.clear()
        tick(1.0)
        assertEquals(MockLocationStatus.RUNNING, MockLocationService.state.value.status)
        assertEquals(2, backend.added.count { it == "network" })
    }

    @Test fun continuousFailureTimesOutButTransientFailureCanRecover() {
        startSingle()
        backend.injectFailures.addAll(listOf("gps", "network"))
        tick(0.1)
        assertEquals(MockLocationStatus.DEGRADED, MockLocationService.state.value.status)
        backend.injectFailures.clear()
        tick(1.0)
        assertEquals(MockLocationStatus.RUNNING, MockLocationService.state.value.status)
        backend.injectFailures.addAll(listOf("gps", "network"))
        repeat(3) { tick(1.0) }
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
    }

    @Test fun trajectoryUsesActualElapsedTimeAndDiscardsLongSuspension() {
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01))
        service.onStartCommand(MockLocationService.trajectoryIntent(service, route, 3.6, false, 0), 0, 1)
        tick(0.15)
        val firstSample = backend.samples.last()
        val beforeSleep = LatLng(firstSample.latitude, firstSample.longitude)
        assertEquals(0.54, MockLocationCoordinates.distance(route[0], beforeSleep), 0.0001)
        tick(10.0)
        assertEquals(beforeSleep, MockLocationService.state.value.position)
        assertEquals(0.0, MockLocationService.state.value.speedMetersPerSecond, 0.0)
        tick(1.0)
        assertEquals(4.14, MockLocationCoordinates.distance(route[0], MockLocationService.state.value.position!!), 0.0001)
    }

    @Test fun independentTrajectoryIntentsCannotMixPointsSpeedsOrLaps() {
        val routeA = mutableListOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01))
        val routeB = listOf(LatLng(1.0, 1.0), LatLng(1.0, 1.01))
        val first = MockLocationService.trajectoryIntent(service, routeA, 2.0, true, 3)
        val second = MockLocationService.trajectoryIntent(service, routeB, 4.0, true, 5)
        routeA.clear()
        service.onStartCommand(first, 0, 1)
        assertEquals(LatLng(0.0, 0.0), MockLocationService.state.value.position)
        assertEquals(2.0, MockLocationService.state.value.configuredSpeed, 0.0)
        assertEquals(3, MockLocationService.state.value.targetLaps)
        service.onStartCommand(second, 0, 2)
        assertEquals(routeB, MockLocationService.state.value.route)
        assertEquals(4.0, MockLocationService.state.value.configuredSpeed, 0.0)
        assertEquals(5, MockLocationService.state.value.targetLaps)
    }

    @Test fun manualMovementReportsSpeedDirectionAndStaysValidAtPole() {
        startSingle(90.0, 180.0)
        service.updateManualMovement(10.0, 90f)
        tick(1.0)
        val location = backend.samples.last()
        assertTrue(MockLocationService.isValidPosition(location.latitude, location.longitude))
        assertEquals(10f, location.speed, 0f)
        assertEquals(90f, location.bearing, 0f)
        service.updateManualMovement(0.0, 90f)
        tick(0.1)
        assertEquals(0f, backend.samples.last().speed, 0f)
    }

    @Test fun manualCommandsCannotOverwriteTrajectory() {
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01))
        service.onStartCommand(MockLocationService.trajectoryIntent(service, route, 2.0, false, 0), 0, 1)
        service.updateManualMovement(10.0, 180f)
        service.binder.setPosition(25.0, 118.0)
        tick(1.0)
        assertEquals(2f, backend.samples.last().speed, 0f)
        assertEquals(2.0, MockLocationCoordinates.distance(route[0], MockLocationService.state.value.position!!), 0.001)
    }

    @Test fun invalidAndZeroLengthRequestsFailBeforeCreatingProviders() {
        startSingle(Double.NaN, 0.0)
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
        assertTrue(backend.added.isEmpty())
        val point = LatLng(26.0, 119.0)
        service.onStartCommand(MockLocationService.trajectoryIntent(service, listOf(point, point), 1.2, false, 0), 0, 2)
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
        assertTrue(backend.added.isEmpty())
    }

    @Test fun startupReservationPreventsDuplicatesAndFailureReleasesReservation() {
        assertTrue(MockLocationService.prepareStart())
        assertFalse(MockLocationService.prepareStart())
        MockLocationService.reportStartFailure("启动被系统拒绝")
        assertTrue(MockLocationService.prepareStart())
    }

    @Test fun cleanupFailureIsReportedAndRetriedBeforeNextStart() {
        startSingle()
        backend.removeFailures.add("gps")
        service.stopMocking()
        assertFalse(MockLocationService.isRunning)
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
        assertTrue(MockLocationService.state.value.message!!.contains("清理失败"))
        assertTrue(MockLocationService.state.value.cleanupPending)
        assertFalse(MockLocationService.prepareStart())
        backend.removeFailures.clear()
        startSingle()
        assertEquals(MockLocationStatus.RUNNING, MockLocationService.state.value.status)
        assertEquals(2, backend.removed.count { it == "gps" })
    }

    @Test fun nullIntentStopsInsteadOfRestoringUnrequestedLocation() {
        startSingle()
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        assertFalse(MockLocationService.isRunning)
        assertEquals(setOf("gps", "network"), backend.removed.toSet())
    }

    @Test fun coldServiceRecoveryOnlyRemovesRecordedProvidersWithoutRegisteringOrInjecting() {
        ledger.pending.add("gps")
        service.onStartCommand(Intent().setAction(MockLocationService.ACTION_RECOVER), 0, 2)
        assertEquals(listOf("gps"), backend.removed)
        assertTrue(backend.added.isEmpty())
        assertTrue(backend.samples.isEmpty())
        assertFalse(MockLocationService.state.value.cleanupPending)
        assertEquals(MockLocationStatus.STOPPED, MockLocationService.state.value.status)
        val notifications = shadowOf(service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test fun deniedCleanupRetainsOwnershipAndCanBeRetriedOnAnotherServiceInstance() {
        startSingle()
        backend.deniedRemovals.addAll(listOf("gps", "network"))
        service.stopMocking()
        assertTrue(MockLocationService.state.value.cleanupPending)
        assertEquals(setOf("gps", "network"), ledger.pending)
        controller.destroy()
        backend = FakeBackend()
        controller = Robolectric.buildService(MockLocationService::class.java)
        service = controller.get().apply {
            injectionBackend = backend
            cleanupLedger = ledger
            automaticUpdates = false
            monotonicClock = { now }
        }
        controller.create()
        assertTrue(backend.removed.isEmpty())
        service.onStartCommand(Intent().setAction(MockLocationService.ACTION_RECOVER), 0, 2)
        assertEquals(setOf("gps", "network"), backend.removed.toSet())
        assertTrue(backend.added.isEmpty())
        assertTrue(ledger.pending.isEmpty())
        assertFalse(MockLocationService.state.value.cleanupPending)
    }

    @Test fun replacementServiceRecoversOwnershipEvenWhenOldProcessNeverCalledOnDestroy() {
        startSingle()
        val oldController = controller
        val recoveredBackend = FakeBackend()
        controller = Robolectric.buildService(MockLocationService::class.java)
        service = controller.get().apply {
            injectionBackend = recoveredBackend
            cleanupLedger = ledger
            automaticUpdates = false
            monotonicClock = { now }
        }
        controller.create()
        assertTrue(recoveredBackend.removed.isEmpty())
        service.onStartCommand(Intent().setAction(MockLocationService.ACTION_RECOVER), 0, 2)
        assertEquals(setOf("gps", "network"), recoveredBackend.removed.toSet())
        assertTrue(recoveredBackend.added.isEmpty())
        assertTrue(recoveredBackend.samples.isEmpty())
        assertFalse(MockLocationService.state.value.cleanupPending)
        oldController.destroy()
    }

    @Test fun previousCleanupFailureCannotBeOverwrittenByANewSession() {
        startSingle()
        backend.removeFailures.add("gps")
        service.stopMocking()
        val addCount = backend.added.size
        val sampleCount = backend.samples.size
        startSingle(10.0, 20.0)
        assertEquals(addCount, backend.added.size)
        assertEquals(sampleCount, backend.samples.size)
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
        assertTrue(MockLocationService.state.value.cleanupPending)
        assertFalse(MockLocationService.isRunning)
    }

    @Test fun registrationRequiresCommittedOwnershipBeforeAnyProviderMutation() {
        ledger.rejectRegistration = true
        startSingle()
        assertTrue(backend.added.isEmpty())
        assertTrue(backend.samples.isEmpty())
        assertEquals(MockLocationStatus.FAILED, MockLocationService.state.value.status)
    }

    @Test fun failedCleanupCommitKeepsPendingAndBlocksRegistrationUntilRecovery() {
        startSingle()
        ledger.rejectRemoval = true
        service.stopMocking()
        assertEquals(setOf("gps", "network"), ledger.pending)
        assertTrue(MockLocationService.state.value.cleanupPending)
        val count = backend.added.size
        startSingle()
        assertEquals(count, backend.added.size)
        ledger.rejectRemoval = false
        service.onStartCommand(Intent().setAction(MockLocationService.ACTION_RECOVER), 0, 3)
        assertFalse(MockLocationService.state.value.cleanupPending)
        assertTrue(ledger.pending.isEmpty())
    }

    @Test fun manualStartDoesNotBackdateMovementIntoTheStationaryInterval() {
        startSingle(0.0, 0.0)
        now += 1_400_000_000L
        service.updateManualMovement(10.0, 90f)
        tick(0.1)
        assertEquals(1.0, MockLocationCoordinates.distance(LatLng(0.0, 0.0), MockLocationService.state.value.position!!), 0.0001)
    }

    @Test fun manualReleaseSettlesTheMovingIntervalThenStaysStill() {
        startSingle(0.0, 0.0)
        service.updateManualMovement(10.0, 90f)
        now += 100_000_000L
        service.updateManualMovement(0.0, 90f)
        tick(1.0)
        assertEquals(1.0, MockLocationCoordinates.distance(LatLng(0.0, 0.0), MockLocationService.state.value.position!!), 0.0001)
        assertEquals(0f, backend.samples.last().speed, 0f)
    }

    @Test fun manualTurnSettlesTheOldDirectionBeforeApplyingTheNewDirection() {
        startSingle(0.0, 0.0)
        service.updateManualMovement(10.0, 90f)
        now += 100_000_000L
        service.updateManualMovement(10.0, 0f)
        tick(0.1)
        // StateFlow deliberately publishes at 4 Hz; this 200 ms check uses the accepted sample.
        val sample = backend.samples.last()
        val point = LatLng(sample.latitude, sample.longitude)
        assertEquals(1.0, MockLocationCoordinates.distance(LatLng(0.0, 0.0), LatLng(0.0, point.lng)), 0.0001)
        assertEquals(1.0, MockLocationCoordinates.distance(LatLng(0.0, point.lng), point), 0.0001)
        assertEquals(0f, backend.samples.last().bearing, 0f)
    }

    @Test fun manualCommandAfterLongSuspensionDiscardsTheOldInterval() {
        startSingle(0.0, 0.0)
        service.updateManualMovement(10.0, 90f)
        now += 10_000_000_000L
        service.updateManualMovement(10.0, 0f)
        tick(0.1)
        val point = MockLocationService.state.value.position!!
        assertEquals(0.0, point.lng, 0.000000001)
        assertEquals(1.0, MockLocationCoordinates.distance(LatLng(0.0, 0.0), point), 0.0001)
    }

    private class FakeLedger : MockLocationCleanupLedger {
        val pending = linkedSetOf<String>()
        var rejectRegistration = false
        var rejectRemoval = false
        override fun pendingProviders(): Set<String> = pending.toSet()
        override fun markPending(providers: Set<String>): Boolean {
            if (rejectRegistration) return false
            pending.addAll(providers)
            return true
        }
        override fun markRemoved(provider: String): Boolean {
            if (rejectRemoval) return false
            pending.remove(provider)
            return true
        }
    }

    private class FakeBackend : MockLocationBackend {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val samples = mutableListOf<Location>()
        val addFailures = mutableSetOf<String>()
        val injectFailures = mutableSetOf<String>()
        val denied = mutableSetOf<String>()
        val removeFailures = mutableSetOf<String>()
        val deniedRemovals = mutableSetOf<String>()
        override fun add(provider: String) {
            if (provider in addFailures) throw IllegalStateException("provider unavailable")
            added.add(provider)
        }
        override fun inject(provider: String, location: Location) {
            if (provider in denied) throw SecurityException("authorization revoked")
            if (provider in injectFailures) throw IllegalStateException("injection unavailable")
            samples.add(Location(location))
        }
        override fun remove(provider: String) {
            removed.add(provider)
            if (provider in deniedRemovals) throw SecurityException("authorization revoked")
            if (provider in removeFailures) throw IllegalStateException("cleanup unavailable")
        }
    }
}
