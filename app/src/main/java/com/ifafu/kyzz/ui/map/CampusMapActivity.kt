package com.ifafu.kyzz.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityCampusMapBinding
import com.ifafu.kyzz.ui.base.BaseActivity

class CampusMapActivity : BaseActivity<ActivityCampusMapBinding>(), LocationListener {

    override fun createBinding() = ActivityCampusMapBinding.inflate(layoutInflater)

    private val campuses = listOf(
        Campus("金山校区", 26.085206, 119.239669, listOf(
            Place("博学楼", 26.086327, 119.235599, "教学楼"),
            Place("常盛楼", 26.082706, 119.236895, "教学楼"),
            Place("北区田径场", 26.086023, 119.240494, "运动场/跑道"),
            Place("下安田径场", 26.076947, 119.237111, "运动场/跑道"),
            Place("中华园", 26.082497, 119.235408, "校园景观")
        )),
        Campus("安溪茶学院", 25.079766, 118.234897, listOf(
            Place("食堂", 25.078867, 118.236826, "学生食堂"),
            Place("教学楼", 25.07895, 118.235415, "茶学院教学楼"),
            Place("行政楼", 25.079984, 118.232731, "行政办公"),
            Place("教师公寓", 25.081853, 118.235548, "教师住宿区"),
            Place("第三食堂", 25.076062, 118.237966, "第三学生食堂"),
            Place("体育场", 25.077319, 118.235394, "运动场"),
            Place("学生宿舍", 25.077101, 118.237037, "学生住宿区")
        )),
        Campus("南平校区", 26.555664, 118.119161, listOf(
            Place("食堂", 26.555278, 118.117938, "学生食堂"),
            Place("教师公寓", 26.553919, 118.119893, "教师住宿区"),
            Place("图书馆", 26.557459, 118.119922, "南平校区图书馆"),
            Place("教学楼", 26.556262, 118.119879, "南平校区教学楼")
        ))
    )

    private var currentCampusIndex = 0
    private val currentPlaces get() = campuses[currentCampusIndex].places
    private var locationManager: LocationManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startNativeLocation()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.blockNetworkLoads = false
        binding.webView.webViewClient = WebViewClient()
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.addJavascriptInterface(MapBridge(), "AndroidBridge")

        loadMap()

        binding.btnSwitchCampus.setOnClickListener {
            val adapter = binding.recyclerView.adapter as? CampusAdapter
            if (adapter != null && adapter.mode == 1) {
                adapter.mode = 0
                adapter.notifyDataSetChanged()
                binding.btnSwitchCampus.visibility = View.GONE
                binding.tvSelectedPlace.text = "选择校区或点击地图标记查看位置"
                binding.tvSelectedPlace.setTextColor(
                    resources.getColor(R.color.claude_text_hint, null)
                )
            }
        }

