package org.evsyukov.post

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theIconIsAdaptiveWithEveryLayerForMasksAndThemedIcons() {
        val icon = context.getDrawable(context.applicationInfo.icon)

        assertThat(icon).isInstanceOf(AdaptiveIconDrawable::class.java)
        icon as AdaptiveIconDrawable
        assertThat(icon.background).isNotNull()
        assertThat(icon.foreground).isNotNull()
        assertThat(icon.monochrome).isNotNull()
    }

    // Masks of launchers cut anything outside the central circle of 66dp, and themed icons take the
    // monochrome layer by its alpha only: both layers must be white and stay inside that circle.
    @Test
    fun theForegroundAndMonochromeLayersAreWhiteInsideTheSafeZone() {
        val icon = context.getDrawable(context.applicationInfo.icon) as AdaptiveIconDrawable

        for ((name, layer) in listOf("foreground" to icon.foreground, "monochrome" to icon.monochrome)) {
            val pixels = render(layer, 108 * PX_PER_DP)
            val painted = pixels.filter { it.alpha > 0 }
            val centre = 54.0 * PX_PER_DP
            val farthest = painted.maxOf { hypot(it.x + 0.5 - centre, it.y + 0.5 - centre) } / PX_PER_DP

            assertWithMessage("$name painted pixels").that(painted.size).isGreaterThan(1000)
            assertWithMessage("$name farthest painted dp from the centre").that(farthest)
                .isAtMost(SAFE_ZONE_RADIUS_DP)
            assertWithMessage("$name pixels that are not white").that(notWhite(painted)).isEqualTo(0)
        }
    }

    @Test
    fun theNotificationIconIsAWhite24dpVector() {
        val icon = context.getDrawable(R.drawable.ic_notification)

        assertThat(icon).isInstanceOf(VectorDrawable::class.java)
        val size = (24 * context.resources.displayMetrics.density).roundToInt()
        assertThat(icon!!.intrinsicWidth).isEqualTo(size)
        assertThat(icon.intrinsicHeight).isEqualTo(size)
        val painted = render(icon, 24 * PX_PER_DP).filter { it.alpha > 0 }
        assertWithMessage("painted pixels").that(painted.size).isGreaterThan(500)
        assertWithMessage("pixels that are not white").that(notWhite(painted)).isEqualTo(0)
    }

    // docs/icon/icon.svg is the master the README and listing pictures are rendered from; it must show
    // what the launcher shows. Gradle does not track the SVG as an input of the tests, so a change of the
    // SVG alone is checked on a clean build, as on CI, or with --rerun.
    @Test
    fun theMasterSvgRepeatsTheLayersOfTheIcon() {
        val svg = parse(File("../docs/icon/icon.svg"))
        val foreground = parse(File("src/main/res/drawable/ic_launcher_foreground.xml"))
        val background = parse(File("src/main/res/drawable/ic_launcher_background.xml"))

        val svgShapes = svg.elements("g").single { it.getAttribute("id") == "foreground" }.elements("path").map {
            Shape(
                d = it.getAttribute("d"),
                fill = it.getAttribute("fill").takeUnless { fill -> fill == "none" },
                stroke = it.getAttribute("stroke").ifEmpty { null },
                strokeWidth = it.getAttribute("stroke-width").ifEmpty { null },
                strokeLineCap = it.getAttribute("stroke-linecap").ifEmpty { null },
            )
        }
        val vectorShapes = foreground.elements("path").map {
            Shape(
                d = it.android("pathData")!!,
                fill = it.android("fillColor"),
                stroke = it.android("strokeColor"),
                strokeWidth = it.android("strokeWidth"),
                strokeLineCap = it.android("strokeLineCap"),
            )
        }
        assertThat(svgShapes).isNotEmpty()
        assertThat(svgShapes).containsExactlyElementsIn(vectorShapes).inOrder()

        val svgGradient = svg.elements("linearGradient").single()
        val vectorGradient = background.elements("gradient").single()
        assertThat(listOf("x1", "y1", "x2", "y2").map { svgGradient.getAttribute(it) })
            .containsExactlyElementsIn(listOf("startX", "startY", "endX", "endY").map { vectorGradient.android(it) })
            .inOrder()
        val svgStops = svgGradient.elements("stop").map { it.getAttribute("offset") to color(it.getAttribute("stop-color")) }
        val vectorStops = vectorGradient.elements("item").map { it.android("offset") to color(it.android("color")!!) }
        assertThat(svgStops).containsExactlyElementsIn(vectorStops).inOrder()
        val backdrop = svg.elements("rect").single()
        assertThat(backdrop.getAttribute("fill")).isEqualTo("url(#${svgGradient.getAttribute("id")})")
    }

    private data class Pixel(val x: Int, val y: Int, val color: Int) {
        val alpha get() = Color.alpha(color)

        // Antialiased edges come back unpremultiplied, which may round a channel down by a step or two.
        val isWhite get() = Color.red(color) >= 250 && Color.green(color) >= 250 && Color.blue(color) >= 250
    }

    private data class Shape(
        val d: String,
        val fill: String?,
        val stroke: String?,
        val strokeWidth: String?,
        val strokeLineCap: String?,
    )

    private fun render(drawable: Drawable, size: Int): List<Pixel> {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return (0 until size).flatMap { y -> (0 until size).map { x -> Pixel(x, y, bitmap.getPixel(x, y)) } }
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file).documentElement

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String): String? = getAttributeNS(ANDROID, name).ifEmpty { null }

    // Pixels too transparent to show a colour are left out.
    private fun notWhite(pixels: List<Pixel>): Int = pixels.count { it.alpha >= 64 && !it.isWhite }

    // #FF303F9F in a vector and #303F9F in the SVG are the same opaque colour.
    private fun color(value: String): String =
        value.uppercase().let { if (it.length == 9 && it.startsWith("#FF")) "#" + it.drop(3) else it }

    private companion object {
        const val PX_PER_DP = 4
        const val SAFE_ZONE_RADIUS_DP = 33.0
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
