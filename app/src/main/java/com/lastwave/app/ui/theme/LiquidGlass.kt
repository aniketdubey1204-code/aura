package com.lastwave.app.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangularShape
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import com.kyant.backdrop.backdrops.layerBackdrop as nativeBackdrop

/** Shared opt-in flag for Settings > Experimental > Liquid Glass. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

/** Shared toggle for classic Material 3 full-width bottom navigation bar. */
val LocalMd3NavBar = staticCompositionLocalOf { false }

/** Background-only source for surfaces inside the captured scrolling content. */
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Separate source for overlays; kept for call-site compat, same type as backdrop. */
val LocalLiquidGlassOverlayBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Content-brightness hint, kept for compat (native recipe does not need it). */
val LocalLiquidGlassContentBrightness = compositionLocalOf { 0f }

/** Typealiases for clean, unified Backdrop types (mirrors PlatformBackdrop). */
typealias LayerBackdrop = LayerBackdrop
typealias Backdrop = Backdrop

/** Factory matching Kyant0 Backdrop's official API.
 *
 *  NOTE: the Compose compiler forbids try/catch (and runCatching) around
 *  @Composable invocations, so this stays a direct passthrough. First-frame
 *  safety comes from the [isDeviceGlassCapable] gate at every call site,
 *  and any failure still lands in the crash-guard log + startup trail. */
@Composable
fun rememberLayerBackdrop(
    onDraw: androidx.compose.ui.graphics.drawscope.ContentDrawScope.() -> Unit = { drawContent() },
): LayerBackdrop? = com.kyant.backdrop.backdrops.rememberLayerBackdrop(onDraw = onDraw)

/** Marks a composable as the source layer that sibling glass surfaces refract. */
fun Modifier.layerBackdropCompat(backdrop: LayerBackdrop): Modifier = this.nativeBackdrop(backdrop)

/** Remember a backdrop that draws a flat color + content. */
@Composable
fun rememberBackdrop(color: Color): LayerBackdrop? =
    rememberLayerBackdrop {
        drawRect(color)
        drawContent()
    }

/**
 * Continuous-curvature squircle (G2 superellipse) implementing [CornerBasedShape].
 * Kept from LastWave so DockShape stays a CornerBasedShape (lens-compatible).
 */
class SquircleShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(radius: Dp) : this(
        CornerSize(radius),
        CornerSize(radius),
        CornerSize(radius),
        CornerSize(radius),
    )

    constructor(percent: Int = 50) : this(
        CornerSize(percent),
        CornerSize(percent),
        CornerSize(percent),
        CornerSize(percent),
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = SquircleShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect.Zero)

        val isLtr = layoutDirection == LayoutDirection.Ltr
        val tl = if (isLtr) topStart else topEnd
        val tr = if (isLtr) topEnd else topStart
        val br = if (isLtr) bottomEnd else bottomStart
        val bl = if (isLtr) bottomStart else bottomEnd

        if (tl <= 0f && tr <= 0f && br <= 0f && bl <= 0f) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        }

        val path = createSquirclePath(w, h, tl, tr, br, bl)
        return Outline.Generic(path)
    }
}

