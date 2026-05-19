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
import com.ifafu.kyzz.databinding.ActivityMockLocationBinding
import com.ifafu.kyzz.service.MockLocationService
import com.ifafu.kyzz.ui.base.BaseActivity

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Load last saved coordinates
        val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        val lastLat = prefs.getFloat("mock_location_lat", 0f)
        val lastLng = prefs.getFloat("mock_location_lng", 0f)
        if (lastLat != 0f && lastLng != 0f) {
            binding.etLatitude.setText(lastLat.toString())
            binding.etLongitude.setText(lastLng.toString())
        }

        setupMap()
        setupButtons()
        updateRunningState(MockLocationService.isRunning)

        // Bind to service if running
        if (MockLocationService.isRunning) {
            bindMockService()
        }
    }

    override fun onBackPressed() {
        moveTaskToBack(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
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
        binding.webView.webViewClient = WebViewClient()
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
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<style>
*{margin:0;padding:0}
html,body,#map{width:100%;height:100%}
.pick-marker{width:20px;height:20px;background:#C75B39;border:3px solid #fff;border-radius:50%;box-shadow:0 2px 8px rgba(0,0,0,.4)}
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
L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',{subdomains:['1','2','3','4'],maxZoom:18}).addTo(map);
var pickMarker=null;
map.on('click',function(e){
  var gcjLat=e.latlng.lat,gcjLng=e.latlng.lng;
  var wgs=gcj2wgs(gcjLat,gcjLng);
  if(pickMarker) map.removeLayer(pickMarker);
  var icon=L.divIcon({className:'pick-marker',iconSize:[20,20],iconAnchor:[10,10]});
  pickMarker=L.marker([gcjLat,gcjLng],{icon:icon}).addTo(map);
  if(typeof AndroidBridge!=='undefined') AndroidBridge.onMapClick(wgs.lat,wgs.lng);
});
</script>
</body></html>""".trimIndent()

        binding.webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

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

        // Overlay permission for floating control panel
        if (!Settings.canDrawOverlays(this)) {
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
            return
        }

        // Battery optimization warning for aggressive OEMs
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

        // Request notification permission for API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // Save coordinates
        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putFloat("mock_location_lat", lat.toFloat())
            .putFloat("mock_location_lng", lng.toFloat())
            .apply()

        // GoGoGo pattern: bind first (triggers onCreate → provider registration),
        // then startForegroundService (triggers onStartCommand → coordinate update)
        val serviceIntent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_LATITUDE, lat)
            putExtra(MockLocationService.EXTRA_LONGITUDE, lng)
        }
        bindMockService()
        startForegroundService(serviceIntent)

        updateRunningState(true)
        Toast.makeText(this, "虚拟定位已启动: ($lat, $lng)", Toast.LENGTH_SHORT).show()
    }

    private fun stopMockLocation() {
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
        } else {
            binding.btnStartStop.text = "开始模拟定位"
            binding.btnStartStop.setBackgroundColor(
                resources.getColor(com.ifafu.kyzz.R.color.claude_terracotta, null)
            )
            binding.tvStatus.text = "未启动"
            binding.tvStatus.setTextColor(
                resources.getColor(com.ifafu.kyzz.R.color.claude_text_hint, null)
            )
            binding.etLatitude.isEnabled = true
            binding.etLongitude.isEnabled = true
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

    inner class MapBridge {
        @JavascriptInterface
        fun onMapClick(lat: Double, lng: Double) {
            runOnUiThread {
                binding.etLatitude.setText(String.format("%.6f", lat))
                binding.etLongitude.setText(String.format("%.6f", lng))
                binding.tvPickedCoords.text = "已选择: ${String.format("%.6f", lat)}, ${String.format("%.6f", lng)}"
            }
        }
    }
}
