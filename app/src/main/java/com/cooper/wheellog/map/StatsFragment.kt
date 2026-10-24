package com.cooper.wheellog.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.cooper.wheellog.R
import com.cooper.wheellog.databinding.ChartFragmentBinding
import com.cooper.wheellog.utils.SomeUtil.getColorEx
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import java.text.SimpleDateFormat
import java.util.*

class StatsFragment: Fragment(), OnChartValueSelectedListener {

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val viewModel: MapViewModel by activityViewModels()
    private lateinit var binding: ChartFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ChartFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initChart(binding.chart1)
        initChart(binding.chart2)

        viewModel.selectedItem.observe(viewLifecycleOwner) { tripData ->
            if (tripData != null) {
                tripDataReceived(binding.chart1, tripData.stats1)
                tripDataReceived(binding.chart2, tripData.stats2)
                binding.chart1.setOnChartValueSelectedListener(this)
                binding.chart2.setOnChartValueSelectedListener(this)
            }
        }
    }

    private val chartAxisValueFormatter: ValueFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            return timeFormatter.format(Date((viewModel.selectedItem.value?.startedAt ?: 0L) + (value * 100).toLong()))
        }
    }

    private fun initChart(chart: LineChart) {
        chart.apply {
            setDrawGridBackground(false)
            description.isEnabled = false
            setHardwareAccelerationEnabled(true)
            setBackgroundColor(context.statsBackgroundColor())
            legend.textColor = context.statsTextColor()
            legend.textSize = 12f
            legend.isWordWrapEnabled = true
            setNoDataText(resources.getString(R.string.no_chart_data))
            setNoDataTextColor(context.statsTextColor())
            axisLeft.apply {
                setDrawGridLines(false)
                textColor = chart.context.statsTextColor()
                textSize = 12f
                axisMinimum = 0f
            }
            axisRight.apply {
                setDrawGridLines(false)
                textColor = chart.context.statsTextColor()
                textSize = 12f
            }
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = chart.context.statsTextColor()
                textSize = 12f
                valueFormatter = chartAxisValueFormatter
            }
        }
    }

    private fun tripDataReceived(chart: LineChart, stats: List<LineDataSet>) {
        chart.apply {
            if (stats.none { it.entryCount > 0 }) { clear(); return }
            data = LineData().apply {
                stats.filter { it.entryCount > 0 }.forEach {
                    addDataSet(it)
                }
            }
            marker = ChartMarkerView(requireContext(), chart.xAxis.valueFormatter, stats).apply { chartView = chart }
            invalidate()
        }
    }

    override fun onValueSelected(e: Entry?, h: Highlight?) {
        if (h == null) return
        for (chart in listOf(binding.chart1, binding.chart2)) {
            val index = chart.data?.dataSets?.indexOfFirst { it.entryCount > 0 } ?: -1
            if (index >= 0) chart.highlightValues(arrayOf(Highlight(h.x, Float.NaN, index)))
        }
    }

    override fun onNothingSelected() {
        binding.chart1.highlightValues(arrayOf())
        binding.chart2.highlightValues(arrayOf())
    }
}
