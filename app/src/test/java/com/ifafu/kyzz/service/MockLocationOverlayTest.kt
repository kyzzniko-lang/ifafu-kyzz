package com.ifafu.kyzz.service

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MockLocationOverlayTest {
    private lateinit var context: Context
    private lateinit var overlay: MockLocationOverlay

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        overlay = MockLocationOverlay(context)
    }

    @After
    fun tearDown() {
        overlay.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun missingPermissionDoesNotCreateWindowAndCanBeRetried() {
        ShadowSettings.setCanDrawOverlays(false)
        overlay.show(26.0, 119.0)
        assertNull(root())
        overlay.dismiss()
        overlay.dismiss()
        ShadowSettings.setCanDrawOverlays(true)
        overlay.show(26.0, 119.0)
        assertNotNull(root())
    }

    @Test
    fun collapsedWindowKeepsRestoreAndStopTouchable() {
        var stops = 0
        overlay.setStopListener { stops++ }
        overlay.show(26.0, 119.0)
        text("收起").performClick()
        assertTrue(isVisibleThroughParents(text("展开")))
        assertTrue(isVisibleThroughParents(text("停止")))
        val params = field("layoutParams") as WindowManager.LayoutParams
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        text("展开").performClick()
        assertTrue(isVisibleThroughParents(text("↑")))
        text("收起").performClick()
        text("停止").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun directionsSendBearingAndReleaseStopsWithoutCalculatingPosition() {
        val commands = mutableListOf<Pair<Double, Float>>()
        overlay.setMovementListener { speed, bearing -> commands += speed to bearing }
        overlay.show(90.0, 180.0)
        touch(text("↑"), MotionEvent.ACTION_DOWN)
        touch(text("↑"), MotionEvent.ACTION_UP)
        touch(text("→"), MotionEvent.ACTION_DOWN)
        touch(text("→"), MotionEvent.ACTION_CANCEL)
        assertEquals(listOf(1.2 to 0f, 0.0 to 0f, 1.2 to 90f, 0.0 to 90f), commands)
        assertEquals("90.00000, 180.00000", text("90.00000, 180.00000").text.toString())
    }

    @Test
    fun switchingToTrajectoryCancelsHeldControlWithoutLateZeroCommand() {
        val commands = mutableListOf<Pair<Double, Float>>()
        overlay.setMovementListener { speed, bearing -> commands += speed to bearing }
        overlay.show(26.0, 119.0)
        val north = text("↑")
        touch(north, MotionEvent.ACTION_DOWN)
        overlay.setTrajectoryMode(true)
        touch(north, MotionEvent.ACTION_CANCEL)
        assertEquals(listOf(1.2 to 0f), commands)
        assertFalse(isVisibleThroughParents(north))
        assertTrue(isVisibleThroughParents(text("轨迹按设定自动运行")))
    }

    @Test
    fun trajectoryCompletionKeepsManualControlsUnavailable() {
        overlay.show(26.0, 119.0)
        overlay.setTrajectoryMode(true)
        overlay.updateTrajectoryProgress(null, 0.0, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(isVisibleThroughParents(text("↑")))
        assertTrue(isVisibleThroughParents(text("轨迹已完成，点击停止恢复真实定位")))
    }

    @Test
    fun dismissStopsAHeldDirectionBeforeClearingMovementCallback() {
        val commands = mutableListOf<Pair<Double, Float>>()
        overlay.setMovementListener { speed, bearing -> commands += speed to bearing }
        overlay.show(26.0, 119.0)
        touch(text("↑"), MotionEvent.ACTION_DOWN)
        overlay.dismiss()
        assertEquals(listOf(1.2 to 0f, 0.0 to 0f), commands)
    }

    @Test
    fun dismissDropsQueuedUiUpdatesAndCallbacksAndAllowsNewWindow() {
        var oldStops = 0
        val oldCommands = mutableListOf<Pair<Double, Float>>()
        overlay.setMovementListener { speed, bearing -> oldCommands += speed to bearing }
        overlay.setStopListener { oldStops++ }
        overlay.show(26.0, 119.0)
        overlay.updateCoords(10.0, 20.0)
        overlay.updateTrajectoryProgress("50%", 3.0, true)
        val oldNorth = text("↑")
        overlay.dismiss()
        overlay.show(30.0, 120.0)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(text("30.00000, 120.00000"))
        touch(oldNorth, MotionEvent.ACTION_CANCEL)
        text("停止定位").performClick()
        assertEquals(0, oldStops)
        assertTrue(oldCommands.isEmpty())
        var newStops = 0
        overlay.setStopListener { newStops++ }
        text("停止定位").performClick()
        assertEquals(1, newStops)
    }

    private fun touch(view: View, action: Int) {
        val event = MotionEvent.obtain(0, 1, action, 10f, 10f, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun root(): View? = field("rootView") as? View

    private fun field(name: String): Any? = MockLocationOverlay::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(overlay)

    private fun text(label: String): TextView = descendants(root()!!)
        .filterIsInstance<TextView>().first { it.text.toString() == label }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun isVisibleThroughParents(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }
}
