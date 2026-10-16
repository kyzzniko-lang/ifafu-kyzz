package com.ifafu.kyzz.ui.toolbox

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import com.ifafu.kyzz.data.model.LatLng
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MockLocationDraftTest {
    @Test fun parcelRoundTripKeepsExactCoordinatesAndUnfinishedInputs() {
        val draft = MockLocationDraft(listOf(LatLng(0.0, 119.123456789123), LatLng(-90.0, -180.0)),
            "-", "119.", "3.", "", trajectory = true, loop = true)
        val saved = Bundle()
        draft.write(saved)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            val restored = parcel.readBundle(MockLocationDraft::class.java.classLoader)!!
            assertEquals(draft, MockLocationDraft.read(restored))
        } finally { parcel.recycle() }
    }

    @Test fun missingOrTruncatedRouteStateIsHandledWithoutCrashing() {
        assertEquals(MockLocationDraft(), MockLocationDraft.read(Bundle()))
        val truncated = Bundle().apply { putDoubleArray("mock_draft_route", doubleArrayOf(26.0, 119.0, 27.0)) }
        assertEquals(listOf(LatLng(26.0, 119.0)), MockLocationDraft.read(truncated).route)
    }
}
