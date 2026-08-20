package com.paul.droproute.mapboxbeta

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.mapbox.bindgen.Value
import com.mapbox.common.MapboxOptions
import com.mapbox.common.NetworkRestriction
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileStore
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.Style
import com.mapbox.maps.StylePackLoadOptions
import com.mapbox.maps.TileStoreUsageMode
import com.mapbox.maps.TilesetDescriptorOptions
import com.mapbox.maps.mapsOptions
import kotlin.math.roundToInt

class OfflineMapActivity : Activity() {
    companion object { const val REGION_ID="droproute-ireland-ni-v1" }
    private lateinit var mapView: MapView
    private lateinit var tileStore: TileStore
    private lateinit var offline: OfflineManager
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var mainButton: Button
    private lateinit var deleteButton: Button
    private var downloading=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token=intent.getStringExtra("token") ?: ""
        if(!token.startsWith("pk.")){ Toast.makeText(this,"Add your Mapbox public token in DropRoute Settings first.",Toast.LENGTH_LONG).show();finish();return }
        MapboxOptions.accessToken=token
        tileStore=MapboxOptions.mapsOptions.tileStore ?: TileStore.create().also { MapboxOptions.mapsOptions.tileStore=it }
        MapboxOptions.mapsOptions.tileStoreUsageMode=TileStoreUsageMode.READ_ONLY
        offline=OfflineManager()
        buildUi(); refreshStatus()
    }

    private fun buildUi(){
        val root=FrameLayout(this)
        mapView=MapView(this)
        root.addView(mapView,FrameLayout.LayoutParams(-1,-1))
        mapView.mapboxMap.loadStyle(Style.STANDARD)
        mapView.mapboxMap.setCamera(CameraOptions.Builder().center(Point.fromLngLat(-8.0,53.6)).zoom(6.4).build())
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(14));setBackgroundColor(Color.argb(245,255,255,255))}
        val title=TextView(this).apply{text="Ireland + Northern Ireland";textSize=20f;setTextColor(Color.BLACK);setTypeface(typeface,1)}
        status=TextView(this).apply{text="Checking offline map…";textSize=12f;setTextColor(Color.DKGRAY);setPadding(0,dp(4),0,dp(8))}
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=0}
        mainButton=Button(this).apply{text="Download";isAllCaps=false;setOnClickListener{downloadOrUpdate()}}
        deleteButton=Button(this).apply{text="Delete offline map";isAllCaps=false;setOnClickListener{deletePack()}}
        val close=Button(this).apply{text="Back to DropRoute";isAllCaps=false;setOnClickListener{finish()}}
        panel.addView(title);panel.addView(status);panel.addView(progress,LinearLayout.LayoutParams(-1,dp(12)));panel.addView(mainButton);panel.addView(deleteButton);panel.addView(close)
        root.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
    }

    private fun regionPolygon(): Polygon {
        val ring=listOf(
            Point.fromLngLat(-11.15,51.15), Point.fromLngLat(-5.05,51.15), Point.fromLngLat(-5.05,55.65),
            Point.fromLngLat(-11.15,55.65), Point.fromLngLat(-11.15,51.15)
        )
        return Polygon.fromLngLats(listOf(ring))
    }

    private fun refreshStatus(){
        tileStore.getAllTileRegions { expected ->
            runOnUiThread {
                val found=expected.value?.any{it.id==REGION_ID}==true
                if(!downloading){status.text=if(found)"Downloaded ✓ • Mapbox will use this automatically when signal drops." else "Not downloaded • whole-island driving map, zoom 5–13";mainButton.text=if(found)"Update" else "Download";deleteButton.isEnabled=found;progress.progress=if(found)100 else 0}
            }
        }
    }

    private fun downloadOrUpdate(){
        if(downloading)return;downloading=true;mainButton.isEnabled=false;deleteButton.isEnabled=false;status.text="Preparing Mapbox offline map…";progress.progress=0
        offline.loadStylePack(Style.STANDARD, StylePackLoadOptions.Builder().metadata(Value("DropRoute Ireland + NI")).build(),
            { p -> runOnUiThread{ val total=p.requiredResourceCount.coerceAtLeast(1); val pc=(100.0*p.completedResourceCount/total).roundToInt().coerceIn(0,100); progress.progress=maxOf(progress.progress,pc/4);status.text="Downloading map style… $pc%" } },
            { styleResult ->
                if(styleResult.error!=null){runOnUiThread{downloadFailed("Style: ${styleResult.error}")};return@loadStylePack}
                startTileDownload()
            })
    }

    private fun startTileDownload(){
        val descriptor=offline.createTilesetDescriptor(TilesetDescriptorOptions.Builder().styleURI(Style.STANDARD).pixelRatio(resources.displayMetrics.density).minZoom(5).maxZoom(13).build())
        val opts=TileRegionLoadOptions.Builder().geometry(regionPolygon()).descriptors(listOf(descriptor)).metadata(Value("Ireland + Northern Ireland")).acceptExpired(false).networkRestriction(NetworkRestriction.NONE).build()
        tileStore.loadTileRegion(REGION_ID,opts,
            { p -> runOnUiThread{ val total=p.requiredResourceCount.coerceAtLeast(1);val pc=(100.0*p.completedResourceCount/total).roundToInt().coerceIn(0,100);progress.progress=25+(pc*75/100);status.text="Downloading Ireland + Northern Ireland… $pc%" } },
            { result -> runOnUiThread{downloading=false;mainButton.isEnabled=true;if(result.error!=null)downloadFailed("Map: ${result.error}") else {progress.progress=100;status.text="Offline map ready ✓";refreshStatus()}} })
    }

    private fun downloadFailed(msg:String){downloading=false;mainButton.isEnabled=true;status.text="Download failed: $msg";refreshStatus()}

    private fun deletePack(){
        tileStore.removeTileRegion(REGION_ID)
        offline.removeStylePack(Style.STANDARD)
        progress.progress=0;status.text="Offline map removed";mainButton.text="Download";mainButton.isEnabled=true;deleteButton.isEnabled=false
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
}
