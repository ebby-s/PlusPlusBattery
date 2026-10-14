package com.dijia1124.plusplusbattery.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dijia1124.plusplusbattery.data.util.Regression
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal time-series chart: x is a timestamp in ms, y a value. Optionally connects
 * the points and/or overlays a linear regression line. Drawn with Compose Canvas so
 * no charting dependency is needed.
 */
@Composable
fun BatteryChart(
    points: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
    connect: Boolean = false,
    regression: Regression? = null,
    yUnit: String = "",
    chartHeight: Dp = 160.dp
) {
    if (points.size < 1) {
        Text(
            text = "Not enough data yet",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(8.dp)
        )
        return
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val pointColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    val xs = points.map { it.first }
    val ys = points.map { it.second }
    var xMin = xs.min()
    var xMax = xs.max()
    if (xMax == xMin) xMax = xMin + 1
    var yMin = ys.min()
    var yMax = ys.max()
    if (yMax == yMin) { yMax = yMin + 1; yMin -= 1 }
    // small vertical padding
    val yPad = (yMax - yMin) * 0.1
    val lo = yMin - yPad
    val hi = yMax + yPad

    Column(modifier = modifier) {
        Text(
            text = "%.1f – %.1f %s".format(hi, lo, yUnit),
            style = MaterialTheme.typography.bodySmall
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            val leftPad = 6.dp.toPx()
            val bottomPad = 6.dp.toPx()
            val plotW = size.width - leftPad
            val plotH = size.height - bottomPad

            fun sx(x: Long): Float =
                leftPad + ((x - xMin).toDouble() / (xMax - xMin).toDouble() * plotW).toFloat()

            fun sy(y: Double): Float =
                plotH - ((y - lo) / (hi - lo) * plotH).toFloat()

            drawLine(axisColor, Offset(leftPad, 0f), Offset(leftPad, plotH), strokeWidth = 2f)
            drawLine(axisColor, Offset(leftPad, plotH), Offset(size.width, plotH), strokeWidth = 2f)

            if (connect && points.size >= 2) {
                val path = Path()
                points.forEachIndexed { i, (x, y) ->
                    val px = sx(x)
                    val py = sy(y)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, lineColor, style = Stroke(width = 3f))
            }

            regression?.let { reg ->
                val y1 = reg.intercept + reg.slopePerMs * xMin
                val y2 = reg.intercept + reg.slopePerMs * xMax
                drawLine(
                    color = lineColor,
                    start = Offset(sx(xMin), sy(y1)),
                    end = Offset(sx(xMax), sy(y2)),
                    strokeWidth = 4f
                )
            }

            points.forEach { (x, y) ->
                drawCircle(pointColor, radius = 6f, center = Offset(sx(x), sy(y)))
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = dateFmt.format(Date(xMin)),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = dateFmt.format(Date(xMax)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}
