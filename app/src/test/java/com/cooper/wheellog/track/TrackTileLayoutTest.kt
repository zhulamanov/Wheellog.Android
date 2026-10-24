package com.cooper.wheellog.track

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.cooper.wheellog.R
import com.cooper.wheellog.databinding.ListTripItemBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TrackTileLayoutTest {
    @Test fun `trip statistics and accessible menu remain within narrow wide and enlarged text tiles`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (theme in listOf(R.style.OriginalTheme, R.style.ContrastTheme, R.style.InfernoTheme)) {
            for (scale in listOf(1f, 1.5f)) {
                val configured = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = scale })
                val context = ContextThemeWrapper(configured, theme)
                for (widthDp in listOf(320, 600, 1000)) {
                    val tile = ListTripItemBinding.inflate(LayoutInflater.from(context))
                    tile.name.text = "Длинное пользовательское название вечерней поездки в парк"
                    tile.description.text = "123.45 км"
                    tile.description2.text = "15:40:00"
                    val density = context.resources.displayMetrics.density
                    val width = (widthDp * density).toInt()
                    tile.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    tile.root.layout(0, 0, tile.root.measuredWidth, tile.root.measuredHeight)
                    for (view in listOf(tile.name, tile.thumbnail, tile.description, tile.description2, tile.popupButton)) {
                        val owner = view.parent as View
                        assertTrue("${view.id} extends outside width $widthDp at font $scale", view.left >= 0 && view.right <= owner.width)
                        assertTrue("${view.id} extends outside height", view.top >= 0 && view.bottom <= owner.height)
                        if (view is TextView && view.lineCount > 0) {
                            assertTrue("text is vertically clipped", view.layout.getLineBottom(view.lineCount - 1) <= view.height - view.totalPaddingTop - view.totalPaddingBottom)
                        }
                    }
                    assertTrue(tile.popupButton.width >= 48 * density - 1)
                    assertTrue(tile.thumbnail.width > 80 * density)
                    assertTrue("thumbnail height=${tile.thumbnail.height}, minimum=${150 * density}, width=$widthDp font=$scale", tile.thumbnail.height >= 150 * density - 1)
                    assertNull(tile.root.findViewById<View>(base.resources.getIdentifier("timeWindow", "id", base.packageName)))
                    assertNull(tile.root.findViewById<View>(base.resources.getIdentifier("speedSummary", "id", base.packageName)))
                }
            }
        }
    }
}
