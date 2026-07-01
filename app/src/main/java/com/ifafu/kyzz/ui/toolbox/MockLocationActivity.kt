package com.ifafu.kyzz.ui.toolbox

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ifafu.kyzz.data.model.LatLng
import com.ifafu.kyzz.databinding.ActivityMockLocationBinding
import com.ifafu.kyzz.service.MockLocationService
import com.ifafu.kyzz.ui.base.BaseActivity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MockLocationActivity : BaseActivity<ActivityMockLocationBinding>() {

    override fun createBinding() = ActivityMockLocationBinding.inflate(layoutInflater)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMockLocation()
        } else {
            Toast.makeText(this, "需要通知权限才能显示运行状态", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startMockLocation()
    }

    private var mockService: MockLocationService? = null
    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mockService = (binder as MockLocationService.MockLocationBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mockService = null
        }
    }

    // Trajectory state
    private val waypoints = mutableListOf<LatLng>()
    private var isTrajectoryMode = false
    private var isLoopMode = false

    // Campus data (GCJ-02 coordinates for Leaflet AMap display)
    private data class CampusInfo(val name: String, val lat: Double, val lng: Double)
    private val campuses = listOf(
        CampusInfo("金山校区", 26.085206, 119.239669),
        CampusInfo("旗山校区", 26.056579, 119.180505),
        CampusInfo("安溪校区", 25.079766, 118.234897),
        CampusInfo("南平校区", 26.555664, 118.119161)
    )
    private val campusButtons = listOf(
        com.ifafu.kyzz.R.id.btnCampusJinshan to 0,
        com.ifafu.kyzz.R.id.btnCampusQishan to 1,
        com.ifafu.kyzz.R.id.btnCampusAnxi to 2,
        com.ifafu.kyzz.R.id.btnCampusNanping to 3
    )

    private val EARTH_RADIUS = 6371000.0

    private var isAutoStarting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)

        // Auto-start from ADB: am start -n ... --ef auto_lat 26.08 --ef auto_lng 119.23
        intent?.let { i ->
            val autoLat = i.getDoubleExtra("auto_lat", Double.NaN)
            val autoLng = i.getDoubleExtra("auto_lng", Double.NaN)
            if (!autoLat.isNaN() && !autoLng.isNaN()) {
                isAutoStarting = true
                prefs.edit()
                    .putFloat("mock_location_lat", autoLat.toFloat())
                    .putFloat("mock_location_lng", autoLng.toFloat())
                    .apply()
                binding.etLatitude.setText(autoLat.toString())
                binding.etLongitude.setText(autoLng.toString())
                val autoPkg = i.getStringExtra("auto_pkg") ?: ""
                binding.root.postDelayed({
                    startMockLocation()
                    if (autoPkg.isNotEmpty()) {
                        binding.root.postDelayed({
                            try {
                                startActivity(
                                    packageManager.getLaunchIntentForPackage(autoPkg)?.apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    }
                                )
                            } catch (_: Exception) {}
                        }, 2000)
                    }
                }, 500)
            }
        }

        val lastLat = prefs.getFloat("mock_location_lat", 0f)
        val lastLng = prefs.getFloat("mock_location_lng", 0f)
        if (lastLat != 0f && lastLng != 0f) {
            binding.etLatitude.setText(lastLat.toString())
            binding.etLongitude.setText(lastLng.toString())
        }

        setupMap()
        setupTrajectoryControls()
        setupButtons()
        setupCampusButtons()
        updateRunningState(MockLocationService.isRunning)

        if (MockLocationService.isRunning) {
            bindMockService()
        }
    }

    override fun onBackPressed() {
        moveTaskToBack(false)
    }

    override fun onDestroy() {
        binding.webView?.apply {
            stopLoading()
            removeJavascriptInterface("AndroidBridge")
            destroy()
        }
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
        super.onDestroy()
    }

    private fun bindMockService() {
        if (isBound) return
        try {
            bindService(
                Intent(this, MockLocationService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            isBound = true
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        updateRunningState(MockLocationService.isRunning)
        updateBatteryWarning()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMap() {
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.allowFileAccess = true
        binding.webView.settings.allowContentAccess = true
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.addJavascriptInterface(MapBridge(), "AndroidBridge")
        binding.webView.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        loadPickerMap()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadPickerMap() {
        val lat = binding.etLatitude.text.toString().toDoubleOrNull() ?: 26.085206
        val lng = binding.etLongitude.text.toString().toDoubleOrNull() ?: 119.239669

        val html = """<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="file:///android_asset/leaflet/leaflet.min.css">
<script src="file:///android_asset/leaflet/leaflet.min.js"></script>
<style>
*{margin:0;padding:0}
html,body,#map{width:100%;height:100%}
.pick-marker{width:20px;height:20px;background:#C75B39;border:3px solid #fff;border-radius:50%;box-shadow:0 2px 8px rgba(0,0,0,.4)}
.wp-marker{width:12px;height:12px;background:#C75B39;border:2px solid #fff;border-radius:50%;box-shadow:0 1px 4px rgba(0,0,0,.4)}
</style>
</head>
<body>
<div id="map"></div>
<script>
var PI=Math.PI,A=6378245.0,EE=0.00669342162296594323;
function tLat(x,y){var r=-100+2*x+3*y+.2*y*y+.1*x*y+.2*Math.sqrt(Math.abs(x));r+=(20*Math.sin(6*x*PI)+20*Math.sin(2*x*PI))*2/3;r+=(20*Math.sin(y*PI)+40*Math.sin(y/3*PI))*2/3;r+=(160*Math.sin(y/12*PI)+320*Math.sin(y*PI/30))*2/3;return r}
function tLng(x,y){var r=300+x+2*y+.1*x*x+.1*x*y+.1*Math.sqrt(Math.abs(x));r+=(20*Math.sin(6*x*PI)+20*Math.sin(2*x*PI))*2/3;r+=(20*Math.sin(x*PI)+40*Math.sin(x/3*PI))*2/3;r+=(150*Math.sin(x/12*PI)+300*Math.sin(x/30*PI))*2/3;return r}
function gcj2wgs(lat,lng){var dLat=tLat(lng-105,lat-35),dLng=tLng(lng-105,lat-35),rLat=lat/180*PI,m=Math.sin(rLat);m=1-EE*m*m;var s=Math.sqrt(m);return{lat:lat-dLat*180/((A*(1-EE))/(m*s)*PI),lng:lng-dLng*180/(A/s*Math.cos(rLat)*PI)}}
var map=L.map('map').setView([$lat,$lng],16);
var satellite=L.tileLayer('https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',{subdomains:['1','2','3','4'],maxZoom:18});
var road=L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',{subdomains:['1','2','3','4'],maxZoom:18}).addTo(map);
L.control.layers({'街道图':road,'卫星图':satellite},null,{collapsed:false}).addTo(map);

// Single-point mode
var pickMarker=null;

// Trajectory mode
var isTrajectoryMode=false;
var waypoints=[];
var wpPolyline=null;
var wpMarkers=[];

var isLoopMode=false;
var wpLoopLine=null;
function setMode(traj){
    isTrajectoryMode=traj;
    if(!traj && pickMarker===null && waypoints.length>0){
        var first=waypoints[0];
        var icon=L.divIcon({className:'pick-marker',iconSize:[20,20],iconAnchor:[10,10]});
        pickMarker=L.marker([first[0],first[1]],{icon:icon}).addTo(map);
    }
}
function setLoopMode(loop){
    isLoopMode=loop;
    updateLoopLine();
}
function updateLoopLine(){
    if(wpLoopLine){map.removeLayer(wpLoopLine);wpLoopLine=null;}
    if(isLoopMode && waypoints.length>=2){
        wpLoopLine=L.polyline([waypoints[waypoints.length-1],waypoints[0]],{
            color:'#C75B39',weight:2,opacity:0.5,dashArray:'6,8'
        }).addTo(map);
    }
}

function clearWaypoints(){
    waypoints=[];
    wpMarkers.forEach(function(m){map.removeLayer(m);});
    wpMarkers=[];
    if(wpPolyline){map.removeLayer(wpPolyline);wpPolyline=null;}
    updateLoopLine();
    if(typeof AndroidBridge!=='undefined') AndroidBridge.onWaypointsCleared();
}

function undoLastWaypoint(){
    if(waypoints.length===0) return;
    waypoints.pop();
    var m=wpMarkers.pop();
    if(m) map.removeLayer(m);
    if(wpPolyline){map.removeLayer(wpPolyline);wpPolyline=null;}
    if(waypoints.length>=2){
        wpPolyline=L.polyline(waypoints,{color:'#C75B39',weight:3,opacity:0.8}).addTo(map);
    }
    updateLoopLine();
    if(typeof AndroidBridge!=='undefined') AndroidBridge.onWaypointUndone(waypoints.length);
}

function pickCampus(gcjLat,gcjLng){
    map.setView([gcjLat,gcjLng],16);
    var wgs=gcj2wgs(gcjLat,gcjLng);
    if(pickMarker) map.removeLayer(pickMarker);
    var icon=L.divIcon({className:'pick-marker',iconSize:[20,20],iconAnchor:[10,10]});
    pickMarker=L.marker([gcjLat,gcjLng],{icon:icon}).addTo(map);
    if(typeof AndroidBridge!=='undefined') AndroidBridge.onMapClick(wgs.lat,wgs.lng);
}
map.on('click',function(e){
    var gcj=e.latlng;
    var wgs=gcj2wgs(gcj.lat,gcj.lng);
    if(isTrajectoryMode){
        waypoints.push([gcj.lat,gcj.lng]);
        var icon=L.divIcon({className:'wp-marker',iconSize:[14,14],iconAnchor:[7,7]});
        var marker=L.marker([gcj.lat,gcj.lng],{icon:icon}).addTo(map);
        marker.on('click',function(){
            var idx=wpMarkers.indexOf(this);
            if(idx>-1){
                map.removeLayer(this);
                waypoints.splice(idx,1);
                wpMarkers.splice(idx,1);
                if(wpPolyline){map.removeLayer(wpPolyline);wpPolyline=null;}
                if(waypoints.length>=2){
                    wpPolyline=L.polyline(waypoints,{color:'#C75B39',weight:3,opacity:0.8}).addTo(map);
                }
                updateLoopLine();
                if(typeof AndroidBridge!=='undefined') AndroidBridge.onWaypointRemovedAt(idx,waypoints.length);
            }
        });
        wpMarkers.push(marker);
        if(waypoints.length>=2){
            if(wpPolyline) map.removeLayer(wpPolyline);
            wpPolyline=L.polyline(waypoints,{color:'#C75B39',weight:3,opacity:0.8}).addTo(map);
        }
        updateLoopLine();
        if(typeof AndroidBridge!=='undefined') AndroidBridge.onWaypointAdded(wgs.lat,wgs.lng,waypoints.length);
    } else {
        if(pickMarker) map.removeLayer(pickMarker);
        var icon=L.divIcon({className:'pick-marker',iconSize:[20,20],iconAnchor:[10,10]});
        pickMarker=L.marker([gcj.lat,gcj.lng],{icon:icon}).addTo(map);
        if(typeof AndroidBridge!=='undefined') AndroidBridge.onMapClick(wgs.lat,wgs.lng);
    }
});
</script>
</body></html>""".trimIndent()

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Restore trajectory mode state after page load
                if (isTrajectoryMode) {
                    view?.evaluateJavascript("javascript:setMode(true)", null)
                }
            }
        }
        binding.webView.loadDataWithBaseURL("file:///android_asset/leaflet/", html, "text/html", "UTF-8", null)
    }

    // ==================== Trajectory Controls ====================

    private var trajectoryDistance = 0.0

    private fun setupTrajectoryControls() {
        binding.switchTrajectoryMode.setOnCheckedChangeListener { _, isChecked ->
            isTrajectoryMode = isChecked
            binding.layoutTrajectoryControls.visibility =
                if (isChecked) View.VISIBLE else View.GONE
            binding.cardCoords.visibility =
                if (isChecked) View.GONE else View.VISIBLE
            binding.webView.evaluateJavascript("javascript:setMode($isChecked)", null)

            if (isChecked && waypoints.isNotEmpty()) {
                updateTrajectoryUI()
            }
        }

        binding.btnUndoWaypoint.setOnClickListener {
            binding.webView.evaluateJavascript("javascript:undoLastWaypoint()", null)
        }

        binding.btnClearWaypoints.setOnClickListener {
            binding.webView.evaluateJavascript("javascript:clearWaypoints()", null)
        }

        binding.switchLoopMode.setOnCheckedChangeListener { _, isChecked ->
            isLoopMode = isChecked
            binding.layoutLapCount.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.webView.evaluateJavascript("javascript:setLoopMode($isChecked)", null)
            updateTrajectoryUI()
        }
    }

    // ==================== Campus Quick-Select ====================

    private fun setupCampusButtons() {
        campusButtons.forEach { pair ->
            val btnId = pair.first
            val index = pair.second
            findViewById<com.google.android.material.button.MaterialButton>(btnId)?.setOnClickListener {
                if (MockLocationService.isRunning) {
                    Toast.makeText(this, "请先停止模拟定位再切换位置", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val campus = campuses[index]
                binding.webView.evaluateJavascript(
                    "javascript:pickCampus(${campus.lat}, ${campus.lng})", null
                )
                binding.tvPickedCoords.text = "已选择: ${campus.name}"

                // Clear trajectory waypoints when switching campus
                if (isTrajectoryMode) {
                    waypoints.clear()
                    trajectoryDistance = 0.0
                    binding.webView.evaluateJavascript("javascript:clearWaypoints()", null)
                    updateTrajectoryUI()
                }
            }
        }
    }

    private fun updateTrajectoryUI() {
        val count = waypoints.size
        binding.tvWaypointInfo.text = "路径点: $count 个"
        binding.btnUndoWaypoint.isEnabled = count > 0
        binding.btnClearWaypoints.isEnabled = count > 0

        if (count >= 2) {
            trajectoryDistance = calculateTotalDistance(waypoints)
            val loopDist = if (isLoopMode && count >= 2) haversine(waypoints.last(), waypoints.first()) else 0.0
            val lapDist = trajectoryDistance + loopDist
            val speed = (binding.etSpeed.text.toString().toDoubleOrNull() ?: 1.2).coerceAtLeast(0.01)
            val lapDistKm = lapDist / 1000.0
            val lapTimeSec = (lapDist / speed).toInt()
            val lapTimeStr = if (lapTimeSec >= 60) {
                "${lapTimeSec / 60}分${lapTimeSec % 60}秒"
            } else "${lapTimeSec}秒"

            binding.tvTrajectoryStats.visibility = View.VISIBLE
            binding.tvTrajectoryStats.text = if (isLoopMode) {
                val laps = binding.etLapCount.text.toString().toIntOrNull() ?: 0
                val total = if (laps > 0) {
                    val totalSec = (lapDist * laps / speed).toInt()
                    "  |  共${laps}圈: ${totalSec / 60}分${totalSec % 60}秒"
                } else ""
                "单圈: ${"%.2f".format(lapDistKm)} 公里 / 约$lapTimeStr (${speed} m/s)$total"
            } else {
                "总距离: ${"%.2f".format(lapDistKm)} 公里 | 预计: $lapTimeStr (${speed} m/s)"
            }
            binding.tvWaypointInfo.text = "路径点: $count 个（可点击地图继续添加）"
        } else {
            binding.tvTrajectoryStats.visibility = View.GONE
            binding.tvWaypointInfo.text = if (count == 1) {
                "已添加 1 个点，请再添加一个以形成路径"
            } else {
                "点击地图添加路径点（至少2个）"
            }
        }
    }

    private fun calculateTotalDistance(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += haversine(points[i], points[i + 1])
        }
        return total
    }

    private fun haversine(p1: LatLng, p2: LatLng): Double {
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLng = Math.toRadians(p2.lng - p1.lng)
        val a = sin(dLat / 2).pow2() +
                cos(Math.toRadians(p1.lat)) * cos(Math.toRadians(p2.lat)) * sin(dLng / 2).pow2()
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS * c
    }

    private fun Double.pow2() = this * this

    // ==================== Buttons ====================

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (MockLocationService.isRunning) {
                stopMockLocation()
            } else {
                startMockLocation()
            }
        }

        binding.btnBatteryOpt.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开电池优化设置", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnAutoStart.setOnClickListener {
            try {
                val intent = Intent().apply {
                    when (Build.MANUFACTURER.lowercase()) {
                        "xiaomi" -> {
                            component = ComponentName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.autostart.AutoStartManagementActivity"
                            )
                        }
                        "huawei" -> {
                            component = ComponentName(
                                "com.huawei.systemmanager",
                                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                            )
                        }
                        "oppo" -> {
                            component = ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.startupapp.StartupAppListActivity"
                            )
                        }
                        "vivo" -> {
                            component = ComponentName(
                                "com.vivo.permissionmanager",
                                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                            )
                        }
                        else -> {
                            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            data = Uri.parse("package:$packageName")
                        }
                    }
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, "请在手机管家中允许本应用自启动", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnNotificationSettings.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开通知设置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startMockLocation() {
        if (isTrajectoryMode) {
            startTrajectoryMockLocation()
            return
        }

        val latStr = binding.etLatitude.text.toString().trim()
        val lngStr = binding.etLongitude.text.toString().trim()

        if (latStr.isEmpty() || lngStr.isEmpty()) {
            Toast.makeText(this, "请输入经纬度", Toast.LENGTH_SHORT).show()
            return
        }

        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()

        if (lat == null || lng == null) {
            Toast.makeText(this, "经纬度格式不正确", Toast.LENGTH_SHORT).show()
            return
        }

        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            Toast.makeText(this, "纬度范围 -90~90，经度范围 -180~180", Toast.LENGTH_SHORT).show()
            return
        }

        if (!checkPermissions()) return

        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putFloat("mock_location_lat", lat.toFloat())
            .putFloat("mock_location_lng", lng.toFloat())
            .apply()

        val serviceIntent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_LATITUDE, lat)
            putExtra(MockLocationService.EXTRA_LONGITUDE, lng)
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_SINGLE)
        }
        bindMockService()
        startForegroundService(serviceIntent)

        updateRunningState(true)
        Toast.makeText(this, "虚拟定位已启动: ($lat, $lng)", Toast.LENGTH_SHORT).show()
    }

    private fun startTrajectoryMockLocation() {
        if (waypoints.size < 2) {
            Toast.makeText(this, "轨迹模式需要至少2个路径点", Toast.LENGTH_SHORT).show()
            return
        }

        val speed = binding.etSpeed.text.toString().toDoubleOrNull()
        if (speed == null || speed <= 0) {
            Toast.makeText(this, "请输入有效的速度（m/s）", Toast.LENGTH_SHORT).show()
            return
        }

        if (!checkPermissions()) return

        val laps = if (isLoopMode) (binding.etLapCount.text.toString().toIntOrNull() ?: 0) else 0

        // Store trajectory in service companion object
        MockLocationService.pendingTrajectory = waypoints.toList()
        MockLocationService.pendingTrajectorySpeed = speed
        MockLocationService.pendingTrajectoryLoop = isLoopMode
        MockLocationService.pendingTrajectoryLaps = laps

        val first = waypoints.first()
        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putFloat("mock_location_lat", first.lat.toFloat())
            .putFloat("mock_location_lng", first.lng.toFloat())
            .apply()

        val serviceIntent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_LATITUDE, first.lat)
            putExtra(MockLocationService.EXTRA_LONGITUDE, first.lng)
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_TRAJECTORY)
            putExtra(MockLocationService.EXTRA_TRAJECTORY_SPEED, speed)
        }
        bindMockService()
        startForegroundService(serviceIntent)

        updateRunningState(true)
        val distKm = trajectoryDistance / 1000.0
        Toast.makeText(
            this,
            "轨迹模拟已启动: ${"%.2f".format(distKm)} 公里 @ ${speed} m/s (${waypoints.size}个路径点)",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun checkPermissions(): Boolean {
        // Overlay permission (skip when auto-starting from ADB)
        if (!isAutoStarting && !Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("虚拟定位需要悬浮窗权限来显示方向控制器。")
                .setPositiveButton("去开启") { _, _ ->
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return false
        }

        // Battery optimization warning
        if (!isIgnoringBatteryOptimization() && isAggressiveOem()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("建议关闭电池优化")
                .setMessage("检测到电池优化未关闭，虚拟定位可能被系统中断。\n\n建议关闭电池优化以保证持续运行。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                            Toast.makeText(this, "请手动到设置中关闭电池优化", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("暂不处理", null)
                .show()
        }

        // Notification permission for API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        }

        return true
    }

    private fun stopMockLocation() {
        mockService?.stopTrajectory()
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
        MockLocationService.stopService(this)
        updateRunningState(false)
        Toast.makeText(this, "虚拟定位已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateRunningState(running: Boolean) {
        if (running) {
            binding.btnStartStop.text = "停止模拟定位"
            binding.btnStartStop.setBackgroundColor(
                resources.getColor(com.ifafu.kyzz.R.color.claude_text_hint, null)
            )
            binding.tvStatus.text = "运行中..."
            binding.tvStatus.setTextColor(
                resources.getColor(com.ifafu.kyzz.R.color.claude_terracotta, null)
            )
            binding.etLatitude.isEnabled = false
            binding.etLongitude.isEnabled = false
            binding.switchTrajectoryMode.isEnabled = false
        } else {
            binding.btnStartStop.text = when {
                isTrajectoryMode && waypoints.size >= 2 && isLoopMode -> "开始校园跑"
                isTrajectoryMode && waypoints.size >= 2 -> "开始轨迹模拟"
                else -> "开始模拟定位"
            }
            binding.btnStartStop.setBackgroundColor(
                resources.getColor(com.ifafu.kyzz.R.color.claude_terracotta, null)
            )
            binding.tvStatus.text = "未启动"
            binding.tvStatus.setTextColor(
                resources.getColor(com.ifafu.kyzz.R.color.claude_text_hint, null)
            )
            binding.etLatitude.isEnabled = true
            binding.etLongitude.isEnabled = true
            binding.switchTrajectoryMode.isEnabled = true
        }
    }

    private fun isIgnoringBatteryOptimization(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryWarning() {
        binding.tvBatteryWarning.visibility =
            if (!isIgnoringBatteryOptimization() && isAggressiveOem()) View.VISIBLE else View.GONE
    }

    private fun isAggressiveOem(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("vivo") || m.contains("iqoo") ||
                m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                m.contains("oppo") || m.contains("oneplus") || m.contains("realme") ||
                m.contains("huawei") || m.contains("honor") ||
                m.contains("meizu") || m.contains("zte") || m.contains("lenovo")
    }

    // ==================== JavaScript Bridge ====================

    inner class MapBridge {
        @JavascriptInterface
        fun onMapClick(lat: Double, lng: Double) {
            runOnUiThread {
                binding.etLatitude.setText(String.format("%.6f", lat))
                binding.etLongitude.setText(String.format("%.6f", lng))
                binding.tvPickedCoords.text = "已选择: ${String.format("%.6f", lat)}, ${String.format("%.6f", lng)}"
            }
        }

        @JavascriptInterface
        fun onWaypointAdded(lat: Double, lng: Double, count: Int) {
            runOnUiThread {
                if (count > waypoints.size) {
                    waypoints.add(LatLng(lat, lng))
                }
                updateTrajectoryUI()
            }
        }

        @JavascriptInterface
        fun onWaypointsCleared() {
            runOnUiThread {
                waypoints.clear()
                trajectoryDistance = 0.0
                updateTrajectoryUI()
            }
        }

        @JavascriptInterface
        fun onWaypointUndone(count: Int) {
            runOnUiThread {
                while (waypoints.size > count) {
                    waypoints.removeAt(waypoints.size - 1)
                }
                updateTrajectoryUI()
            }
        }

        @JavascriptInterface
        fun onWaypointRemovedAt(index: Int, count: Int) {
            runOnUiThread {
                if (index < waypoints.size) {
                    waypoints.removeAt(index)
                }
                trajectoryDistance = calculateTotalDistance(waypoints)
                updateTrajectoryUI()
            }
        }
    }
}
