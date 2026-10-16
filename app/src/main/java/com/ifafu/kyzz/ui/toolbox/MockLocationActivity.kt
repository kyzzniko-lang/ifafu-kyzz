package com.ifafu.kyzz.ui.toolbox

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ifafu.kyzz.R
import com.ifafu.kyzz.MainApplication
import com.ifafu.kyzz.data.model.LatLng
import com.ifafu.kyzz.databinding.ActivityMockLocationBinding
import com.ifafu.kyzz.service.MockLocationCoordinates
import com.ifafu.kyzz.service.MockLocationAuthorization
import com.ifafu.kyzz.service.MockLocationEngine
import com.ifafu.kyzz.service.MockLocationService
import com.ifafu.kyzz.service.MockLocationSnapshot
import com.ifafu.kyzz.service.MockLocationStatus
import com.ifafu.kyzz.ui.base.BaseActivity
import kotlinx.coroutines.launch
import java.util.Locale

class MockLocationActivity : BaseActivity<ActivityMockLocationBinding>() {
    override fun createBinding() = ActivityMockLocationBinding.inflate(layoutInflater)

    // Native state owns all WGS-84 coordinates. JavaScript renders GCJ-02 without mutating routes.
    private val waypoints = mutableListOf<LatLng>()
    private var isTrajectoryMode = false
    private var isLoopMode = false
    private var renderingControls = false
    private lateinit var mapView: WebView
    private var mapReady = false
    private var mapDestroyed = false
    private var snapshot = MockLocationService.state.value
    private var startRequested = false
    private var awaitingStart = false
    private var stopRequested = false
    private var recoveryRequested = false
    private var recoveryForced = false
    // Editable trajectory fields survive temporary single-position and external service sessions.
    private var trajectoryDraft: MockLocationDraft? = null
    private var adoptActiveTrajectoryDraft = false
    private var resumeStartAfterSettings = false
    private var externalRequestPending = false
    private var notificationPromptHandled = false
    private var overlayPromptHandled = false
    private var pendingAutoPackage: String? = null
    private var autoStartPending = false
    private var autoLaunchReady = false
    private var autoRecoveryNoticeShown = false
    private var mapGeneration = 0
    private var mapTilesFailed = false
    private var renderedRoute: List<LatLng>? = null
    private var renderedMode: Boolean? = null
    private var renderedLoop: Boolean? = null
    private var renderedLock: Boolean? = null
    private var geometryRoute: List<LatLng>? = null
    private var geometryLoop = false
    private var mapGeometry: MockLocationMapGeometry? = null
    private val editingLocked: Boolean
        get() = MockLocationService.state.value.isBusy || startRequested || recoveryRequested || awaitingStart || stopRequested

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        externalRequestPending = false
        if (!granted) toast("通知未开启，可继续定位；请在本页停止定位")
        continueStartRequest()
    }
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        externalRequestPending = false
        continueStartRequest()
    }
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        externalRequestPending = false
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            continueStartRequest()
        } else {
            cancelStartRequest()
            toast("需要定位权限才能运行定位前台服务")
        }
    }

    private data class CampusInfo(val name: String, val position: LatLng)
    private val campuses = listOf(
        CampusInfo("金山校区", LatLng(26.085206, 119.239669)),
        CampusInfo("旗山校区", LatLng(26.056579, 119.180505)),
        CampusInfo("安溪校区", LatLng(25.079766, 118.234897)),
        CampusInfo("南平校区", LatLng(26.555664, 118.119161))
    )
    private val campusButtons = listOf(R.id.btnCampusJinshan, R.id.btnCampusQishan, R.id.btnCampusAnxi, R.id.btnCampusNanping)

    override fun onCreate(savedInstanceState: Bundle?) {
        // BaseActivity intentionally drops framework restore state after process death. Old
        // ActivityResult callbacks then cannot be recovered, so keep only the editable draft.
        val restorePendingRequests = MainApplication.processWarmed
        super.onCreate(savedInstanceState)
        MockLocationService.refreshCleanupState(this)
        snapshot = MockLocationService.state.value
        // Only a genuinely new page with an already active trajectory has no local draft to protect.
        adoptActiveTrajectoryDraft = savedInstanceState == null && snapshot.isRunning &&
            snapshot.mode == MockLocationService.MODE_TRAJECTORY
        mapView = binding.webView
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState != null) {
            applyDraft(MockLocationDraft.read(savedInstanceState))
            notificationPromptHandled = savedInstanceState.getBoolean("mock_notification_prompt")
            overlayPromptHandled = savedInstanceState.getBoolean("mock_overlay_prompt")
            awaitingStart = restorePendingRequests && savedInstanceState.getBoolean("mock_awaiting_start") && snapshot.isBusy
            externalRequestPending = restorePendingRequests && savedInstanceState.getBoolean("mock_external_pending")
            startRequested = savedInstanceState.getBoolean("mock_start_requested") && externalRequestPending && !snapshot.isBusy
            recoveryRequested = savedInstanceState.getBoolean("mock_recovery_requested") && externalRequestPending && !snapshot.isBusy
            recoveryForced = recoveryRequested && savedInstanceState.getBoolean("mock_recovery_forced")
            resumeStartAfterSettings = savedInstanceState.getBoolean("mock_resume_settings") && (startRequested || recoveryRequested)
            autoStartPending = restorePendingRequests && savedInstanceState.getBoolean("mock_auto_pending")
            autoLaunchReady = restorePendingRequests && savedInstanceState.getBoolean("mock_auto_launch") && snapshot.isRunning
            pendingAutoPackage = savedInstanceState.getString("mock_auto_package")
                ?.takeIf { awaitingStart || startRequested || autoStartPending || autoLaunchReady }
        } else {
            restoreCoordinates()
            readAutoStartIntent(intent)
        }
        setupTrajectoryControls()
        setupButtons()
        setupCampusButtons()
        trajectoryDraft = currentDraft()
        renderServiceState(snapshot)
        setupMap(mapView)
        if (savedInstanceState != null &&
            (savedInstanceState.getBoolean("mock_start_requested") || savedInstanceState.getBoolean("mock_recovery_requested")) &&
            !startRequested && !recoveryRequested && !snapshot.isBusy) {
            toast("页面已重建，未完成的启动或恢复流程已取消，请重新操作")
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MockLocationService.state.collect { renderServiceState(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (readAutoStartIntent(intent)) { renderMap(center = true); consumeAutoStart() }
    }

    override fun onResume() {
        super.onResume()
        MockLocationService.refreshCleanupState(this)
        renderServiceState(MockLocationService.state.value)
        updateBatteryWarning()
        consumeAutoStart()
        launchPendingTarget()
        if (resumeStartAfterSettings) {
            resumeStartAfterSettings = false
            externalRequestPending = false
            continueStartRequest()
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Framework widget restoration follows onCreate; replay the authoritative state afterward.
        if (MockLocationService.state.value.isBusy) renderServiceState(MockLocationService.state.value)
        else applyDraft(MockLocationDraft.read(savedInstanceState))
        updateControls()
        renderMap(center = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val draft = currentDraft()
        (if (snapshot.isBusy) trajectoryDraft?.copy(latitude = draft.latitude, longitude = draft.longitude)
            ?: draft else draft).write(outState)
        outState.putBoolean("mock_notification_prompt", notificationPromptHandled)
        outState.putBoolean("mock_overlay_prompt", overlayPromptHandled)
        outState.putBoolean("mock_awaiting_start", awaitingStart)
        outState.putBoolean("mock_external_pending", externalRequestPending)
        outState.putBoolean("mock_start_requested", startRequested)
        outState.putBoolean("mock_recovery_requested", recoveryRequested)
        outState.putBoolean("mock_recovery_forced", recoveryForced)
        outState.putBoolean("mock_resume_settings", resumeStartAfterSettings)
        outState.putString("mock_auto_package", pendingAutoPackage)
        outState.putBoolean("mock_auto_pending", autoStartPending)
        outState.putBoolean("mock_auto_launch", autoLaunchReady)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapDestroyed = true
        mapReady = false
        mapView.stopLoading()
        mapView.removeJavascriptInterface("AndroidBridge")
        (mapView.parent as? android.view.ViewGroup)?.removeView(mapView)
        mapView.destroy()
        super.onDestroy()
    }

    private fun applyDraft(draft: MockLocationDraft) {
        renderingControls = true
        waypoints.clear()
        waypoints.addAll(draft.route.filter { MockLocationCoordinates.isValidPosition(it) }.take(MockLocationEngine.MAX_WAYPOINTS))
        isTrajectoryMode = draft.trajectory
        isLoopMode = draft.loop
        binding.etLatitude.setText(draft.latitude)
        binding.etLongitude.setText(draft.longitude)
        binding.etSpeed.setText(draft.speed)
        binding.etLapCount.setText(draft.laps)
        binding.switchTrajectoryMode.isChecked = isTrajectoryMode
        binding.switchLoopMode.isChecked = isLoopMode
        renderingControls = false
    }

    private fun currentDraft() = MockLocationDraft(waypoints.toList(), binding.etLatitude.text.toString(),
        binding.etLongitude.text.toString(), binding.etSpeed.text.toString(), binding.etLapCount.text.toString(),
        isTrajectoryMode, isLoopMode)

    private fun restoreTrajectoryDraft() {
        val draft = trajectoryDraft ?: return
        renderingControls = true
        waypoints.clear()
        waypoints.addAll(draft.route)
        isTrajectoryMode = draft.trajectory
        isLoopMode = draft.loop
        binding.etSpeed.setText(draft.speed)
        binding.etLapCount.setText(draft.laps)
        renderingControls = false
    }

    private fun restoreCoordinates() {
        val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        val lat = prefs.getString("mock_location_lat_double", null)?.toDoubleOrNull()
            ?: if (prefs.contains("mock_location_lat")) prefs.getFloat("mock_location_lat", 0f).toDouble() else null
        val lng = prefs.getString("mock_location_lng_double", null)?.toDoubleOrNull()
            ?: if (prefs.contains("mock_location_lng")) prefs.getFloat("mock_location_lng", 0f).toDouble() else null
        if (lat != null && lng != null && MockLocationCoordinates.isValidPosition(lat, lng)) setCoordinates(LatLng(lat, lng))
    }

    private fun persistCoordinates(position: LatLng) {
        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putString("mock_location_lat_double", position.lat.toString())
            .putString("mock_location_lng_double", position.lng.toString()).apply()
    }

    private fun readAutoStartIntent(source: Intent?): Boolean {
        if (source == null || !source.hasExtra("auto_lat") || !source.hasExtra("auto_lng")) return false
        val lat = (source.extras?.get("auto_lat") as? Number)?.toDouble()
        val lng = (source.extras?.get("auto_lng") as? Number)?.toDouble()
        source.removeExtra("auto_lat")
        source.removeExtra("auto_lng")
        if (lat == null || lng == null || !MockLocationCoordinates.isValidPosition(lat, lng)) {
            toast("自动定位参数无效"); return false
        }
        if (editingLocked) { toast("定位正在运行，请先停止后再设置新位置"); return false }
        isTrajectoryMode = false
        binding.switchTrajectoryMode.isChecked = false
        setCoordinates(LatLng(lat, lng))
        pendingAutoPackage = source.getStringExtra("auto_pkg")?.takeIf { it.isNotBlank() }
        autoStartPending = true
        return true
    }

    private fun consumeAutoStart() {
        if (!autoStartPending || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (MockLocationService.state.value.cleanupPending) {
            if (!autoRecoveryNoticeShown) {
                autoRecoveryNoticeShown = true
                toast("请先恢复真实定位，再启动新的模拟定位")
            }
            return
        }
        autoStartPending = false
        overlayPromptHandled = true
        notificationPromptHandled = true
        startMockLocation()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMap(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = true // Packaged Leaflet assets; main-frame navigation is restricted.
        view.settings.allowContentAccess = false
        view.webChromeClient = WebChromeClient()
        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                return handleMapNavigation(request.url)
            }
            @Deprecated("Required for Android 7 WebView implementations")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = url?.let { handleMapNavigation(Uri.parse(it)) } ?: true
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view !== mapView || mapDestroyed) return
                view.evaluateJavascript("if(typeof renderState==='function'){AndroidBridge.onMapReady();}", null)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && view === mapView) showMapRecovery()
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (view === mapView && !mapDestroyed) {
                    mapReady = false
                    view?.removeJavascriptInterface("AndroidBridge")
                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                    view?.destroy()
                    mapView = WebView(this@MockLocationActivity)
                    binding.cardMap.addView(mapView, android.view.ViewGroup.LayoutParams(-1, -1))
                    showMapRecovery()
                    setupMap(mapView)
                }
                return true
            }
        }
        loadPickerMap()
    }

    private fun handleMapNavigation(uri: Uri): Boolean {
        if (uri.toString() == "about:blank" || uri.toString() == "file:///android_asset/leaflet/") return false
        if (uri.scheme == "https" || uri.scheme == "http") {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            catch (_: Exception) { toast("没有可打开此链接的浏览器") }
        }
        return true
    }

    private fun showMapRecovery() { mapReady = false; binding.btnReloadMap.visibility = View.VISIBLE }

    private fun loadPickerMap() {
        mapReady = false
        renderedRoute = null
        mapGeneration++
        mapTilesFailed = false
        mapView.removeJavascriptInterface("AndroidBridge")
        mapView.addJavascriptInterface(MapBridge(mapGeneration), "AndroidBridge")
        val initial = selectedPosition()?.let { MockLocationCoordinates.wgsToGcj(it) } ?: campuses.first().position
        val html = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="file:///android_asset/leaflet/leaflet.min.css">
<script src="file:///android_asset/leaflet/leaflet.min.js"></script>
<style>*{margin:0;padding:0}html,body,#map{width:100%;height:100%}
.pick-marker{width:20px;height:20px;background:#C75B39;border:3px solid #fff;border-radius:50%;box-shadow:0 2px 8px rgba(0,0,0,.4)}
.wp-marker{width:12px;height:12px;background:#C75B39;border:2px solid #fff;border-radius:50%}</style>
</head><body><div id="map"></div><script>
var map=L.map('map').setView([${initial.lat},${initial.lng}],16);
var satellite=L.tileLayer('https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',{subdomains:['1','2','3','4'],maxZoom:18});
var road=L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',{subdomains:['1','2','3','4'],maxZoom:18});
function watchTiles(layer){
 var failed=false;
 layer.on('loading',function(){failed=false;});
 layer.on('tileerror',function(){failed=true;if(map.hasLayer(layer)){AndroidBridge.onMapTilesFailed();}});
 layer.on('load',function(){if(!failed&&map.hasLayer(layer)){AndroidBridge.onMapTilesReady();}});
}
watchTiles(road);watchTiles(satellite);road.addTo(map);
L.control.layers({'街道图':road,'卫星图':satellite},null,{collapsed:false}).addTo(map);
var locked=false,routeLayers=[],pickMarker=null;
function nearestPoint(p){return [p[0],p[1]+Math.round((map.getCenter().lng-p[1])/360)*360];}
function setPosition(p){
 if(!p){if(pickMarker){map.removeLayer(pickMarker);pickMarker=null;}return;}
 p=nearestPoint(p);
 if(pickMarker){pickMarker.setLatLng(p);}else{pickMarker=L.marker(p,{interactive:false,icon:L.divIcon({className:'pick-marker',iconSize:[20,20],iconAnchor:[10,10]})}).addTo(map);}
}
function renderState(traj,loop,busy,points,pick,center,path,closing){
 locked=busy;routeLayers.forEach(function(layer){map.removeLayer(layer);});routeLayers=[];
 var shift=points.length?Math.round((map.getCenter().lng-points[0][1])/360)*360:0;
 points=points.map(function(p){return[p[0],p[1]+shift];});
 path=path.map(function(p){return[p[0],p[1]+shift];});
 closing=closing.map(function(p){return[p[0],p[1]+shift];});
 if(traj){
  points.forEach(function(p,index){
   var m=L.marker(p,{icon:L.divIcon({className:'wp-marker',iconSize:[14,14],iconAnchor:[7,7]})}).addTo(map);
   m.on('click',function(e){L.DomEvent.stopPropagation(e);if(!locked){AndroidBridge.onWaypointRemovedAt(index);}});routeLayers.push(m);
  });
  if(path.length>=2){routeLayers.push(L.polyline(path,{color:'#C75B39',weight:3,opacity:.8}).addTo(map));}
  if(loop&&closing.length>=2){routeLayers.push(L.polyline(closing,{color:'#C75B39',weight:2,opacity:.5,dashArray:'6,8'}).addTo(map));}
 }
 if(center){if(traj&&path.length>=2){map.fitBounds(L.latLngBounds(path.concat(closing)),{padding:[20,20],maxZoom:17});}else if(pick){map.setView(nearestPoint(pick),16);}}
 setPosition(pick);
}
map.on('click',function(e){if(!locked){AndroidBridge.onMapClick(e.latlng.lat,e.latlng.lng);}});
AndroidBridge.onMapReady();
</script></body></html>""".trimIndent()
        mapView.loadDataWithBaseURL("file:///android_asset/leaflet/", html, "text/html", "UTF-8", null)
    }

    private fun renderMap(center: Boolean = false) {
        if (!mapReady || mapDestroyed) return
        val lock = editingLocked
        val position = if (snapshot.isRunning) snapshot.position ?: selectedPosition() else selectedPosition()
        val pick = position?.let { pointJson(MockLocationMapProjection.projectPoint(it)) } ?: "null"
        val needsRoute = center || renderedRoute != waypoints || renderedMode != isTrajectoryMode || renderedLoop != isLoopMode || renderedLock != lock
        val script = if (needsRoute) {
            if (geometryRoute != waypoints || geometryLoop != isLoopMode || mapGeometry == null) {
                mapGeometry = MockLocationMapProjection.geometry(waypoints, isLoopMode)
                geometryRoute = waypoints.toList()
                geometryLoop = isLoopMode
            }
            val geometry = requireNotNull(mapGeometry)
            "renderState($isTrajectoryMode,$isLoopMode,$lock,${pointsJson(geometry.waypoints)},$pick,$center," +
                "${pointsJson(geometry.path)},${pointsJson(geometry.closingPath)});"
        } else "setPosition($pick);"
        mapView.evaluateJavascript(script, null)
        renderedRoute = waypoints.toList()
        renderedMode = isTrajectoryMode
        renderedLoop = isLoopMode
        renderedLock = lock
    }

    private fun pointJson(point: MockLocationMapPoint) = "[${point.lat},${point.lng}]"
    private fun pointsJson(points: List<MockLocationMapPoint>) = points.joinToString(prefix = "[", postfix = "]") { pointJson(it) }

    private fun setupTrajectoryControls() {
        binding.switchTrajectoryMode.setOnCheckedChangeListener { _, checked ->
            if (renderingControls) return@setOnCheckedChangeListener
            if (editingLocked) { updateControls(); return@setOnCheckedChangeListener }
            isTrajectoryMode = checked; updateControls(); renderMap()
        }
        binding.switchLoopMode.setOnCheckedChangeListener { _, checked ->
            if (renderingControls) return@setOnCheckedChangeListener
            if (editingLocked) { updateControls(); return@setOnCheckedChangeListener }
            isLoopMode = checked; updateControls(); renderMap()
        }
        binding.btnUndoWaypoint.setOnClickListener {
            if (!editingLocked && waypoints.isNotEmpty()) { waypoints.removeAt(waypoints.lastIndex); updateControls(); renderMap() }
        }
        binding.btnClearWaypoints.setOnClickListener {
            if (!editingLocked) { waypoints.clear(); updateControls(); renderMap() }
        }
        binding.etSpeed.doAfterTextChanged { if (!renderingControls) updateTrajectoryUI() }
        binding.etLapCount.doAfterTextChanged { if (!renderingControls) updateTrajectoryUI() }
        binding.etLatitude.doAfterTextChanged { if (!renderingControls && !editingLocked) renderMap() }
        binding.etLongitude.doAfterTextChanged { if (!renderingControls && !editingLocked) renderMap() }
    }

    private fun setupCampusButtons() {
        campusButtons.forEachIndexed { index, id ->
            findViewById<MaterialButton>(id).setOnClickListener {
                if (editingLocked) return@setOnClickListener
                val campus = campuses[index]
                if (isTrajectoryMode) waypoints.clear()
                setCoordinates(MockLocationCoordinates.gcjToWgs(campus.position))
                binding.tvPickedCoords.text = "已选择: ${campus.name}（WGS-84）"
                updateControls(); renderMap(center = true)
            }
        }
    }

    private fun updateTrajectoryUI() {
        val count = waypoints.size
        binding.btnUndoWaypoint.isEnabled = !editingLocked && count > 0
        binding.btnClearWaypoints.isEnabled = !editingLocked && count > 0
        val distance = routeDistance()
        if (count < 2 || !distance.isFinite() || distance <= 0) {
            binding.tvWaypointInfo.text = if (editingLocked) "路径点: $count 个（运行中不可编辑）"
                else if (count == 1) "已添加 1 个点，请再添加一个以形成路径" else "点击地图添加路径点（至少2个）"
            binding.tvTrajectoryStats.visibility = View.GONE
            return
        }
        val speed = binding.etSpeed.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        val duration = speed?.let { formatDuration(distance / it) } ?: "请填写有效速度"
        val prefix = if (isLoopMode) "单圈" else "总距离"
        binding.tvTrajectoryStats.visibility = View.VISIBLE
        binding.tvTrajectoryStats.text = "$prefix: ${String.format(Locale.US, "%.2f", distance / 1000)} 公里 | 预计: $duration"
        binding.tvWaypointInfo.text = if (editingLocked) "路径点: $count 个（运行中不可编辑）" else "路径点: $count 个（点击地图继续添加）"
    }

    private fun routeDistance(): Double = waypoints.zipWithNext().sumOf { (a, b) -> MockLocationCoordinates.distance(a, b) } +
        if (isLoopMode && waypoints.size >= 2) MockLocationCoordinates.distance(waypoints.last(), waypoints.first()) else 0.0

    private fun formatDuration(seconds: Double): String {
        if (!seconds.isFinite() || seconds > Int.MAX_VALUE) return "超过可显示时长"
        val total = seconds.toLong().coerceAtLeast(0)
        return if (total >= 60) "${total / 60}分${total % 60}秒" else "${total}秒"
    }

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            when {
                MockLocationService.state.value.status == MockLocationStatus.RECOVERING -> Unit
                MockLocationService.state.value.isBusy -> stopMockLocation()
                MockLocationService.state.value.cleanupPending -> recoverRealLocation()
                else -> startMockLocation()
            }
        }
        binding.btnReloadMap.setOnClickListener { loadPickerMap() }
        binding.btnRecoverRealLocation.setOnClickListener { recoverRealLocation(force = true) }
        binding.btnBatteryOpt.setOnClickListener { openBatterySettings() }
        binding.btnAutoStart.setOnClickListener {
            val target = Intent().apply {
                component = when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
                    "xiaomi" -> ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    "huawei" -> ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                    "oppo" -> ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                    "vivo" -> ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                    else -> null
                }
                if (component == null) { action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS; data = Uri.parse("package:$packageName") }
            }
            try { startActivity(target) }
            catch (_: Exception) { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        binding.btnNotificationSettings.setOnClickListener {
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun startMockLocation() {
        if (editingLocked) return
        if (!validateDraft()) { pendingAutoPackage = null; return }
        startRequested = true
        trajectoryDraft = currentDraft()
        updateControls()
        continueStartRequest()
    }

    private fun validateDraft(): Boolean {
        if (!isTrajectoryMode) {
            if (selectedPosition() == null) { toast("请输入有效 WGS-84 坐标：纬度 -90~90，经度 -180~180"); return false }
            return true
        }
        if (waypoints.size !in 2..MockLocationEngine.MAX_WAYPOINTS ||
            waypoints.any { !MockLocationCoordinates.isValidPosition(it) } || routeDistance() < MockLocationEngine.MIN_DISTANCE_METERS) {
            toast("轨迹需要至少2个有效且不重合的路径点，总长度至少${MockLocationEngine.MIN_DISTANCE_METERS}米"); return false
        }
        val speed = binding.etSpeed.text.toString().toDoubleOrNull()
        if (speed == null || !speed.isFinite() || speed <= 0 || speed > MockLocationEngine.MAX_SPEED_METERS_PER_SECOND) {
            toast("请输入0~100 m/s之间的有效速度（大于0）"); return false
        }
        if (isLoopMode) {
            val laps = binding.etLapCount.text.toString().toIntOrNull()
            if (laps == null || laps < 0 || laps > 999) { toast("圈数范围0~999，0表示无限循环"); return false }
        }
        return true
    }

    private fun continueStartRequest() {
        if (recoveryRequested) { continueRecoveryRequest(); return }
        if (!startRequested || isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { resumeStartAfterSettings = true; return }
        if (MockLocationService.state.value.isBusy) { cancelStartRequest(); return }
        if (MockLocationService.state.value.cleanupPending) {
            cancelStartRequest()
            toast("请先恢复真实定位，再启动新的模拟定位")
            return
        }
        if (!validateDraft()) { cancelStartRequest(); return }
        if (!hasLocationPermission()) {
            externalRequestPending = true
            try { locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            catch (_: Exception) { cancelStartRequest(); toast("无法申请定位权限，请在系统设置中授权后重试") }
            return
        }
        if (!isLocationEnabled()) {
            showRequiredSetting("请开启系统定位", "系统定位关闭时，目标应用可能无法接收模拟位置。", Settings.ACTION_LOCATION_SOURCE_SETTINGS); return
        }
        if (!hasMockLocationAppOp()) {
            showRequiredSetting("请选择模拟位置应用", "在开发者选项的「选择模拟位置信息应用」中选择本应用，然后返回重试。", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); return
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPromptHandled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPromptHandled = true
            externalRequestPending = true
            try { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            catch (_: Exception) { externalRequestPending = false; continueStartRequest() }
            return
        }
        if (!overlayPromptHandled && !Settings.canDrawOverlays(this)) {
            overlayPromptHandled = true
            MaterialAlertDialogBuilder(this).setTitle("可选：开启悬浮控制器")
                .setMessage("悬浮窗便于移动和停止定位，也可以直接在本页启动、停止。")
                .setPositiveButton("去开启") { _, _ ->
                    externalRequestPending = true
                    try { overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { externalRequestPending = false; continueStartRequest() }
                }.setNegativeButton("直接开始") { _, _ -> continueStartRequest() }
                .setOnCancelListener { cancelStartRequest() }.show()
            return
        }
        val first = if (isTrajectoryMode) waypoints.first() else selectedPosition() ?: run { cancelStartRequest(); return }
        val serviceIntent = if (isTrajectoryMode) MockLocationService.trajectoryIntent(this, waypoints.toList(),
            binding.etSpeed.text.toString().toDouble(), isLoopMode, if (isLoopMode) binding.etLapCount.text.toString().toInt() else 0)
        else MockLocationService.singleIntent(this, first.lat, first.lng)
        if (!MockLocationService.prepareStart()) { cancelStartRequest(); return }
        startRequested = false
        awaitingStart = true
        persistCoordinates(first)
        renderServiceState(MockLocationService.state.value)
        try { ContextCompat.startForegroundService(this, serviceIntent) }
        catch (error: Exception) {
            MockLocationService.reportStartFailure("启动定位服务失败：${error.message ?: error.javaClass.simpleName}")
            renderServiceState(MockLocationService.state.value)
        }
    }

    private fun recoverRealLocation(force: Boolean = false) {
        if (editingLocked) return
        MockLocationService.refreshCleanupState(this)
        if (!MockLocationService.state.value.cleanupPending && !force) { renderServiceState(MockLocationService.state.value); return }
        recoveryRequested = true
        recoveryForced = force
        updateControls()
        continueRecoveryRequest()
    }

    private fun continueRecoveryRequest() {
        if (!recoveryRequested || isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { resumeStartAfterSettings = true; return }
        val state = MockLocationService.state.value
        if (state.isBusy || (!state.cleanupPending && !recoveryForced)) { cancelStartRequest(); return }
        if (!hasMockLocationAppOp()) {
            showRequiredSetting("恢复真实定位需要模拟位置授权",
                "请在开发者选项中重新选择本应用。返回后将只清理残留模拟定位，不会启动位置注入。",
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            return
        }
        recoveryRequested = false
        resumeStartAfterSettings = false
        externalRequestPending = false
        val force = recoveryForced
        recoveryForced = false
        if (!MockLocationService.recoverRealLocation(this, force)) toast("无法提交恢复请求，请保持本页在前台后重试")
        renderServiceState(MockLocationService.state.value)
    }

    private fun showRequiredSetting(title: String, message: String, action: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                resumeStartAfterSettings = true
                externalRequestPending = true
                try { startActivity(Intent(action)) }
                catch (_: Exception) { cancelStartRequest(); toast("无法打开系统设置，请手动设置后重试") }
            }.setNegativeButton("取消") { _, _ -> cancelStartRequest() }
            .setOnCancelListener { cancelStartRequest() }.show()
    }

    private fun cancelStartRequest() {
        val cancellingRecovery = recoveryRequested
        startRequested = false
        recoveryRequested = false
        recoveryForced = false
        resumeStartAfterSettings = false
        externalRequestPending = false
        if (!cancellingRecovery) {
            pendingAutoPackage = null
            autoLaunchReady = false
        }
        updateControls(); renderMap()
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { true } // Service reports authoritative provider failures.
    }

    private fun hasMockLocationAppOp(): Boolean = try {
        MockLocationAuthorization.isAllowed(this)
    } catch (_: Exception) { false }

    private fun stopMockLocation() {
        if (stopRequested) return
        stopRequested = true
        pendingAutoPackage = null
        awaitingStart = false
        autoLaunchReady = false
        updateControls()
        binding.tvStatus.text = "正在停止..."
        try { MockLocationService.stopService(this) }
        catch (error: Exception) {
            stopRequested = false
            renderServiceState(MockLocationService.state.value)
            toast("停止请求失败，请重试：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun renderServiceState(value: MockLocationSnapshot) {
        if (isDestroyed) return
        val wasBusy = snapshot.isBusy
        val startSucceeded = awaitingStart && value.isRunning
        val startFailed = awaitingStart && !value.isBusy
        // Clear local startup guards before rendering; a terminal failure may be the last flow emission.
        if (startSucceeded || startFailed) awaitingStart = false
        if (!wasBusy && value.isBusy) trajectoryDraft = currentDraft()
        snapshot = value
        if (value.isRunning) {
            renderingControls = true
            // STARTING/RECOVERING never replace editable fields with an unacknowledged request.
            isTrajectoryMode = value.mode == MockLocationService.MODE_TRAJECTORY
            if (isTrajectoryMode) {
                isLoopMode = value.loop
                if (waypoints != value.route) { waypoints.clear(); waypoints.addAll(value.route) }
                val speedText = value.configuredSpeed.toString()
                if (binding.etSpeed.text.toString() != speedText) binding.etSpeed.setText(speedText)
                val lapsText = value.targetLaps.toString()
                if (binding.etLapCount.text.toString() != lapsText) binding.etLapCount.setText(lapsText)
                // An empty editable route can still contain intentional mode and parameter choices.
                if (adoptActiveTrajectoryDraft) {
                    trajectoryDraft = currentDraft()
                    adoptActiveTrajectoryDraft = false
                }
            }
            value.position?.let { setCoordinates(it) }
            renderingControls = false
        }
        if (wasBusy && !value.isBusy) restoreTrajectoryDraft()
        if (!value.isBusy) stopRequested = false
        updateControls()
        binding.tvStatus.text = if (stopRequested) "正在停止..." else when (value.status) {
            MockLocationStatus.STOPPED -> when {
                value.cleanupPending -> "检测到残留模拟定位，请恢复真实定位"
                value.trajectoryCompleted -> "轨迹已完成，定位已停止"
                else -> "未启动"
            }
            MockLocationStatus.STARTING -> "正在启动，等待定位注入确认..."
            MockLocationStatus.RECOVERING -> "正在清理残留模拟定位，恢复真实定位..."
            MockLocationStatus.RUNNING -> if (value.trajectoryCompleted) "轨迹已完成，停留终点；请停止定位以恢复真实位置"
            else if (isTrajectoryMode) {
                val laps = if (value.loop) " | 已完成 ${value.completedLaps} 圈" else ""
                "运行中 | ${String.format(Locale.US, "%.2f", value.speedMetersPerSecond)} m/s$laps"
            } else "运行中"
            MockLocationStatus.DEGRADED -> "定位已降级：${value.message ?: "部分定位通道不可用"}"
            MockLocationStatus.FAILED -> "定位失败：${value.message ?: "请检查权限后重试"}"
        }
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, if (value.isBusy) R.color.claude_terracotta else R.color.claude_text_hint))
        renderMap(center = wasBusy != value.isBusy)
        if (startSucceeded) {
            toast(if (value.status == MockLocationStatus.DEGRADED) "定位已启动，部分通道不可用" else "虚拟定位已启动")
            autoLaunchReady = pendingAutoPackage != null
            launchPendingTarget()
        } else if (startFailed) {
            pendingAutoPackage = null
            autoLaunchReady = false
            if (value.status == MockLocationStatus.FAILED) toast(value.message ?: "定位启动失败")
        }
        if (!value.isBusy && !value.cleanupPending && autoStartPending) consumeAutoStart()
    }

    private fun launchPendingTarget() {
        if (!autoLaunchReady || !MockLocationService.state.value.isRunning ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val target = pendingAutoPackage ?: return
        autoLaunchReady = false
        pendingAutoPackage = null
        if (!MockLocationTargetLauncher.launch(this, target)) toast("无法打开目标应用：$target")
    }

    private fun updateControls() {
        val locked = editingLocked
        renderingControls = true
        binding.switchTrajectoryMode.isChecked = isTrajectoryMode
        binding.switchLoopMode.isChecked = isLoopMode
        binding.switchTrajectoryMode.isEnabled = !locked
        binding.switchLoopMode.isEnabled = !locked
        binding.layoutTrajectoryControls.visibility = if (isTrajectoryMode) View.VISIBLE else View.GONE
        binding.cardCoords.visibility = if (isTrajectoryMode) View.GONE else View.VISIBLE
        binding.layoutLapCount.visibility = if (isLoopMode) View.VISIBLE else View.GONE
        binding.etLatitude.isEnabled = !locked
        binding.etLongitude.isEnabled = !locked
        binding.etSpeed.isEnabled = !locked
        binding.etLapCount.isEnabled = !locked
        campusButtons.forEach { findViewById<View>(it).isEnabled = !locked }
        binding.btnStartStop.isEnabled = !startRequested && !recoveryRequested && !stopRequested &&
            snapshot.status != MockLocationStatus.STARTING && snapshot.status != MockLocationStatus.RECOVERING
        binding.btnStartStop.text = when {
            stopRequested -> "正在停止..."
            snapshot.status == MockLocationStatus.RECOVERING || recoveryRequested -> "正在恢复真实定位..."
            snapshot.status == MockLocationStatus.STARTING || startRequested -> "正在启动..."
            snapshot.isRunning -> "停止模拟定位"
            snapshot.cleanupPending -> "恢复真实定位"
            isTrajectoryMode && isLoopMode -> "开始校园跑"
            isTrajectoryMode -> "开始轨迹模拟"
            else -> "开始模拟定位"
        }
        binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this,
            if (snapshot.isBusy) R.color.claude_text_hint else R.color.claude_terracotta))
        binding.btnRecoverRealLocation.isEnabled = !locked
        binding.btnRecoverRealLocation.visibility = if (snapshot.isBusy) View.GONE else View.VISIBLE
        renderingControls = false
        updateTrajectoryUI()
    }

    private fun selectedPosition(): LatLng? {
        val lat = binding.etLatitude.text.toString().trim().toDoubleOrNull() ?: return null
        val lng = binding.etLongitude.text.toString().trim().toDoubleOrNull() ?: return null
        return LatLng(lat, lng).takeIf { MockLocationCoordinates.isValidPosition(it) }
    }

    private fun setCoordinates(position: LatLng) {
        val previouslyRendering = renderingControls
        renderingControls = true
        binding.etLatitude.setText(position.lat.toString())
        binding.etLongitude.setText(position.lng.toString())
        binding.tvPickedCoords.text = "WGS-84: ${String.format(Locale.US, "%.6f, %.6f", position.lat, position.lng)}"
        renderingControls = previouslyRendering
    }

    private fun openSettings(target: Intent) {
        try { startActivity(target) } catch (_: Exception) { toast("无法打开系统设置，请手动设置") }
    }
    private fun openBatterySettings() {
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
    private fun isIgnoringBatteryOptimization(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    private fun updateBatteryWarning() {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val aggressive = listOf("vivo", "iqoo", "xiaomi", "redmi", "poco", "oppo", "oneplus", "realme", "huawei", "honor", "meizu", "zte", "lenovo")
            .any { manufacturer.contains(it) }
        binding.tvBatteryWarning.visibility = if (aggressive && !isIgnoringBatteryOptimization()) View.VISIBLE else View.GONE
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    inner class MapBridge(private val generation: Int = mapGeneration) {
        @JavascriptInterface
        fun onMapReady() = runOnUiThread {
            if (mapDestroyed || isDestroyed || generation != mapGeneration) return@runOnUiThread
            mapReady = true
            binding.btnReloadMap.visibility = if (mapTilesFailed) View.VISIBLE else View.GONE
            renderMap(center = true)
        }
        @JavascriptInterface
        fun onMapClick(gcjLat: Double, gcjLng: Double) = runOnUiThread {
            if (mapDestroyed || generation != mapGeneration || editingLocked ||
                !gcjLat.isFinite() || gcjLat !in -90.0..90.0 || !gcjLng.isFinite()) return@runOnUiThread
            // Leaflet permits dragging into repeated world copies, whose longitudes exceed ±180.
            val point = MockLocationCoordinates.gcjToWgs(LatLng(gcjLat, MockLocationCoordinates.normalizeLongitude(gcjLng)))
            if (!MockLocationCoordinates.isValidPosition(point)) return@runOnUiThread
            if (isTrajectoryMode) {
                if (waypoints.size >= MockLocationEngine.MAX_WAYPOINTS) { toast("最多支持${MockLocationEngine.MAX_WAYPOINTS}个路径点"); return@runOnUiThread }
                if (waypoints.lastOrNull()?.let { MockLocationCoordinates.distance(it, point) < MockLocationEngine.MIN_DISTANCE_METERS } == true) {
                    toast("路径点过近，请选择相距至少${MockLocationEngine.MIN_DISTANCE_METERS}米的位置"); return@runOnUiThread
                }
                waypoints.add(point)
            } else setCoordinates(point)
            updateControls(); renderMap()
        }
        @JavascriptInterface
        fun onWaypointRemovedAt(index: Int) = runOnUiThread {
            if (mapDestroyed || generation != mapGeneration || editingLocked || index !in waypoints.indices) return@runOnUiThread
            waypoints.removeAt(index); updateControls(); renderMap()
        }

        @JavascriptInterface
        fun onMapTilesFailed() = runOnUiThread {
            if (mapDestroyed || generation != mapGeneration || isDestroyed) return@runOnUiThread
            mapTilesFailed = true
            // Route editing and the local map controls remain usable while remote tiles fail.
            binding.btnReloadMap.visibility = View.VISIBLE
        }

        @JavascriptInterface
        fun onMapTilesReady() = runOnUiThread {
            if (mapDestroyed || generation != mapGeneration || isDestroyed) return@runOnUiThread
            mapTilesFailed = false
            if (mapReady) binding.btnReloadMap.visibility = View.GONE
        }
    }
}
