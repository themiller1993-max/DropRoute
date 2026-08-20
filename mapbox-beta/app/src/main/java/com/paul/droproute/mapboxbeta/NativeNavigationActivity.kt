package com.paul.droproute.mapboxbeta

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.TileStoreUsageMode
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.mapsOptions
import com.mapbox.maps.plugin.locationcomponent.location
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.*

class NativeNavigationActivity : Activity(), LocationListener {
    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var speedView: TextView
    private lateinit var limitView: TextView
    private lateinit var turnView: TextView
    private lateinit var arriveBtn: Button
    private lateinit var deliveredBtn: Button
    private lateinit var undeliveredBtn: Button
    private lateinit var breakBtn: Button
    private lateinit var payload: JSONObject
    private var token = ""
    private var kind = "stop"
    private var targetLat = Double.NaN
    private var targetLon = Double.NaN
    private var arrived = false
    private var breakActive = false
    private var lastLoc: Location? = null
    private var routeSource: GeoJsonSource? = null
    private var routeCoords: List<Point> = emptyList()
    private var maxSpeeds: List<Double?> = emptyList()
    private var lastFetchAt = 0L
    private var fetching = false
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        payload = try { JSONObject(intent.getStringExtra("payload") ?: "{}") } catch (_: Exception) { JSONObject() }
        token = payload.optString("token", "")
        if (!token.startsWith("pk.")) {
            Toast.makeText(this, "Add your Mapbox public token in DropRoute Settings first.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        MapboxOptions.accessToken = token
        MapboxOptions.mapsOptions.tileStoreUsageMode = TileStoreUsageMode.READ_ONLY
        kind = payload.optString("kind", "stop")
        targetLat = payload.optJSONObject("target")?.optDouble("lat", Double.NaN) ?: Double.NaN
        targetLon = payload.optJSONObject("target")?.optDouble("lon", Double.NaN) ?: Double.NaN
        arrived = payload.optBoolean("arrived", false)
        breakActive = payload.optBoolean("breakActive", false)
        buildUi()
        loadInitialRoute()
        startLocation()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.argb(238, 17, 24, 39))
        }
        titleView = TextView(this).apply { text = payload.optString("title", "Next stop"); setTextColor(Color.WHITE); textSize = 19f; setTypeface(typeface, 1) }
        subView = TextView(this).apply { text = payload.optString("parcel", ""); setTextColor(Color.rgb(191,219,254)); textSize = 12f }
        turnView = TextView(this).apply { text = "Mapbox navigation map"; setTextColor(Color.WHITE); textSize = 15f; setPadding(0, dp(7), 0, 0) }
        top.addView(titleView); top.addView(subView); top.addView(turnView)
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val speedBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(5), dp(8), dp(5)); setBackgroundColor(Color.WHITE)
        }
        speedView = TextView(this).apply { text = "0 km/h"; setTextColor(Color.BLACK); textSize = 18f; setTypeface(typeface, 1); setPadding(dp(6),0,dp(12),0) }
        limitView = TextView(this).apply { text = "—"; setTextColor(Color.BLACK); textSize = 21f; setTypeface(typeface,1); gravity = Gravity.CENTER; background = android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(4), Color.RED); setSize(dp(58),dp(58)) } }
        speedBox.addView(speedView); speedBox.addView(limitView, LinearLayout.LayoutParams(dp(58),dp(58)))
        root.addView(speedBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin=dp(12); bottomMargin=dp(190) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10),dp(10),dp(10),dp(12)); setBackgroundColor(Color.argb(245,255,255,255)) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        arriveBtn = actionButton("📍 At location") { finishAction("arrive") }
        breakBtn = actionButton(if (breakActive) "☕ Break active" else "☕ Break") { finishAction("break") }
        breakBtn.isEnabled = !breakActive
        row1.addView(arriveBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd=dp(5) })
        row1.addView(breakBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart=dp(5) })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mainLabel = when(kind) { "fuel" -> "⛽ Fuel stop"; "final" -> "🏁 Finish day"; else -> "✓ Delivered" }
        deliveredBtn = actionButton(mainLabel) { if (kind == "stop") finishAction("delivered") else finishAction("special") }
        undeliveredBtn = actionButton("✕ Undelivered") { finishAction("undelivered") }
        row2.addView(deliveredBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd=dp(5) })
        row2.addView(undeliveredBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart=dp(5) })
        val back = actionButton("← Back to DropRoute") { finishAction("back") }
        bottom.addView(row1); bottom.addView(row2, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin=dp(6) }); bottom.addView(back, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin=dp(6) })
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        mapView.location.updateSettings { enabled = true; pulsingEnabled = true }
        mapView.mapboxMap.loadStyle(Style.STANDARD) { drawRoute(routeCoords) }
        refreshActionState()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; isAllCaps = false; setOnClickListener { action() }
    }

    private fun loadInitialRoute() {
        val geo = payload.optJSONObject("routeGeo") ?: return
        val cs = geo.optJSONArray("coordinates") ?: return
        routeCoords = parsePoints(cs)
        if (::mapView.isInitialized) drawRoute(routeCoords)
    }

    private fun parsePoints(a: JSONArray): List<Point> {
        val out = ArrayList<Point>()
        for (i in 0 until a.length()) {
            val c = a.optJSONArray(i) ?: continue
            if (c.length() >= 2) out.add(Point.fromLngLat(c.optDouble(0), c.optDouble(1)))
        }
        return out
    }

    private fun drawRoute(points: List<Point>) {
        if (points.size < 2 || !::mapView.isInitialized) return
        mapView.mapboxMap.getStyle { style ->
            val line = LineString.fromLngLats(points)
            val existing = routeSource
            if (existing != null) existing.geometry(line)
            else {
                val src = GeoJsonSource.Builder("droproute-route").geometry(line).build()
                style.addSource(src); routeSource = src
                val casing = LineLayer("droproute-route-casing", "droproute-route").lineColor("#FFFFFF").lineWidth(10.0).lineCap(LineCap.ROUND).lineJoin(LineJoin.ROUND)
                val layer = LineLayer("droproute-route-line", "droproute-route").lineColor("#2563EB").lineWidth(6.5).lineCap(LineCap.ROUND).lineJoin(LineJoin.ROUND)
                style.addLayer(casing); style.addLayer(layer)
            }
        }
    }

    private fun startLocation() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 44); return
        }
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this) } catch (_: Exception) {}
        try { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500L, 3f, this) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 44) startLocation()
    }

    override fun onLocationChanged(loc: Location) {
        lastLoc = loc
        val kmh = if (loc.hasSpeed()) max(0.0, loc.speed * 3.6) else 0.0
        speedView.text = "${kmh.roundToInt()} km/h"
        val bearing = if (loc.hasBearing() && kmh > 5) loc.bearing.toDouble() else 0.0
        mapView.mapboxMap.setCamera(CameraOptions.Builder().center(Point.fromLngLat(loc.longitude,loc.latitude)).zoom(16.2).bearing(bearing).pitch(42.0).build())
        val direct = if (targetLat.isFinite() && targetLon.isFinite()) haversine(loc.latitude,loc.longitude,targetLat,targetLon) else Double.NaN
        if (direct.isFinite()) subView.text = listOf(payload.optString("parcel", ""), if(direct < 1000) "${direct.roundToInt()} m away" else String.format("%.1f km away",direct/1000)).filter{it.isNotBlank()}.joinToString(" • ")
        updateLimit(loc)
        refreshActionState()
        if (targetLat.isFinite() && targetLon.isFinite() && SystemClock.elapsedRealtime() - lastFetchAt > 45000 && !fetching) fetchLiveRoute(loc)
    }

    private fun refreshActionState() {
        val l = lastLoc
        val near = l != null && targetLat.isFinite() && targetLon.isFinite() && haversine(l.latitude,l.longitude,targetLat,targetLon) <= 350.0 + min(120.0, l.accuracy.toDouble())
        if (kind == "stop") {
            arriveBtn.visibility = View.VISIBLE
            undeliveredBtn.visibility = View.VISIBLE
            arriveBtn.isEnabled = near && !arrived && !breakActive
            arriveBtn.text = if (arrived) "✓ Arrived" else "📍 At location"
            deliveredBtn.isEnabled = near && arrived && !breakActive
            undeliveredBtn.isEnabled = near && arrived && !breakActive
        } else {
            arriveBtn.visibility = View.GONE
            undeliveredBtn.visibility = View.GONE
            deliveredBtn.isEnabled = near && !breakActive
        }
    }

    private fun fetchLiveRoute(loc: Location) {
        fetching = true; lastFetchAt = SystemClock.elapsedRealtime()
        val url = "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/${loc.longitude},${loc.latitude};${targetLon},${targetLat}?steps=true&geometries=geojson&overview=full&annotations=maxspeed,distance,congestion_numeric&depart_at=now&access_token=${java.net.URLEncoder.encode(token,"UTF-8")}" 
        io.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout=9000; conn.readTimeout=12000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val j=JSONObject(text); val route=j.optJSONArray("routes")?.optJSONObject(0) ?: throw Exception("No route")
                val geometry=route.optJSONObject("geometry"); val coords=geometry?.optJSONArray("coordinates")?.let { parsePoints(it) } ?: emptyList()
                val leg=route.optJSONArray("legs")?.optJSONObject(0)
                val ms=leg?.optJSONObject("annotation")?.optJSONArray("maxspeed")
                val speeds=ArrayList<Double?>(); if(ms!=null) for(i in 0 until ms.length()) { val m=ms.optJSONObject(i); var s:Double?=null; if(m!=null && m.has("speed")){s=m.optDouble("speed"); if(m.optString("unit")=="mph")s=s!!*1.60934}; speeds.add(s) }
                val steps=leg?.optJSONArray("steps"); var instruction="Continue"; if(steps!=null&&steps.length()>0){ val st=steps.optJSONObject(0); instruction=st?.optJSONObject("maneuver")?.optString("instruction","") ?: "Continue"; if(instruction.isBlank()) instruction=st?.optString("name","Continue") ?: "Continue" }
                runOnUiThread { if(coords.size>1){routeCoords=coords;drawRoute(coords)};maxSpeeds=speeds;turnView.text=instruction;updateLimit(lastLoc) }
            } catch (_: Exception) {
                runOnUiThread { if (routeCoords.isNotEmpty()) turnView.text = "Offline map • using saved route" else turnView.text = "Offline map • route refresh unavailable" }
            } finally { fetching=false }
        }
    }

    private fun updateLimit(loc: Location?) {
        if(loc==null || routeCoords.size<2 || maxSpeeds.isEmpty()){ limitView.text="—"; return }
        var best=0; var bestD=Double.MAX_VALUE
        val step=max(1,routeCoords.size/600)
        var i=0; while(i<routeCoords.size){ val p=routeCoords[i]; val d=haversine(loc.latitude,loc.longitude,p.latitude(),p.longitude()); if(d<bestD){bestD=d;best=i}; i+=step }
        val idx=min(maxSpeeds.size-1, best)
        val v=maxSpeeds.getOrNull(idx); limitView.text=if(v==null)"—" else v.roundToInt().toString()
    }

    private fun finishAction(action: String) {
        val l=lastLoc
        val data=Intent().putExtra("action",action).putExtra("lat",l?.latitude ?: Double.NaN).putExtra("lon",l?.longitude ?: Double.NaN).putExtra("acc",l?.accuracy?.toDouble() ?: 9999.0)
        setResult(RESULT_OK,data); finish()
    }

    private fun haversine(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double{
        val r=6371000.0; val p1=Math.toRadians(aLat); val p2=Math.toRadians(bLat); val dp=Math.toRadians(bLat-aLat); val dl=Math.toRadians(bLon-aLon)
        val x=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2); return 2*r*atan2(sqrt(x),sqrt(1-x))
    }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).roundToInt()

    override fun onDestroy(){ try{ if(::locationManager.isInitialized) locationManager.removeUpdates(this) }catch(_:Exception){}; io.shutdownNow(); super.onDestroy() }
}
