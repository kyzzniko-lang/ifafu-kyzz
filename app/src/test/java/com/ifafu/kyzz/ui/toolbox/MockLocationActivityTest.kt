package com.ifafu.kyzz.ui.toolbox

import android.app.Application
import android.app.AppOpsManager
import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.ifafu.kyzz.R
import com.ifafu.kyzz.MainApplication
import com.ifafu.kyzz.data.model.LatLng
import com.ifafu.kyzz.service.MockLocationBackend
import com.ifafu.kyzz.service.MockLocationCoordinates
import com.ifafu.kyzz.service.MockLocationService
import com.ifafu.kyzz.service.MockLocationStatus
import com.ifafu.kyzz.service.PreferencesMockLocationCleanupLedger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MockLocationActivityTest {
    private lateinit var serviceController: ServiceController<MockLocationService>
    private lateinit var service: MockLocationService
    private val activities = mutableListOf<ActivityController<MockLocationActivity>>()
    private var originalProcessWarmed = false

    @Before fun setup() {
        originalProcessWarmed = MainApplication.processWarmed
        serviceController = Robolectric.buildService(MockLocationService::class.java)
        service = serviceController.get().apply {
            injectionBackend = object : MockLocationBackend {
                override fun add(provider: String) = Unit
                override fun inject(provider: String, location: Location) = Unit
                override fun remove(provider: String) = Unit
            }
            automaticUpdates = false
        }
        serviceController.create()
        service.stopMocking()
    }

    @After fun teardown() {
        activities.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        serviceController.destroy()
        MainApplication.processWarmed = originalProcessWarmed
        idle()
    }

    private fun createActivity(saved: Bundle? = null): ActivityController<MockLocationActivity> {
        val controller = Robolectric.buildActivity(MockLocationActivity::class.java)
        controller.get().setTheme(R.style.Theme_iFAFUKYZZ)
        controller.create(saved).start()
        if (saved != null) controller.restoreInstanceState(saved)
        controller.resume().visible()
        activities.add(controller)
        idle()
        return controller
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun status(activity: MockLocationActivity) = activity.findViewById<TextView>(R.id.tvStatus).text.toString()
    private fun button(activity: MockLocationActivity) = activity.findViewById<MaterialButton>(R.id.btnStartStop)
    private fun latitude(activity: MockLocationActivity) = activity.findViewById<TextInputEditText>(R.id.etLatitude)
    private fun setAwaitingStart(activity: MockLocationActivity) {
        MockLocationActivity::class.java.getDeclaredField("awaitingStart").apply { isAccessible = true }.setBoolean(activity, true)
    }

    @Test fun startingNeverClaimsSuccessAndFailureUnlocksControlsWithoutAnotherEmission() {
        val activity = createActivity().get()
        setAwaitingStart(activity)
        assertTrue(MockLocationService.prepareStart())
        idle()
        assertTrue(status(activity).contains("正在启动"))
        assertFalse(button(activity).isEnabled)
        assertFalse(latitude(activity).isEnabled)
        MockLocationService.reportStartFailure("未选择模拟位置应用")
        idle()
        assertTrue(status(activity).contains("未选择模拟位置应用"))
        assertTrue(button(activity).isEnabled)
        assertTrue(latitude(activity).isEnabled)
        assertEquals("开始模拟定位", button(activity).text.toString())
    }

    @Test fun successfulStartupIsObservedEvenWhenStartingEmissionWasConflated() {
        val activity = createActivity().get()
        setAwaitingStart(activity)
        MockLocationService.prepareStart()
        service.onStartCommand(MockLocationService.singleIntent(service, 26.0835123456, 119.2389123456), 0, 1)
        idle()
        assertEquals(MockLocationStatus.RUNNING, MockLocationService.state.value.status)
        assertEquals("运行中", status(activity))
        assertEquals("停止模拟定位", button(activity).text.toString())
        assertTrue(button(activity).isEnabled)
        assertFalse(latitude(activity).isEnabled)
        assertEquals(26.0835123456, latitude(activity).text.toString().toDouble(), 0.0)
    }

    @Test fun backgroundPageReplaysFailureWhenItBecomesVisibleAgain() {
        service.onStartCommand(MockLocationService.singleIntent(service, 26.0, 119.0), 0, 1)
        val controller = createActivity()
        val activity = controller.get()
        controller.pause().stop()
        idle()
        service.stopMocking()
        MockLocationService.prepareStart()
        MockLocationService.reportStartFailure("模拟位置授权被撤回")
        idle()
        controller.start().resume().visible()
        idle()
        assertTrue(status(activity).contains("授权被撤回"))
        assertTrue(button(activity).isEnabled)
        assertTrue(latitude(activity).isEnabled)
    }

    @Test fun rotationPreservesDraftRoutePrecisionModeSpeedAndLaps() {
        val original = createActivity()
        val first = original.get()
        first.findViewById<SwitchCompat>(R.id.switchTrajectoryMode).isChecked = true
        first.findViewById<SwitchCompat>(R.id.switchLoopMode).isChecked = true
        first.findViewById<TextInputEditText>(R.id.etSpeed).setText("3.75")
        first.findViewById<TextInputEditText>(R.id.etLapCount).setText("8")
        val points = listOf(LatLng(26.0835123456, 119.2389123456), LatLng(26.0845123456, 119.2399123456))
        points.forEach { point ->
            val gcj = MockLocationCoordinates.wgsToGcj(point)
            first.MapBridge().onMapClick(gcj.lat, gcj.lng)
        }
        idle()
        val state = Bundle()
        original.saveInstanceState(state).pause().stop().destroy()
        val second = createActivity(state).get()
        val restored = Bundle()
        second.javaClass.getDeclaredMethod("onSaveInstanceState", Bundle::class.java).apply { isAccessible = true }.invoke(second, restored)
        val draft = MockLocationDraft.read(restored)
        assertTrue(draft.trajectory)
        assertTrue(draft.loop)
        assertEquals("3.75", draft.speed)
        assertEquals("8", draft.laps)
        assertEquals(2, draft.route.size)
        points.zip(draft.route).forEach { (expected, actual) ->
            assertTrue(MockLocationCoordinates.distance(expected, actual) < 0.01)
        }
    }

    @Test fun newPageRestoresActiveServiceAndRejectsEveryBridgeEdit() {
        val route = listOf(LatLng(26.0835123456, 119.2389123456), LatLng(26.0845123456, 119.2399123456))
        service.onStartCommand(MockLocationService.trajectoryIntent(service, route, 2.5, true, 4), 0, 1)
        val controller = createActivity()
        val activity = controller.get()
        activity.MapBridge().onMapClick(0.0, 0.0)
        activity.MapBridge().onWaypointRemovedAt(0)
        idle()
        val state = Bundle()
        controller.saveInstanceState(state)
        val draft = MockLocationDraft.read(state)
        assertEquals(route, draft.route)
        assertTrue(draft.trajectory)
        assertTrue(draft.loop)
        assertEquals("2.5", draft.speed)
        assertEquals("4", draft.laps)
        assertFalse(activity.findViewById<SwitchCompat>(R.id.switchTrajectoryMode).isEnabled)
        assertFalse(activity.findViewById<MaterialButton>(R.id.btnClearWaypoints).isEnabled)
        assertEquals(route, MockLocationService.state.value.route)
    }

    @Test fun persistedDoubleCoordinatesIncludingZeroRemainExactOnReopen() {
        val first = createActivity().get()
        first.getSharedPreferences("ifafu_user", Application.MODE_PRIVATE).edit()
            .putString("mock_location_lat_double", "0.0")
            .putString("mock_location_lng_double", "119.2389123456789").apply()
        val second = createActivity().get()
        assertEquals("0.0", latitude(second).text.toString())
        assertEquals("119.2389123456789", second.findViewById<TextInputEditText>(R.id.etLongitude).text.toString())
    }

    @Test fun coldRestoreKeepsDraftButDiscardsPermissionRequestWhoseCallbackWasLost() {
        val saved = Bundle()
        val expected = MockLocationDraft(latitude = "26.123456789", longitude = "119.987654321", speed = "3.5")
        expected.write(saved)
        saved.putBoolean("mock_external_pending", true)
        saved.putBoolean("mock_start_requested", true)
        saved.putString("mock_auto_package", "obsolete.target")
        MainApplication.processWarmed = false
        val activity = createActivity(saved).get()
        assertTrue(button(activity).isEnabled)
        assertTrue(latitude(activity).isEnabled)
        assertEquals(expected.latitude, latitude(activity).text.toString())
        assertEquals("未启动", status(activity))
        val restored = Bundle()
        activity.javaClass.getDeclaredMethod("onSaveInstanceState", Bundle::class.java).apply { isAccessible = true }.invoke(activity, restored)
        assertFalse(restored.getBoolean("mock_start_requested"))
        assertFalse(restored.getBoolean("mock_external_pending"))
        assertNull(restored.getString("mock_auto_package"))
    }

    @Test fun tileFailureOffersReloadWhileDraftAndLocalControlsRemainUsable() {
        val controller = createActivity()
        val activity = controller.get()
        val bridge = activity.MapBridge()
        bridge.onMapReady()
        bridge.onMapTilesFailed()
        idle()
        val reload = activity.findViewById<MaterialButton>(R.id.btnReloadMap)
        assertEquals(View.VISIBLE, reload.visibility)
        bridge.onMapClick(10.0, 540.1)
        idle()
        assertEquals(10.0, latitude(activity).text.toString().toDouble(), 0.0)
        assertEquals(-179.9, activity.findViewById<TextInputEditText>(R.id.etLongitude).text.toString().toDouble(), 1e-10)
        val map = shadowOf(activity.findViewById<WebView>(R.id.webView))
        assertTrue(map.lastEvaluatedJavascript.contains("setPosition"))
        bridge.onMapTilesReady()
        idle()
        assertEquals(View.GONE, reload.visibility)
        bridge.onMapTilesFailed()
        reload.performClick()
        bridge.onMapClick(20.0, 20.0)
        bridge.onMapTilesFailed()
        idle()
        assertEquals(10.0, latitude(activity).text.toString().toDouble(), 0.0)
        val saved = Bundle()
        controller.saveInstanceState(saved)
        assertEquals("10.0", MockLocationDraft.read(saved).latitude)
    }

    @Test fun antimeridianServiceRouteIsRenderedInAdjacentWorldCopies() {
        val route = listOf(LatLng(10.0, 179.9), LatLng(10.0, -179.9))
        service.onStartCommand(MockLocationService.trajectoryIntent(service, route, 2.5, true, 4), 0, 1)
        val activity = createActivity().get()
        activity.MapBridge().onMapReady()
        idle()
        val script = shadowOf(activity.findViewById<WebView>(R.id.webView)).lastEvaluatedJavascript
        assertTrue(script.startsWith("renderState(true,true,true,"))
        assertTrue(script.contains("[10.0,179.9]"))
        assertTrue(script.contains("[10.0,180.1]"))
        assertFalse(script.contains("[10.0,-179.9]"))
        assertEquals(route, MockLocationService.state.value.route)
    }

    @Test fun singleSessionAndRotationKeepTheIndependentTrajectoryDraftAndParameters() {
        val controller = createActivity()
        val first = controller.get()
        val trajectory = first.findViewById<SwitchCompat>(R.id.switchTrajectoryMode)
        trajectory.isChecked = true
        first.findViewById<SwitchCompat>(R.id.switchLoopMode).isChecked = true
        first.findViewById<TextInputEditText>(R.id.etSpeed).setText("3.75")
        first.findViewById<TextInputEditText>(R.id.etLapCount).setText("8")
        first.MapBridge().onMapClick(10.0, 10.0)
        first.MapBridge().onMapClick(10.001, 10.001)
        idle()
        @Suppress("UNCHECKED_CAST")
        val expected = (MockLocationActivity::class.java.getDeclaredField("waypoints")
            .apply { isAccessible = true }.get(first) as List<LatLng>).toList()
        assertEquals(Lifecycle.State.RESUMED, first.lifecycle.currentState)
        trajectory.isChecked = false
        service.onStartCommand(MockLocationService.singleIntent(service, 20.0, 20.0), 0, 1)
        idle()
        assertFalse(trajectory.isEnabled) // The RUNNING flow has reached the Activity.
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop().destroy()
        val secondController = createActivity(saved)
        val second = secondController.get()
        service.stopMocking()
        idle()
        second.findViewById<SwitchCompat>(R.id.switchTrajectoryMode).isChecked = true
        val restored = Bundle()
        secondController.saveInstanceState(restored)
        val draft = MockLocationDraft.read(restored)
        assertEquals(expected, draft.route)
        assertEquals("3.75", draft.speed)
        assertEquals("8", draft.laps)
        assertTrue(draft.loop)
    }

    @Test fun externallyStartedTrajectoryDoesNotOverwriteThisPagesEditableDraft() {
        val controller = createActivity()
        val activity = controller.get()
        activity.findViewById<SwitchCompat>(R.id.switchTrajectoryMode).isChecked = true
        activity.MapBridge().onMapClick(10.0, 10.0)
        activity.MapBridge().onMapClick(10.001, 10.001)
        activity.findViewById<TextInputEditText>(R.id.etSpeed).setText("4.5")
        idle()
        val active = listOf(LatLng(20.0, 20.0), LatLng(20.01, 20.01))
        service.onStartCommand(MockLocationService.trajectoryIntent(service, active, 8.0, true, 2), 0, 1)
        idle()
        assertEquals("8.0", activity.findViewById<TextInputEditText>(R.id.etSpeed).text.toString())
        service.stopMocking()
        idle()
        val saved = Bundle()
        controller.saveInstanceState(saved)
        val draft = MockLocationDraft.read(saved)
        assertEquals(listOf(LatLng(10.0, 10.0), LatLng(10.001, 10.001)), draft.route)
        assertEquals("4.5", draft.speed)
        assertFalse(draft.loop)
    }

    @Test fun externalTrajectoryRestoresAnEmptySingleModeDraftWithItsCustomParameters() {
        val controller = createActivity()
        val activity = controller.get()
        activity.findViewById<TextInputEditText>(R.id.etSpeed).setText("4.5")
        activity.findViewById<TextInputEditText>(R.id.etLapCount).setText("7")
        activity.findViewById<SwitchCompat>(R.id.switchLoopMode).isChecked = true
        assertFalse(activity.findViewById<SwitchCompat>(R.id.switchTrajectoryMode).isChecked)
        val active = listOf(LatLng(20.0, 20.0), LatLng(20.01, 20.01))
        service.onStartCommand(MockLocationService.trajectoryIntent(service, active, 8.0, false, 0), 0, 1)
        idle()
        assertTrue(activity.findViewById<SwitchCompat>(R.id.switchTrajectoryMode).isChecked)
        assertEquals("8.0", activity.findViewById<TextInputEditText>(R.id.etSpeed).text.toString())
        val duringSession = Bundle()
        controller.saveInstanceState(duringSession).pause().stop().destroy()
        assertFalse(MockLocationDraft.read(duringSession).trajectory)
        val restoredController = createActivity(duringSession)
        val restoredActivity = restoredController.get()
        service.stopMocking()
        idle()
        assertFalse(restoredActivity.findViewById<SwitchCompat>(R.id.switchTrajectoryMode).isChecked)
        val saved = Bundle()
        restoredController.saveInstanceState(saved)
        val draft = MockLocationDraft.read(saved)
        assertTrue(draft.route.isEmpty())
        assertFalse(draft.trajectory)
        assertTrue(draft.loop)
        assertEquals("4.5", draft.speed)
        assertEquals("7", draft.laps)
    }

    @Test fun rotatingAnInPagePermissionDialogCancelsTheRequestAndUnlocksControls() {
        val first = createActivity()
        latitude(first.get()).setText("26.0")
        first.get().findViewById<TextInputEditText>(R.id.etLongitude).setText("119.0")
        MockLocationActivity::class.java.getDeclaredField("startRequested").apply { isAccessible = true }.setBoolean(first.get(), true)
        // Dialogs have no ActivityResult callback to restore; the recreated page explicitly cancels.
        MockLocationActivity::class.java.getDeclaredField("externalRequestPending").apply { isAccessible = true }.setBoolean(first.get(), false)
        val saved = Bundle()
        first.saveInstanceState(saved).pause().stop().destroy()
        MainApplication.processWarmed = true
        val second = createActivity(saved).get()
        assertTrue(button(second).isEnabled)
        assertTrue(latitude(second).isEnabled)
        assertEquals("开始模拟定位", button(second).text.toString())
        assertFalse(MockLocationService.state.value.isBusy)
    }

    @Test fun cleanupOnlyCanBeRequestedWithoutLocationPermissionOrSystemLocationAndDoesNotInject() {
        val activity = createActivity().get()
        val appOps = activity.getSystemService(Application.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), activity.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOf(activity.application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = activity.getSystemService(Application.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        var injections = 0
        val removed = mutableListOf<String>()
        service.injectionBackend = object : MockLocationBackend {
            override fun add(provider: String) { fail("Recovery must not register a mock provider") }
            override fun inject(provider: String, location: Location) { injections++; fail("Recovery must not inject a mock position") }
            override fun remove(provider: String) { removed.add(provider) }
        }
        val recover = activity.findViewById<MaterialButton>(R.id.btnRecoverRealLocation)
        assertTrue(recover.isEnabled)
        recover.performClick()
        idle()
        assertEquals(MockLocationStatus.RECOVERING, MockLocationService.state.value.status)
        assertFalse(button(activity).isEnabled)
        val intent = shadowOf(activity.application).nextStartedService
        assertNotNull(intent)
        service.onStartCommand(intent, 0, 1)
        idle()
        assertEquals(MockLocationStatus.STOPPED, MockLocationService.state.value.status)
        assertFalse(MockLocationService.state.value.cleanupPending)
        assertEquals(0, injections)
        assertTrue(removed.containsAll(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)))
        assertTrue(button(activity).isEnabled)
    }

    @Test fun progressUpdatesReuseSampledGeometryAndUpdateOnlyThePositionMarker() {
        var now = 10_000_000_000L
        service.monotonicClock = { now }
        val route = listOf(LatLng(30.0, -100.0), LatLng(55.0, 30.0))
        service.onStartCommand(MockLocationService.trajectoryIntent(service, route, 3.0, false, 0), 0, 1)
        val activity = createActivity().get()
        activity.MapBridge().onMapReady()
        idle()
        val field = MockLocationActivity::class.java.getDeclaredField("mapGeometry").apply { isAccessible = true }
        val geometry = field.get(activity)
        assertNotNull(geometry)
        now += 500_000_000L
        service.runLocationTick(now)
        idle()
        assertSame(geometry, field.get(activity))
        assertTrue(shadowOf(activity.findViewById<WebView>(R.id.webView)).lastEvaluatedJavascript.startsWith("setPosition("))
    }

    @Test fun coldPageShowsDurableCleanupObligationAndOffersRecoveryWithoutStartingInjection() {
        service.getSharedPreferences("ifafu_user", Application.MODE_PRIVATE).edit()
            .putStringSet(PreferencesMockLocationCleanupLedger.KEY_PENDING, setOf(LocationManager.GPS_PROVIDER))
            .putInt(PreferencesMockLocationCleanupLedger.KEY_SCHEMA, 1).commit()
        MainApplication.processWarmed = false
        val activity = createActivity().get()
        assertTrue(MockLocationService.state.value.cleanupPending)
        assertFalse(MockLocationService.state.value.isBusy)
        assertEquals("恢复真实定位", button(activity).text.toString())
        assertTrue(button(activity).isEnabled)
        assertTrue(status(activity).contains("恢复真实定位"))
        assertNull(shadowOf(activity.application).nextStartedService)
        assertFalse(MockLocationService.prepareStart())
    }

    @Test fun systemBackClosesControlPageAndLeavesTheIndependentMockServiceRunning() {
        service.onStartCommand(MockLocationService.singleIntent(service, 26.0, 119.0), 0, 1)
        val activity = createActivity().get()
        assertTrue(MockLocationService.state.value.isRunning)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertTrue(MockLocationService.state.value.isRunning)
        assertEquals(LatLng(26.0, 119.0), MockLocationService.state.value.position)
    }
}
