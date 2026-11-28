package com.lastwave.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlassShapeSafetyTest {

    @Test
    fun rectangleShape_mapsToCornerBasedShape() {
        val safe = lensSafeGlassShape(RectangleShape)

        assertThat(safe).isInstanceOf(CornerBasedShape::class.java)
        assertThat(safe).isEqualTo(RoundedCornerShape(0.dp))
    }

    @Test
    fun supportedShapes_passThroughUnchanged() {
        val circle: Shape = CircleShape
        val rounded: Shape = RoundedCornerShape(24.dp)

        assertThat(lensSafeGlassShape(circle)).isSameInstanceAs(circle)
        assertThat(lensSafeGlassShape(rounded)).isSameInstanceAs(rounded)
    }

    @Test
    fun domeSlopeAt_isC1ContinuousAtEntryAndInnerBounds() {
        assertThat(domeSlopeAt(0f, 2.6f)).isEqualTo(0f)
        assertThat(domeSlopeAt(1f, 2.6f)).isEqualTo(0f)

        val outerEntry = domeSlopeAt(0.02f, 2.6f)
        val lipPeak = domeSlopeAt(0.14f, 2.6f)
        val midDome = domeSlopeAt(0.50f, 2.6f)
        assertThat(outerEntry).isGreaterThan(0f)
        assertThat(outerEntry).isLessThan(lipPeak)
        assertThat(midDome).isGreaterThan(0f)
        assertThat(midDome).isLessThan(lipPeak)
    }

    @Test
    fun opticsPresets_matchWalkthroughConstants() {
        val floating = LiquidGlassOptics.Floating
        assertThat(floating.refractionDp).isEqualTo(20f)
        assertThat(floating.bezel).isEqualTo(0.65f)
        assertThat(floating.curvature).isEqualTo(2.6f)
        assertThat(floating.chroma).isEqualTo(0.28f)
        assertThat(floating.blurDp).isEqualTo(4f)
        assertThat(floating.saturation).isEqualTo(1.6f)

        val nav = LiquidGlassOptics.NavPill
        assertThat(nav.refractionDp).isEqualTo(11f)
        assertThat(nav.bezel).isEqualTo(0.62f)
        assertThat(nav.curvature).isEqualTo(2.6f)
        assertThat(nav.chroma).isEqualTo(0.24f)
        assertThat(nav.blurDp).isEqualTo(4f)
        assertThat(nav.saturation).isEqualTo(1.6f)
    }

    @Test
    fun resolveCornerRadiiPx_clampsToHalfMinDimensionAndRespectsRtl() {
        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 8.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
        val density = Density(2f)
        val size = Size(100f, 60f) // maxRadius = 30px; topStart 24dp = 48px -> clamped to 30px

        val ltr = resolveCornerRadiiPx(shape, size, LayoutDirection.Ltr, density)
        assertThat(ltr).isEqualTo(floatArrayOf(30f, 16f, 30f, 8f))

        val rtl = resolveCornerRadiiPx(shape, size, LayoutDirection.Rtl, density)
        assertThat(rtl).isEqualTo(floatArrayOf(16f, 30f, 8f, 30f))
    }
}
