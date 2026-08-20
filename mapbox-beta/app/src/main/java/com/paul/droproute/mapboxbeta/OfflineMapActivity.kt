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
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.common.NetworkRestriction
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileStore
import com.mapbox.common.TilesetDescriptor
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.Style
import com.mapbox.maps.StylePackLoadOptions
import com.mapbox.maps.TileStoreUsageMode
import com.mapbox.maps.TilesetDescriptorOptions
import com.mapbox.maps.mapsOptions
import kotlin.math.roundToInt

class OfflineMapActivity : Activity() {
    companion object {
        const val LEGACY_REGION_ID = "droproute-ireland-ni-v1"
        const val REGION_PREFIX = "droproute-ireland-ni-v2-"
    }

    private data class RegionSpec(
        val id: String,
        val label: String,
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double
    )

    private lateinit var tileStore: TileStore
    private lateinit var offline: OfflineManager
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var mainButton: Button
    private lateinit var deleteButton: Button

    private val cancelables = mutableListOf<Cancelable>()
    private var downloading = false
    private var styleDone = false
    private var chunksDone = false
    private var stylePc = 0
    private var chunkIndex = 0
    private var chunkPc = 0

    private val regions = listOf(
        RegionSpec("${REGION_PREFIX}sw", "South West", -11.15, 51.15, -8.00, 53.50),
        RegionSpec("${REGION_PREFIX}se", "South East", -8.00, 51.15, -5.00, 53.50),
        RegionSpec("${REGION_PREFIX}nw", "North West", -11.15, 53.50, -8.00, 55.70),
        RegionSpec("${REGION_PREFIX}ne", "North East", -8.00, 53.50, -5.00, 55.70)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("token") ?: ""
        if (!token.startsWith("pk.")) {
            Toast.makeText(this, "Add your Mapbox public token in DropRoute Settings first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            MapboxOptions.accessToken = token
            MapboxOptions.mapsOptions.tileStoreUsageMode = TileStoreUsageMode.READ_AND_UPDATE
            tileStore = MapboxOptions.mapsOptions.tileStore ?: TileStore.create().also {
                MapboxOptions.mapsOptions.tileStore = it
            }
            offline = OfflineManager()
            buildUi()
            refreshStatus()
        } catch (t: Throwable) {
            Toast.makeText(this, "Offline maps could not start: ${safeMessage(t)}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(239, 246, 255)) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Ireland + Northern Ireland"
            textSize = 22f
            setTextColor(Color.BLACK)
            setTypeface(typeface, 1)
        }
        val info = TextView(this).apply {
            text = "One offline driving map for the whole island. DropRoute downloads it as 4 smaller internal regions so a large download cannot overwhelm the map engine."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(12))
        }
        status = TextView(this).apply {
            text = "Checking offline map…"
            textSize = 13f
            setTextColor(Color.rgb(30, 64, 175))
            setPadding(0, 0, 0, dp(10))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val detail = TextView(this).apply {
            text = "Whole-island pack • driving detail • zoom 5–12\nKeep DropRoute open while the first download completes."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        mainButton = Button(this).apply {
            text = "Download"
            isAllCaps = false
            setOnClickListener { downloadOrUpdate() }
        }
        deleteButton = Button(this).apply {
            text = "Delete offline map"
            isAllCaps = false
            setOnClickListener { deletePack() }
        }
        val close = Button(this).apply {
            text = "Back to DropRoute"
            isAllCaps = false
            setOnClickListener { finish() }
        }

        panel.addView(title)
        panel.addView(info)
        panel.addView(status)
        panel.addView(progress, LinearLayout.LayoutParams(-1, dp(14)))
        panel.addView(detail)
        panel.addView(mainButton)
        panel.addView(deleteButton)
        panel.addView(close)
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
    }

    private fun polygon(r: RegionSpec): Polygon {
        val ring = listOf(
            Point.fromLngLat(r.west, r.south),
            Point.fromLngLat(r.east, r.south),
            Point.fromLngLat(r.east, r.north),
            Point.fromLngLat(r.west, r.north),
            Point.fromLngLat(r.west, r.south)
        )
        return Polygon.fromLngLats(listOf(ring))
    }

    private fun refreshStatus() {
        if (!::tileStore.isInitialized) return
        tileStore.getAllTileRegions { expected ->
            runOnUiThread {
                val ids = expected.value?.map { it.id }?.toSet().orEmpty()
                val count = regions.count { ids.contains(it.id) }
                if (!downloading) {
                    when {
                        count == regions.size -> {
                            status.text = "Downloaded ✓ • ready for offline map use"
                            mainButton.text = "Update"
                            deleteButton.isEnabled = true
                            progress.progress = 100
                        }
                        count > 0 -> {
                            status.text = "Partial download ($count/${regions.size}) • tap Resume"
                            mainButton.text = "Resume"
                            deleteButton.isEnabled = true
                            progress.progress = (count * 100 / regions.size)
                        }
                        else -> {
                            status.text = "Not downloaded"
                            mainButton.text = "Download"
                            deleteButton.isEnabled = false
                            progress.progress = 0
                        }
                    }
                }
            }
        }
    }

    private fun downloadOrUpdate() {
        if (downloading) return
        try {
            downloading = true
            styleDone = false
            chunksDone = false
            stylePc = 0
            chunkIndex = 0
            chunkPc = 0
            cancelables.forEach { it.cancel() }
            cancelables.clear()

            mainButton.isEnabled = false
            deleteButton.isEnabled = false
            status.text = "Preparing Mapbox offline data…"
            progress.progress = 0

            tileStore.removeTileRegion(LEGACY_REGION_ID)

            val descriptor = offline.createTilesetDescriptor(
                TilesetDescriptorOptions.Builder()
                    .styleURI(Style.STANDARD)
                    .pixelRatio(resources.displayMetrics.density.coerceAtLeast(2f))
                    .minZoom(5)
                    .maxZoom(12)
                    .build()
            )

            val styleCancelable = offline.loadStylePack(
                Style.STANDARD,
                StylePackLoadOptions.Builder()
                    .glyphsRasterizationMode(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY)
                    .metadata(Value("DropRoute Ireland + Northern Ireland"))
                    .acceptExpired(false)
                    .build(),
                { p ->
                    runOnUiThread {
                        val total = p.requiredResourceCount.coerceAtLeast(1)
                        stylePc = (100.0 * p.completedResourceCount / total).roundToInt().coerceIn(0, 100)
                        renderProgress("Downloading map style… $stylePc%")
                    }
                },
                { result ->
                    runOnUiThread {
                        if (result.error != null) {
                            downloadFailed("Style download: ${result.error}")
                        } else {
                            stylePc = 100
                            styleDone = true
                            renderProgress("Map style ready • downloading road data…")
                            finishIfReady()
                        }
                    }
                }
            )
            cancelables.add(styleCancelable)

            startChunkDownload(descriptor, 0)
        } catch (t: Throwable) {
            downloadFailed("Could not start: ${safeMessage(t)}")
        }
    }

    private fun startChunkDownload(descriptor: TilesetDescriptor, index: Int) {
        if (!downloading) return
        if (index >= regions.size) {
            chunksDone = true
            chunkIndex = regions.size
            chunkPc = 100
            finishIfReady()
            return
        }

        chunkIndex = index
        chunkPc = 0
        val r = regions[index]
        renderProgress("Downloading ${r.label} (${index + 1}/${regions.size})…")

        try {
            val opts = TileRegionLoadOptions.Builder()
                .geometry(polygon(r))
                .descriptors(listOf(descriptor))
                .metadata(Value("Ireland + Northern Ireland • ${r.label}"))
                .acceptExpired(false)
                .networkRestriction(NetworkRestriction.NONE)
                .build()

            val c = tileStore.loadTileRegion(
                r.id,
                opts,
                { p ->
                    runOnUiThread {
                        val total = p.requiredResourceCount.coerceAtLeast(1)
                        chunkPc = (100.0 * p.completedResourceCount / total).roundToInt().coerceIn(0, 100)
                        renderProgress("Downloading ${r.label} (${index + 1}/${regions.size})… $chunkPc%")
                    }
                },
                { result ->
                    runOnUiThread {
                        if (result.error != null) {
                            downloadFailed("${r.label}: ${result.error}")
                        } else {
                            chunkPc = 100
                            startChunkDownload(descriptor, index + 1)
                        }
                    }
                }
            )
            cancelables.add(c)
        } catch (t: Throwable) {
            downloadFailed("${r.label}: ${safeMessage(t)}")
        }
    }

    private fun renderProgress(message: String) {
        if (!downloading) return
        val regionOverall = when {
            regions.isEmpty() -> 100.0
            chunkIndex >= regions.size -> 100.0
            else -> ((chunkIndex * 100.0) + chunkPc) / regions.size
        }
        val overall = (stylePc * 0.20 + regionOverall * 0.80).roundToInt().coerceIn(0, 100)
        progress.progress = overall
        status.text = message
    }

    private fun finishIfReady() {
        if (!styleDone || !chunksDone || !downloading) return
        downloading = false
        cancelables.clear()
        progress.progress = 100
        status.text = "Offline map ready ✓"
        mainButton.isEnabled = true
        mainButton.text = "Update"
        deleteButton.isEnabled = true
    }

    private fun downloadFailed(msg: String) {
        if (!::status.isInitialized) return
        downloading = false
        cancelables.forEach {
            try { it.cancel() } catch (_: Throwable) { }
        }
        cancelables.clear()
        mainButton.isEnabled = true
        mainButton.text = "Resume"
        deleteButton.isEnabled = true
        status.text = "Download stopped • $msg"
        Toast.makeText(this, "Offline map stopped safely. You can tap Resume.", Toast.LENGTH_LONG).show()
    }

    private fun deletePack() {
        try {
            downloading = false
            cancelables.forEach { it.cancel() }
            cancelables.clear()
            tileStore.removeTileRegion(LEGACY_REGION_ID)
            regions.forEach { tileStore.removeTileRegion(it.id) }
            offline.removeStylePack(Style.STANDARD)
            progress.progress = 0
            status.text = "Offline map removed"
            mainButton.text = "Download"
            mainButton.isEnabled = true
            deleteButton.isEnabled = false
        } catch (t: Throwable) {
            status.text = "Could not remove map: ${safeMessage(t)}"
        }
    }

    private fun safeMessage(t: Throwable): String = t.message?.take(180) ?: t.javaClass.simpleName
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        if (downloading) {
            cancelables.forEach {
                try { it.cancel() } catch (_: Throwable) { }
            }
            cancelables.clear()
        }
        super.onDestroy()
    }
}
