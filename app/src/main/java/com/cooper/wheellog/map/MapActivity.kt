package com.cooper.wheellog.map

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2
import com.cooper.wheellog.BuildConfig
import com.cooper.wheellog.LocaleManager
import com.cooper.wheellog.R
import com.cooper.wheellog.data.TripParser
import com.cooper.wheellog.databinding.ActivityMapBinding
import com.cooper.wheellog.utils.SomeUtil.getColorEx
import com.cooper.wheellog.AppConfig
import com.cooper.wheellog.LoggingService
import com.cooper.wheellog.data.NativeCsv
import com.cooper.wheellog.utils.ThemeManager
import org.koin.android.ext.android.inject
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import java.io.*

class MapActivity : AppCompatActivity() {
    private val appConfig: AppConfig by inject()

    private val mapAdapter by lazy { MapActivityAdapter(this) }
    private lateinit var viewPager: ViewPager2
    private lateinit var tabs: TabLayout
    private val viewModel: MapViewModel by viewModels()

    private val backgroundScope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.setLocale(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.theme)
        super.onCreate(savedInstanceState)
        val context = applicationContext

        // load/initialize the osmdroid configuration, this can be done
        Configuration.getInstance().apply {
            load(context, PreferenceManager.getDefaultSharedPreferences(context))
            userAgentValue = BuildConfig.APPLICATION_ID

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                osmdroidBasePath = File(context.filesDir, "osmdroid").apply {
                    mkdirs()
                }
                osmdroidTileCache = File(osmdroidBasePath, "titles").apply {
                    mkdirs()
                }
            }
        }

        val binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewPager = binding.pager
        viewPager.apply {
            offscreenPageLimit = 10
            isUserInputEnabled = false
            adapter = mapAdapter
        }

        val tabNames: Array<String> = arrayOf(
            getString(R.string.map_map_tab_name),
            getString(R.string.map_statistics_tab_name),
            getString(R.string.map_values_tab_name)
        )

        tabs = binding.tabs
        binding.root.setBackgroundColor(getColorEx(R.color.background))
        styleTripTabs(this, tabs)
        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = tabNames[position]
        }.attach()

        val extras = intent.extras
            ?: kotlin.run {
                this.finish()
                return
            }

        backgroundScope.launch {
            do {
                val tripData = parseFile(extras)
                if (isDestroyed) return@launch
                withContext(Dispatchers.Main) { viewModel.selectItem(tripData) }
                if (LoggingService.currentLogFileName != tripData.fileName) break
                delay(3_000)
            } while (isActive)
        }
    }

    override fun onDestroy() {
        backgroundScope.cancel()
        super.onDestroy()
    }

    private fun parseFile(extras: Bundle): TripData {
        val fileName = extras.getString("file_name", extras.getString("title", "undefined"))
        val title = getSharedPreferences("trip_titles", MODE_PRIVATE).getString(fileName, extras.getString("title", fileName))!!
        val path = extras.getString("path", "undefined")
        val uri = Uri.parse(extras.getString("uri", "undefined"))
        val tripData = TripData(title)
        val parsed = TripParser.parseFile(applicationContext, fileName, path, uri)
        val list = parsed.first
        val db = parsed.second
        tripData.errorMessage = TripParser.lastError
        if (tripData.errorMessage != "") {
            return tripData
        }

        val geoLine = list.filter {
            it.latitude != 0.0 && it.longitude != 0.0
        }.map { tick ->
            LogGeoPoint(tick.latitude, tick.longitude, tick.altitude).also {
                it.speed = tick.speed
                it.voltage = tick.voltage
                it.battery = tick.batteryLevel
                it.distance = tick.distance
                it.temperature = tick.temperature
                it.timeString = tick.timeString
            }
        }

        val chart1DataSets = listOf(
            LineDataSet(
                list.filter { it.wheelDataAvailable }.map { Entry(it.timePlusDayOfWeek, it.batteryLevel.toFloat()) },
                getString(R.string.battery)
            ).apply {
                color = statSeriesColor(R.color.stats_battery)
                setDrawCircles(false)
                axisDependency = YAxis.AxisDependency.LEFT
                lineWidth = 2f
            },
            LineDataSet(
                list.map { Entry(it.timePlusDayOfWeek, it.speed.toFloat()) },
                getString(R.string.speed)
            ).apply {
                color = statSeriesColor(R.color.stats_speed)
                setDrawCircles(false)
                axisDependency = YAxis.AxisDependency.LEFT
                lineWidth = 2f
            },
            LineDataSet(
                list.filter { it.wheelDataAvailable }.map { Entry(it.timePlusDayOfWeek, it.temperature.toFloat()) },
                getString(R.string.temperature)
            ).apply {
                color = statSeriesColor(R.color.stats_temp)
                setDrawCircles(false)
                axisDependency = YAxis.AxisDependency.RIGHT
                lineWidth = 2f
            })
        val chart2DataSets = listOf(
            LineDataSet(
                list.filter { it.pwmAvailable }.map { Entry(it.timePlusDayOfWeek, it.pwm.toFloat()) },
                getString(R.string.pwm)
            ).apply {
                color = statSeriesColor(R.color.stats_pwm)
                setDrawCircles(false)
                axisDependency = YAxis.AxisDependency.LEFT
                lineWidth = 2f
            },
            LineDataSet(
                list.filter { it.wheelDataAvailable }.map { Entry(it.timePlusDayOfWeek, it.voltage.toFloat()) },
                getString(R.string.voltage)
            ).apply {
                color = statSeriesColor(R.color.stats_voltage)
                setDrawCircles(false)
                axisDependency = YAxis.AxisDependency.LEFT
                lineWidth = 2f
            },
            LineDataSet(
                list.filter { it.wheelDataAvailable }.map { Entry(it.timePlusDayOfWeek, it.power.toFloat()) },
                getString(R.string.power)
            ).apply {
                color = statSeriesColor(R.color.stats_power)
                setDrawCircles(false)
                axisDependency = YAxis.AxisDependency.RIGHT
                lineWidth = 2f
            },
            LineDataSet(
                list.filter { it.wheelDataAvailable }.map { Entry(it.timePlusDayOfWeek, it.current.toFloat()) },
                getString(R.string.current)
            ).apply {
                color = statSeriesColor(R.color.stats_current)
                setDrawCircles(false)
                axisDependency = YAxis.AxisDependency.LEFT
                lineWidth = 2f
            })

        return tripData.copy(
            geoLine = geoLine,
            stats1 = chart1DataSets.filter { it.entryCount > 0 },
            stats2 = chart2DataSets.filter { it.entryCount > 0 },
            tripDb = db,
            values = com.cooper.wheellog.data.TripValues.calculate(list),
            routePoints = NativeCsv.points(list),
            startedAt = list.firstOrNull()?.timestampMillis ?: 0,
            endedAt = list.lastOrNull()?.timestampMillis,
            distanceMeters = db?.distance?.toDouble() ?: com.cooper.wheellog.track.summarizeTrack(NativeCsv.points(list)).distanceMeters,
            fileName = fileName,
        )
    }
}