        binding.recyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerView.adapter = CampusAdapter()
    }

    // ---------- 原生 GPS 定位 ----------

    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startNativeLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNativeLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        this.locationManager = lm

        // 优先用 GPS
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, mainLooper)
        }
        // 同时请求网络定位作为快速兜底
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, mainLooper)
        }
        // 立即用上次缓存的位置
        val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (lastKnown != null) {
            onLocationChanged(lastKnown)
        }
    }

    override fun onLocationChanged(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val acc = location.accuracy
        runOnUiThread {
            binding.webView.evaluateJavascript(
                "showUserLocation($lat,$lng,$acc)", null
            )
        }
    }

    // ---------- 地图 ----------

    private fun loadMap() {
        val campus = campuses[currentCampusIndex]
        val markersJson = campus.places.joinToString(",") { p ->
            """{"name":"${p.name}","lat":${p.lat},"lng":${p.lng},"desc":"${p.desc}"}"""
        }

        val html = """<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<style>
*{margin:0;padding:0}
html,body,#map{width:100%;height:100%}
.leaflet-popup-content-wrapper{border-radius:12px!important;font-family:serif}
.leaflet-popup-content{margin:10px 14px!important;font-size:14px}
.custom-marker{width:24px;height:24px;background:#C75B39;border:2px solid #fff;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,.3)}
.user-marker{width:18px;height:18px;background:#4285F4;border:3px solid #fff;border-radius:50%;box-shadow:0 0 10px rgba(66,133,244,.6)}
</style>
</head>
<body>
<div id="map"></div>
<button style="position:absolute;bottom:20px;right:20px;z-index:1000;width:40px;height:40px;background:#fff;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,.3);display:flex;align-items:center;justify-content:center;cursor:pointer;font-size:18px;border:none" onclick="requestLocation()" title="定位">&#x1F4CD;</button>
<script>
var map=L.map('map',{zoomControl:true}).setView([${campus.lat},${campus.lng}],16);
L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',{
  subdomains:['1','2','3','4'],
  maxZoom:18
}).addTo(map);

var places=[$markersJson];
var markers=[];
var userMarker=null;
var userCircle=null;

for(var i=0;i<places.length;i++){
  var p=places[i];
  var icon=L.divIcon({className:'custom-marker',iconSize:[24,24],iconAnchor:[12,24],popupAnchor:[0,-24]});
  var m=L.marker([p.lat,p.lng],{icon:icon}).addTo(map)
    .bindPopup('<b>'+p.name+'</b><br><span style="color:#888">'+p.desc+'</span>');
  (function(idx){
    m.on('click',function(){
      if(typeof AndroidBridge!=='undefined') AndroidBridge.onMarkerClick(idx);
    });
  })(i);
  markers.push(m);
}

function showUserLocation(lat,lng,acc){
  if(userMarker) map.removeLayer(userMarker);
  if(userCircle) map.removeLayer(userCircle);
  var icon=L.divIcon({className:'user-marker',iconSize:[18,18],iconAnchor:[9,9]});
  userMarker=L.marker([lat,lng],{icon:icon}).addTo(map)
    .bindPopup('<b>我的位置</b><br>精度:'+Math.round(acc)+'米');
  userCircle=L.circle([lat,lng],{radius:acc,color:'#4285F4',fillColor:'#4285F4',fillOpacity:0.1,weight:1,opacity:0.3}).addTo(map);
  map.flyTo([lat,lng],17,{duration:0.6});
  userMarker.openPopup();
}

function requestLocation(){
  if(typeof AndroidBridge!=='undefined') AndroidBridge.requestLocation();
}

function goTo(idx){
  var p=places[idx];
  map.flyTo([p.lat,p.lng],17,{duration:0.6});
  setTimeout(function(){markers[idx].openPopup();},700);
}

function switchCampus(lat,lng,markersJson){
  map.flyTo([lat,lng],16,{duration:0.8});
  for(var i=0;i<markers.length;i++) map.removeLayer(markers[i]);
  markers=[];
  places=JSON.parse(markersJson);
  setTimeout(function(){
    for(var i=0;i<places.length;i++){
      var p=places[i];
      var icon=L.divIcon({className:'custom-marker',iconSize:[24,24],iconAnchor:[12,24],popupAnchor:[0,-24]});
      var m=L.marker([p.lat,p.lng],{icon:icon}).addTo(map)
        .bindPopup('<b>'+p.name+'</b><br><span style="color:#888">'+p.desc+'</span>');
      (function(idx){
        m.on('click',function(){
          if(typeof AndroidBridge!=='undefined') AndroidBridge.onMarkerClick(idx);
        });
      })(i);
      markers.push(m);
    }
  },900);
}
</script>
</body></html>"""

        binding.webView.loadDataWithBaseURL("https://webrd01.is.autonavi.com", html, "text/html", "UTF-8", null)
    }

    private fun switchCampus(index: Int) {
        currentCampusIndex = index
        val campus = campuses[index]
        val markersJson = campus.places.joinToString(",") { p ->
            """{"name":"${p.name}","lat":${p.lat},"lng":${p.lng},"desc":"${p.desc}"}"""
        }
        binding.tvSelectedPlace.text = if (campus.places.isEmpty()) "已切换到${campus.name}" else "已切换到${campus.name}，点击地点查看位置"
        binding.tvSelectedPlace.setTextColor(resources.getColor(R.color.claude_text_hint, null))
        binding.btnSwitchCampus.visibility = View.VISIBLE
        binding.webView.evaluateJavascript(
            "switchCampus(${campus.lat},${campus.lng},'[$markersJson]')", null
        )
        (binding.recyclerView.adapter as? CampusAdapter)?.let { adapter ->
            if (adapter.mode == 1) adapter.notifyDataSetChanged()
        }
    }

    private fun selectPlace(index: Int) {
        val place = currentPlaces[index]
        binding.tvSelectedPlace.text = "${place.name}  ·  ${place.desc}"
        binding.tvSelectedPlace.setTextColor(resources.getColor(R.color.claude_terracotta, null))
        binding.webView.evaluateJavascript("goTo($index)", null)
    }

    private inner class MapBridge {
        @JavascriptInterface
        fun onMarkerClick(index: Int) {
            runOnUiThread {
                val place = currentPlaces.getOrNull(index) ?: return@runOnUiThread
                binding.tvSelectedPlace.text = "${place.name}  ·  ${place.desc}"
                binding.tvSelectedPlace.setTextColor(
                    resources.getColor(R.color.claude_terracotta, null)
                )
            }
        }

        @JavascriptInterface
        fun requestLocation() {
            runOnUiThread { this@CampusMapActivity.requestLocation() }
        }
    }

    override fun onPause() {
        super.onPause()
        locationManager?.removeUpdates(this)
    }

    data class Place(val name: String, val lat: Double, val lng: Double, val desc: String)
    data class Campus(val name: String, val lat: Double, val lng: Double, val places: List<Place>)

    inner class CampusAdapter : RecyclerView.Adapter<CampusAdapter.VH>() {
        var mode = 0
        private val campusItems = campuses.map { it.name }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = MaterialCardView(parent.context).apply {
                radius = 20f
                cardElevation = 0f
                strokeWidth = 1
                strokeColor = resources.getColor(R.color.claude_border, null)
                setCardBackgroundColor(resources.getColor(R.color.claude_bg, null))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                isClickable = true
                isFocusable = true
            }
            val tv = TextView(parent.context).apply {
                setPadding(24, 12, 24, 12)
                textSize = 13f
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                typeface = resources.getFont(R.font.claude_serif)
                gravity = Gravity.CENTER
            }
            card.addView(tv)
            return VH(card, tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (mode == 0) {
                holder.tv.text = campusItems[position]
                holder.itemView.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    switchCampus(pos)
                    mode = 1
                    notifyDataSetChanged()
                }
            } else {
                holder.tv.text = currentPlaces[position].name
                holder.itemView.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    selectPlace(pos)
                }
            }
        }

        override fun getItemCount(): Int {
            return if (mode == 0) campusItems.size else currentPlaces.size
        }

        inner class VH(itemView: View, val tv: TextView) : RecyclerView.ViewHolder(itemView)
    }
}