fun createSquirclePath(
    w: Float,
    h: Float,
    tlRadius: Float,
    trRadius: Float,
    brRadius: Float,
    blRadius: Float,
): Path {
    val path = Path()
    val maxRadius = minOf(w, h) / 2f
    val tl = tlRadius.coerceIn(0f, maxRadius)
    val tr = trRadius.coerceIn(0f, maxRadius)
    val br = brRadius.coerceIn(0f, maxRadius)
    val bl = blRadius.coerceIn(0f, maxRadius)

    val k = 1.528665f
    var lTl = tl * k
    var lTr = tr * k
    var lBr = br * k
    var lBl = bl * k

    val maxWTop = lTl + lTr
    if (maxWTop > w && maxWTop > 0f) {
        val scale = w / maxWTop
        lTl *= scale
        lTr *= scale
    }
    val maxWBottom = lBl + lBr
    if (maxWBottom > w && maxWBottom > 0f) {
        val scale = w / maxWBottom
        lBl *= scale
        lBr *= scale
    }
    val maxHLeft = lTl + lBl
    if (maxHLeft > h && maxHLeft > 0f) {
        val scale = h / maxHLeft
        lTl *= scale
        lBl *= scale
    }
    val maxHRight = lTr + lBr
    if (maxHRight > h && maxHRight > 0f) {
        val scale = h / maxHRight
        lTr *= scale
        lBr *= scale
    }

    path.moveTo(lTl, 0f)
    path.lineTo(w - lTr, 0f)
    if (lTr > 0.001f) {
        path.cubicTo(
            w - lTr * (1f - 0.712053f), 0f,
            w - lTr * (1f - 0.566789f), lTr * 0.030183f,
            w - lTr * (1f - 0.455953f), lTr * 0.087377f,
        )
        path.cubicTo(
            w - lTr * (1f - 0.345118f), lTr * 0.144571f,
            w - lTr * (1f - 0.242846f), lTr * 0.242846f,
            w - lTr * (1f - 0.144571f), lTr * 0.345118f,
        )
        path.cubicTo(
            w - lTr * (1f - 0.087377f), lTr * 0.455953f,
            w - lTr * 0.030183f, lTr * (1f - 0.566789f),
            w, lTr * (1f - 0.712053f),
        )
        path.lineTo(w, lTr)
    } else {
        path.lineTo(w, 0f)
        path.lineTo(w, lTr)
    }
    path.lineTo(w, h - lBr)
    if (lBr > 0.001f) {
        path.cubicTo(
            w, h - lBr * (1f - 0.712053f),
            w - lBr * 0.030183f, h - lBr * (1f - 0.566789f),
            w - lBr * 0.087377f, h - lBr * (1f - 0.455953f),
        )
        path.cubicTo(
            w - lBr * 0.144571f, h - lBr * (1f - 0.345118f),
            w - lBr * 0.242846f, h - lBr * (1f - 0.242846f),
            w - lBr * 0.345118f, h - lBr * (1f - 0.144571f),
        )
        path.cubicTo(
            w - lBr * 0.455953f, h - lBr * (1f - 0.087377f),
            w - lBr * (1f - 0.566789f), h - lBr * 0.030183f,
            w - lBr * (1f - 0.712053f), h,
        )
        path.lineTo(w - lBr, h)
    } else {
        path.lineTo(w, h)
        path.lineTo(w - lBr, h)
    }
    path.lineTo(lBl, h)
    if (lBl > 0.001f) {
        path.cubicTo(
            lBl * (1f - 0.712053f), h,
            lBl * (1f - 0.566789f), h - lBl * 0.030183f,
            lBl * (1f - 0.455953f), h - lBl * 0.087377f,
        )
        path.cubicTo(
            lBl * (1f - 0.345118f), h - lBl * 0.144571f,
            lBl * (1f - 0.242846f), h - lBl * 0.242846f,
            lBl * (1f - 0.144571f), h - lBl * 0.345118f,
        )
        path.cubicTo(
            lBl * (1f - 0.087377f), h - lBl * 0.455953f,
            lBl * 0.030183f, h - lBl * (1f - 0.566789f),
            0f, h - lBl * (1f - 0.712053f),
        )
        path.lineTo(0f, h - lBl)
    } else {
        path.lineTo(0f, h)
        path.lineTo(0f, h - lBl)
    }
    path.lineTo(0f, lTl)
    if (lTl > 0.001f) {
        path.cubicTo(
            0f, lTl * (1f - 0.712053f),
            lTl * 0.030183f, lTl * (1f - 0.566789f),
            lTl * 0.087377f, lTl * (1f - 0.455953f),
        )
        path.cubicTo(
            lTl * 0.144571f, lTl * (1f - 0.345118f),
            lTl * 0.242846f, lTl * (1f - 0.242846f),
            lTl * 0.345118f, lTl * (1f - 0.144571f),
        )
        path.cubicTo(
            lTl * 0.455953f, lTl * (1f - 0.087377f),
            lTl * (1f - 0.566789f), lTl * 0.030183f,
            lTl * (1f - 0.712053f), 0f,
        )
        path.lineTo(lTl, 0f)
    } else {
        path.lineTo(0f, 0f)
        path.lineTo(lTl, 0f)
    }
    path.close()
    return path
}

/** Keeps glass inside Material's visual bounds while retaining its outer touch target. */
@Composable
fun LiquidGlassSurface(
    onClick: () -> Unit,
    glassModifier: Modifier,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = color,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        interactionSource = interactionSource,
        enabled = enabled,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().then(glassModifier),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

// ── Interactive liquid glass core (C1-dome optical system) ───────────────────────
// Matches the transparent crystal optics, C1-entry SDF dome slope, chromatic
// R/G/B dispersion, meniscus rim, and droplet press physics from the reference
// Liquid Glass implementation.

@Composable
fun isDeviceGlassCapable(): Boolean {
    val view = LocalView.current
    if (view.isInEditMode) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    if (!view.isHardwareAccelerated) return false
    val context = LocalContext.current
    val am = remember(context) {
        runCatching { context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
    }
    if (am?.isLowRamDevice == true) return false
    return true
}

@Composable
fun isLiquidGlassBackdropSupported(): Boolean =
    LocalLiquidGlass.current && isDeviceGlassCapable()

@Composable
fun Modifier.liquidGlassSource(
    backdrop: LayerBackdrop?,
): Modifier {
    if (backdrop == null) return this
    if (!isLiquidGlassBackdropSupported()) return this
    return this.layerBackdrop(backdrop)
}

/**
 * Returns [Color.Transparent] whenever backdrop refraction is active so resting
 * controls carry zero milky white wash (`background: transparent`).
 */
@Composable
fun liquidGlassContainerColor(
    color: Color,
    enabled: Boolean = LocalLiquidGlass.current,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): Color = if (enabled && isLiquidGlassBackdropSupported() && backdrop != null) {
    Color.Transparent
} else color

@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

/**
 * C1-continuous entry dome slope matching `domeSlopeAt(distIn, bw, curvature)`
 * so edge normals ramp smoothly from 0 across the outer 14% of the bezel and
 * eliminate Skia jagged pixelation at curved edges.
 */
internal fun domeSlopeAt(s: Float, curvature: Float): Float {
    if (s <= 0f || s >= 1f) return 0f
    val entry = if (s < 0.14f) s / 0.14f else 1f
    val smoothEntry = entry * entry * (3f - 2f * entry)
    val oneMinusS = (1f - s).coerceAtLeast(0f)
    val dome = oneMinusS.toDouble().pow((curvature - 1f).toDouble()).toFloat() *
        sin(oneMinusS.toDouble() * PI * 0.5).toFloat()
    return smoothEntry * dome
}

internal fun computeMaxDomeSlope(curvature: Float, samples: Int = 256): Float {
    var peak = 1e-4f
    for (i in 1 until samples) {
        val slope = domeSlopeAt(i.toFloat() / samples.toFloat(), curvature)
        if (slope > peak) peak = slope
    }
    return peak
}

/**
 * Optical parameter bundle for a Liquid Glass surface.
 */
data class LiquidGlassOptics(
    val refractionDp: Float,
    val bezel: Float,
    val curvature: Float,
    val chroma: Float,
    val blurDp: Float = 4f,
    val saturation: Float = 1.6f,
    val brightness: Float = 0f,
    val maxSlope: Float = computeMaxDomeSlope(curvature),
) {
    companion object {
        /** Floating pills, controls, mini-player, and FAB-sized capsules (~56dp). */
        val Floating = LiquidGlassOptics(
            refractionDp = 20f,
            bezel = 0.65f,
            curvature = 2.6f,
            chroma = 0.28f,
            blurDp = 4f,
            saturation = 1.6f,
            brightness = 0f,
        )

        /** Compact navbar pills, bottom dock, and header chrome (~36-48dp). */
        val NavPill = LiquidGlassOptics(
            refractionDp = 11f,
            bezel = 0.62f,
            curvature = 2.6f,
            chroma = 0.24f,
            blurDp = 4f,
            saturation = 1.6f,
            brightness = 0f,
        )
    }
}

private const val C1_DOME_REFRACTION_SHADER_KEY = "AuraC1DomeRefractionChromaV1"

private const val C1_DOME_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float bezel;
uniform float curvature;
uniform float refractionAmount;
uniform float chroma;
uniform float maxSlope;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 clamped = max(cornerCoord, 0.0);
        float len = length(clamped);
        if (len < 1e-5) {
            return sign(coord) * (cornerCoord.x >= cornerCoord.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
        }
        return sign(coord) * (clamped / len);
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float domeSlopeAt(float s, float curv) {
    float entry = s < 0.14 ? (s / 0.14) : 1.0;
    float smoothEntry = entry * entry * (3.0 - 2.0 * entry);
    float oneMinusS = max(0.0, 1.0 - s);
    float dome = oneMinusS > 1e-5 ? pow(oneMinusS, max(0.0, curv - 1.0)) * sin(oneMinusS * 1.5707963) : 0.0;
    return smoothEntry * dome;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    if (halfSize.x <= 0.0 || halfSize.y <= 0.0) {
        return content.eval(coord);
    }
    float2 centeredCoord = (coord + offset) - halfSize;
    float minHalf = min(halfSize.x, halfSize.y);
    float cornerR = min(radiusAt(centeredCoord, cornerRadii), minHalf);
    float sd = sdRoundedRect(centeredCoord, halfSize, cornerR);
    if (sd >= 0.0) {
        return content.eval(coord);
    }

    float effectiveR = cornerR > 0.5 ? cornerR : (minHalf * 0.45);
    float bw = max(4.0, bezel * effectiveR);
    float distIn = -sd;
    if (distIn >= bw) {
        return content.eval(coord);
    }

    float s = clamp(distIn / bw, 0.0, 1.0);
    float slopeNorm = clamp(domeSlopeAt(s, curvature) / max(maxSlope, 1e-4), 0.0, 1.0);
    float gradRadius = min(max(cornerR, bw * 1.02), minHalf);
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);

    float safeRefract = min(refractionAmount, bw * 0.90);
    float baseD = -safeRefract * slopeNorm;

    float2 uvR = coord + grad * baseD;
    float2 uvG = coord + grad * (baseD * (1.0 + chroma * 0.5));
    float2 uvB = coord + grad * (baseD * (1.0 + chroma));

    half4 cR = content.eval(uvR);
    half4 cG = content.eval(uvG);
    half4 cB = content.eval(uvB);

    half a = clamp(max(cG.a, max(cR.a, cB.a)), 0.0, 1.0);
    return half4(min(cR.r, a), min(cG.g, a), min(cB.b, a), a);
}
"""

internal fun resolveCornerRadiiPx(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): FloatArray {
    val maxRadius = (size.minDimension / 2f).coerceAtLeast(0f)
    if (maxRadius <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
    val raw = when (shape) {
        is RoundedRectangularShape -> {
            val c = shape.corners(size, layoutDirection, density)
            floatArrayOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft)
        }
        is AbsoluteRoundedCornerShape -> floatArrayOf(
            shape.topStart.toPx(size, density),
            shape.topEnd.toPx(size, density),
            shape.bottomEnd.toPx(size, density),
            shape.bottomStart.toPx(size, density),
        )
        is CornerBasedShape -> {
            val ts = shape.topStart.toPx(size, density)
            val te = shape.topEnd.toPx(size, density)
            val be = shape.bottomEnd.toPx(size, density)
            val bs = shape.bottomStart.toPx(size, density)
            if (layoutDirection == LayoutDirection.Ltr) {
                floatArrayOf(ts, te, be, bs)
            } else {
                floatArrayOf(te, ts, bs, be)
            }
        }
        else -> floatArrayOf(0f, 0f, 0f, 0f)
    }
    return FloatArray(4) { i -> raw[i].coerceIn(0f, maxRadius) }
}

/**
 * Press/hold state holder for a single liquid-glass surface.
 * Snappy 120ms cubic-bezier active droplet compress, spring release back to rest.
 */
class GlassInteraction(
    private val animationScope: CoroutineScope,
) {
    private val pressDownSpec = tween<Float>(
        durationMillis = 120,
        easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f),
    )
    private val releaseSpringSpec = spring(
        dampingRatio = 0.45f,
        stiffness = 420f,
        visibilityThreshold = 0.001f,
    )
    private val pressAnimation = Animatable(0f, 0.001f)

    /** 0f at rest, animating to 1f while pressed. Read in draw/effect/layer blocks. */
    val pressProgress: Float get() = pressAnimation.value

    /** Local-space touch point used as the centre of the touch sheen. */
    var touchPosition by mutableStateOf(Offset.Zero)
        private set

    suspend fun detectPress(pointer: PointerInputScope) =
        with(pointer) {
            inspectDragGestures(
                onDragStart = { down ->
                    touchPosition = down.position
                    animationScope.launch { pressAnimation.animateTo(1f, pressDownSpec) }
                },
                onDragEnd = { animationScope.launch { pressAnimation.animateTo(0f, releaseSpringSpec) } },
                onDragCancel = { animationScope.launch { pressAnimation.animateTo(0f, releaseSpringSpec) } },
            ) { change, _ ->
                touchPosition = change.position
            }
        }
}

@Composable
fun rememberGlassInteraction(): GlassInteraction {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassInteraction(scope) }
}

/**
 * Water-clear glass constants calibrated to match `walkthrough.md`:
 * blur(4px) saturate(160%), zero chalky border, zero resting milkiness.
 */
private object GlassRecipe {
    const val BLUR_BRIGHT_BOOST_DP = 2f
    const val CONTRAST = 1f
    const val MIN_SCRIM = 0f
    const val MAX_SCRIM = 0.14f
    const val PRESS_BLUR_DP = 1.5f
    const val DROPLET_PRESS_X = 0.955f
    const val DROPLET_PRESS_Y = 0.935f
}

private val DisabledChalkHighlight = Highlight(
    width = 0.dp,
    blurRadius = 0.dp,
    alpha = 0f,
    style = HighlightStyle.Plain(),
)

/**
 * Interactive liquid glass drawing modifier.
 * Element MUST be a sibling of the backdrop source, never inside it.
 */
fun Modifier.drawInteractiveGlass(
    isDark: Boolean,
    backdrop: Backdrop,
    layer: GraphicsLayer? = null,
    luminanceAnimation: Float,
    shape: Shape,
    interaction: GlassInteraction?,
    pressedScale: Float = GlassRecipe.DROPLET_PRESS_X,
    highlight: Highlight = DisabledChalkHighlight,
    blurScale: Float = 1f,
    minScrim: Float = GlassRecipe.MIN_SCRIM,
    maxScrim: Float = GlassRecipe.MAX_SCRIM,
    optics: LiquidGlassOptics = LiquidGlassOptics.Floating,
    pressedScaleY: Float = if (pressedScale < 1f) GlassRecipe.DROPLET_PRESS_Y else pressedScale,
): Modifier {
    val safeShape = lensSafeGlassShape(shape)
    val zeroRadiusRect = shape == RectangleShape ||
        (shape is RoundedCornerShape && shape == RoundedCornerShape(0.dp))

    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { safeShape },
            highlight = {
                if (highlight === Highlight.Default) DisabledChalkHighlight else highlight
            },
            shadow = {
                if (zeroRadiusRect) {
                    null
                } else if (isDark) {
                    Shadow(
                        radius = 26.dp,
                        offset = DpOffset(0.dp, 10.dp),
                        color = Color.Black,
                        alpha = 0.38f,
                    )
                } else {
                    Shadow(
                        radius = 24.dp,
                        offset = DpOffset(0.dp, 10.dp),
                        color = Color(0xFF12100C),
                        alpha = 0.16f,
                    )
                }
            },
            effects = {
                val brightFactor = ((luminanceAnimation - 0.5f) / 0.5f).coerceIn(0f, 1f)
                val press = interaction?.pressProgress ?: 0f
                colorControls(
                    brightness = optics.brightness,
                    contrast = GlassRecipe.CONTRAST,
                    saturation = optics.saturation,
                )
                val blurRadiusPx = (
                    (optics.blurDp + GlassRecipe.BLUR_BRIGHT_BOOST_DP * brightFactor) * blurScale +
                        GlassRecipe.PRESS_BLUR_DP * press
                    ).dp.toPx()
                if (blurRadiusPx > 0.5f) {
                    blur(blurRadiusPx)
                }
                val corners = resolveCornerRadiiPx(safeShape, size, layoutDirection, this)
                runtimeShaderEffect(
                    key = C1_DOME_REFRACTION_SHADER_KEY,
                    shaderString = C1_DOME_REFRACTION_SHADER,
                    uniformShaderName = "content",
                ) {
                    setFloatUniform("size", size.width, size.height)
                    setFloatUniform("offset", -padding, -padding)
                    setFloatUniform("cornerRadii", corners)
                    setFloatUniform("bezel", optics.bezel)
                    setFloatUniform("curvature", optics.curvature)
                    setFloatUniform("refractionAmount", optics.refractionDp.dp.toPx())
                    setFloatUniform("chroma", optics.chroma)
                    setFloatUniform("maxSlope", optics.maxSlope)
                }
            },
            onDrawBackdrop = { drawBackdrop ->
                drawBackdrop()
            },
            onDrawSurface = {
                if (size.width > 0f && size.height > 0f) {
                    val brightOver50 = ((luminanceAnimation - 0.5f) / 0.5f).coerceIn(0f, 1f)
                    val adaptiveScrim = lerp(minScrim, maxScrim, brightOver50)
                    if (adaptiveScrim > 0.001f) {
                        drawRect((if (isDark) Color.Black else Color.White).copy(alpha = adaptiveScrim))
                    }

                    val press = interaction?.pressProgress ?: 0f
                    if (press > 0.001f) {
                        val sheenCenter = interaction?.touchPosition?.takeIf { it != Offset.Zero }
                            ?: Offset(size.width * 0.26f, size.height * 0.18f)
                        val sheenRadius = max(90.dp.toPx(), size.minDimension * 1.15f)
                        drawRect(
                            brush = Brush.radialGradient(
                                0.0f to Color.White.copy(alpha = 0.22f * press),
                                0.68f to Color.Transparent,
                                1.0f to Color.Transparent,
                                center = sheenCenter,
                                radius = sheenRadius,
                            ),
                            blendMode = BlendMode.Plus,
                        )
                    }

                    val outline = safeShape.createOutline(size, layoutDirection, this)
                    val outerRingColor = if (isDark) {
                        Color.White.copy(alpha = 0.10f)
                    } else {
                        Color.Black.copy(alpha = 0.14f)
                    }
                    // Clipped by surface shape: 1.dp stroke renders as crisp 0.5.dp outer contrast hairline.
                    drawOutline(
                        outline = outline,
                        color = outerRingColor,
                        style = Stroke(width = 1.dp.toPx()),
                    )

                    val meniscusBrush = Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = if (isDark) 0.32f else 0.28f),
                        0.32f to Color.Transparent,
                        0.68f to Color.Transparent,
                        1.0f to Color.White.copy(alpha = if (isDark) 0.12f else 0.10f),
                    )
                    // Clipped by surface shape: 1.5.dp stroke renders as exact 0.75.dp inset meniscus rim.
                    drawOutline(
                        outline = outline,
                        brush = meniscusBrush,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            },
            layerBlock =
                if (interaction != null) {
                    {
                        val press = interaction.pressProgress
                        scaleX = lerp(1f, pressedScale, press)
                        scaleY = lerp(1f, pressedScaleY, press)
                    }
                } else {
                    null
                },
        ).then(
            if (interaction != null) {
                Modifier.pointerInput(interaction) { interaction.detectPress(this) }
            } else {
                Modifier
            },
        )
}

/**
 * lens-safe shape guard: kyant's backdrop effects only accept corner-based shapes;
 * plain [RectangleShape] maps to a zero-radius [RoundedCornerShape].
 */
internal fun lensSafeGlassShape(shape: Shape): Shape =
    if (shape == RectangleShape) RoundedCornerShape(0.dp) else shape

/** Interactive liquidGlass: resting surfaces stay crystal clear (`luminanceAnimation = 0.5f`). */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape = CircleShape,
    interactive: Boolean = true,
    highlight: Highlight = DisabledChalkHighlight,
    forceDark: Boolean = false,
    optics: LiquidGlassOptics = LiquidGlassOptics.Floating,
): Modifier {
    if (!LocalLiquidGlass.current) {
        return this
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f))
    }
    if (!isLiquidGlassBackdropSupported()) {
        return this
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f))
    }
    val isDark = forceDark || LocalIsDarkTheme.current
    val layer = rememberGraphicsLayer()
    val interaction = rememberGlassInteraction()
    return this.drawInteractiveGlass(
        isDark = isDark,
        backdrop = backdrop,
        layer = layer,
        luminanceAnimation = 0.5f,
        shape = shape,
        interaction = if (interactive) interaction else null,
        highlight = highlight,
        optics = optics,
    )
}

/** Interactive liquidGlass overload for luminance-sampling surfaces (MiniPlayer, nav capsule). */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    layer: GraphicsLayer,
    luminanceAnimation: Float,
    shape: Shape = CircleShape,
    interactive: Boolean = true,
    blurScale: Float = 1f,
    minScrim: Float = GlassRecipe.MIN_SCRIM,
    maxScrim: Float = GlassRecipe.MAX_SCRIM,
    forceDark: Boolean = false,
    optics: LiquidGlassOptics = LiquidGlassOptics.Floating,
): Modifier {
    if (!LocalLiquidGlass.current || !isLiquidGlassBackdropSupported()) {
        return this
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f))
    }
    val isDark = forceDark || LocalIsDarkTheme.current
    val interaction = rememberGlassInteraction()
    return this.drawInteractiveGlass(
        isDark = isDark,
        backdrop = backdrop,
        layer = layer,
        luminanceAnimation = luminanceAnimation,
        shape = shape,
        interaction = if (interactive) interaction else null,
        pressedScale = 0.985f,
        pressedScaleY = 0.975f,
        blurScale = blurScale,
        minScrim = minScrim,
        maxScrim = maxScrim,
        optics = optics,
    )
}

@Composable
fun LiquidGlassContainer(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    interactive: Boolean = true,
    highlight: Highlight = DisabledChalkHighlight,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    if (backdrop == null || !isLiquidGlassBackdropSupported()) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)),
            contentAlignment = contentAlignment,
            content = content,
        )
        return
    }
    Box(
        modifier = modifier.liquidGlass(backdrop, shape, interactive, highlight),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** Floating action pill hosting icon buttons in a liquid glass shell. */
@Composable
fun LiquidGlassActionPill(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable RowScope.() -> Unit,
) {
    if (backdrop == null || !isLiquidGlassBackdropSupported()) {
        Row(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        return
    }
    Row(
        modifier = modifier.liquidGlass(backdrop, shape, true, DisabledChalkHighlight),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** LiquidGlassIconButton: ImageVector version. */
@Composable
fun LiquidGlassIconButton(
    backdrop: Backdrop?,
    imageVector: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = Color.White,
    interactive: Boolean = true,
    highlight: Highlight = DisabledChalkHighlight,
) {
    LiquidGlassContainer(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        interactive = interactive,
        highlight = highlight,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp).clickable(onClick = onClick),
        )
    }
}

/** Compat overload: Painter version (existing LastWave call sites). */
@Composable
fun LiquidGlassIconButton(
    backdrop: Backdrop?,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    LiquidGlassContainer(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        interactive = true,
        highlight = DisabledChalkHighlight,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp).clickable(onClick = onClick),
        )
    }
}

// ── Compat shims: route active glass presets to C1-dome optical presets ────────

enum class LiquidGlassPreset {
    BottomNavigation,
    MiniPlayer,
    PlayerControls,
    FloatingControls,
    Header,
    ModalSheet,
    ContextMenu,
    Overlay,
    Card,
}

private fun LiquidGlassPreset.isGlassTarget(): Boolean = when (this) {
    LiquidGlassPreset.BottomNavigation,
    LiquidGlassPreset.MiniPlayer,
    LiquidGlassPreset.PlayerControls,
    LiquidGlassPreset.FloatingControls,
    LiquidGlassPreset.Header -> true
    LiquidGlassPreset.ModalSheet,
    LiquidGlassPreset.ContextMenu,
    LiquidGlassPreset.Overlay,
    LiquidGlassPreset.Card -> false
}

private fun LiquidGlassPreset.toOptics(): LiquidGlassOptics = when (this) {
    LiquidGlassPreset.BottomNavigation,
    LiquidGlassPreset.Header -> LiquidGlassOptics.NavPill
    LiquidGlassPreset.MiniPlayer,
    LiquidGlassPreset.PlayerControls,
    LiquidGlassPreset.FloatingControls,
    LiquidGlassPreset.ModalSheet,
    LiquidGlassPreset.ContextMenu,
    LiquidGlassPreset.Overlay,
    LiquidGlassPreset.Card -> LiquidGlassOptics.Floating
}

/**
 * Routes active glass presets to [liquidGlass] with preset-matched optics
 * (NavPill 11dp/0.62/0.24 chroma vs Floating 20dp/0.65/0.28 chroma).
 */
@Composable
fun Modifier.liquidGlassChrome(
    shape: Shape,
    enabled: Boolean = true,
    preset: LiquidGlassPreset = LiquidGlassPreset.Card,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
    interactionSource: MutableInteractionSource? = null,
    exportedBackdrop: LayerBackdrop? = null,
    ambientMotion: Boolean = false,
    contentBrightness: Float = 0f,
    onPointerPosition: ((Offset?) -> Unit)? = null,
    forceDark: Boolean = false,
): Modifier {
    if (!enabled || !preset.isGlassTarget() || backdrop == null || !isLiquidGlassBackdropSupported()) {
        return this
    }
    // PlayerControls and FloatingControls in Now Playing always use dark-glass
    // styling so they stay sharp over dark blurred artwork backdrops.
    val shouldForceDark = forceDark ||
        preset == LiquidGlassPreset.PlayerControls ||
        preset == LiquidGlassPreset.FloatingControls
    return this.liquidGlass(
        backdrop = backdrop,
        shape = shape,
        interactive = preset != LiquidGlassPreset.BottomNavigation && preset != LiquidGlassPreset.Header,
        highlight = DisabledChalkHighlight,
        forceDark = shouldForceDark,
        optics = preset.toOptics(),
    )
}

/**
 * Veil overlay replacing deprecated full-screen self-capture blur loops.
 */
@Composable
fun BackdropBlur(
    radius: Dp,
    modifier: Modifier = Modifier,
    veil: Color = Color.Unspecified,
    veilAlpha: Float = 0.74f,
    content: @Composable BoxScope.() -> Unit,
) {
    val resolvedVeil = if (veil == Color.Unspecified) MaterialTheme.colorScheme.surface else veil
    Box(modifier) {
        Box(Modifier.matchParentSize(), content = content)
        Box(Modifier.matchParentSize().background(resolvedVeil.copy(alpha = veilAlpha)))
    }
}

/**
 * Observe-only drag/press recogniser (Kyant catalog DragGestureInspector).
 * Never consumes events, so glass reacts while wrapped buttons keep their taps.
 */
internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down)
        onDrag(down, Offset.Zero)
        val upEvent = drag(
            pointerId = down.id,
            onDrag = { onDrag(it, it.positionChange()) },
        )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(pointerId: PointerId): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}
