// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.annotation.SuppressLint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DropShadowScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize

/**
 * A node's handle on its [ShadowEntry] plus the objects Compose's `ShadowRenderer` / `DropShadowRenderer` /
 * `InnerShadowRenderer` cache for the final draw (the tint `ColorFilter`, the composite brush, the inner shadow's mask
 * brush). Every drop and inner node draws through one; a state draws one kind only.
 */
internal class ShadowDrawState {
  /** The store of the thread this node draws on, taken on the first draw after an attach. */
  private var store: ShadowStore? = null
  private var entry: ShadowEntry? = null

  /** The first build started when the node was measured, until the draw takes it ([prefetch], [takePrefetch]). */
  private var prefetch: ShadowPrefetch? = null

  private var tint: ColorFilter? = null
  private var tintColor: Color = Color.Unspecified

  // Drop shadow: the composite brush (one per build of one entry and brush).
  private var composite: Brush? = null
  private var compositeSrc: Brush? = null
  private var compositeEntry: ShadowEntry? = null
  private var compositeBuild = -1

  // Inner shadow: the mask brush (one per build of one entry) and the composite brush (one per mask and brush).
  private var mask: ShaderBrush? = null
  private var maskEntry: ShadowEntry? = null
  private var maskBuild = -1
  private var innerComposite: Brush? = null
  private var innerCompositeSrc: Brush? = null
  private var innerCompositeMask: ShaderBrush? = null

  /**
   * What [CpuDropShadowNode] draws for [shadow] (already normalized by `Shadow.init`: color, brush, alpha) with the
   * bitmap's [extent].
   */
  fun DrawScope.drawShadow(shape: Shape, shadow: Shadow, extent: ShadowExtent) {
    drawShadow(
      shape,
      shadow.radius.toPx(),
      shadow.spread.toPx(),
      shadow.offset.x.toPx(),
      shadow.offset.y.toPx(),
      shadow.color,
      shadow.brush,
      shadow.alpha,
      shadow.blendMode,
      extent,
    )
  }

  /**
   * `DropShadowPainter.onDraw` → `ShadowRenderer.drawShadow` → `DropShadowRenderer.onDrawShadow`, with the same calls
   * in the same order: rebuild through [ShadowStore] if the geometry (the [extent] included) changed, then Compose's
   * draw call. The painter's own alpha is 1 and its color filter null in the modifier, so [alpha] (in [0, 1]) is drawn
   * as is. A full-extent bitmap is drawn at Compose's position moved by its origin ([ShadowBitmaps.originX]), so the
   * pixels Compose's window holds land where Compose draws them; a brush's gradient stays on Compose's window.
   */
  fun DrawScope.drawShadow(
    shape: Shape,
    radiusPx: Float,
    spreadPx: Float,
    offsetXPx: Float,
    offsetYPx: Float,
    color: Color,
    brush: Brush?,
    alpha: Float,
    blendMode: BlendMode,
    extent: ShadowExtent,
  ) {
    // Painter.draw: the painter draws only for a non-empty size.
    if (!(size.width > 0 && size.height > 0)) return
    val store = store ?: ShadowStore.current().also { this@ShadowDrawState.store = it }
    val entry =
      takePrefetch(store, shape, size, layoutDirection, density, radiusPx, spreadPx, false, 0f, 0f, extent)
        ?: store.obtain(
          this@ShadowDrawState.entry, this, shape, size, layoutDirection, radiusPx, spreadPx, false, 0f, 0f, extent,
        )
    this@ShadowDrawState.entry = entry
    translate(offsetXPx, offsetYPx) {
      val offset = -(radiusPx + spreadPx)
      val bitmaps = entry.bitmaps
      val left = if (bitmaps.originX == 0) offset else offset + bitmaps.originX
      val top = if (bitmaps.originY == 0) offset else offset + bitmaps.originY
      if (brush != null) {
        val shaderBrush = obtainCompositeBrush(entry, brush)
        // A BitmapShader's image is not pinned at sync: the next build must not rewrite this bitmap.
        entry.sampledByShader = true
        // In Compose's window, where the gradient lies; the bitmap starts at its origin there (the image shader's).
        translate(offset, offset) {
          drawRect(
            brush = shaderBrush,
            topLeft = Offset(bitmaps.originX.toFloat(), bitmaps.originY.toFloat()),
            size = Size(entry.usedWidth.toFloat(), entry.usedHeight.toFloat()),
            alpha = alpha,
            blendMode = blendMode,
          )
        }
      } else {
        drawImage(
          entry.imageBitmap(),
          topLeft = Offset(left, top),
          alpha = alpha,
          colorFilter = if (color.isSpecified) obtainTint(color) else null,
          blendMode = blendMode,
        )
      }
    }
  }

  /** What [CpuInnerShadowNode] draws for [shadow] (already normalized by `Shadow.init`: color, brush, alpha). */
  fun DrawScope.drawInnerShadow(shape: Shape, shadow: Shadow) {
    drawInnerShadow(
      shape,
      shadow.radius.toPx(),
      shadow.spread.toPx(),
      shadow.offset.x.toPx(),
      shadow.offset.y.toPx(),
      shadow.color,
      shadow.brush,
      shadow.alpha,
      shadow.blendMode,
    )
  }

  /**
   * `InnerShadowPainter.onDraw` → `ShadowRenderer.drawShadow` → `InnerShadowRenderer.onDrawShadow`, with the same
   * calls in the same order: rebuild through [ShadowStore] if the geometry (offset included) changed, then the outline
   * filled with the mask as a `BitmapShader` (pixel (0, 0) at the component's origin, no translate: the offset is in
   * the bitmap), composited `SrcIn` with a [ShaderBrush] [brush] sized to the component, or tinted with [color]
   * without a brush. The painter's own alpha is 1 and its color filter null in the modifier, so [alpha] (in [0, 1]) is
   * drawn as is. When the build found a large hole of 0s in the bitmap ([ShadowBitmaps.hasBands]) and [blendMode]
   * leaves the destination as it is under a transparent source, the same fill is drawn once per band around the hole
   * instead, so the GPU shades only the pixels the bitmap can change.
   */
  fun DrawScope.drawInnerShadow(
    shape: Shape,
    radiusPx: Float,
    spreadPx: Float,
    offsetXPx: Float,
    offsetYPx: Float,
    color: Color,
    brush: Brush?,
    alpha: Float,
    blendMode: BlendMode,
  ) {
    // Painter.draw: the painter draws only for a non-empty size.
    if (!(size.width > 0 && size.height > 0)) return
    val store = store ?: ShadowStore.current().also { this@ShadowDrawState.store = it }
    val entry =
      takePrefetch(
        store, shape, size, layoutDirection, density, radiusPx, spreadPx, true, offsetXPx, offsetYPx, ShadowExtent.Compose,
      ) ?: store.obtain(this@ShadowDrawState.entry, this, shape, size, layoutDirection, radiusPx, spreadPx, true, offsetXPx, offsetYPx)
    this@ShadowDrawState.entry = entry
    // ShadowRenderer.drawShadow's filter: the tint only without a brush.
    val filter = if (brush == null && color.isSpecified) obtainTint(color) else null
    val mask = obtainMask(entry)
    val target = if (brush is ShaderBrush) obtainInnerComposite(mask, brush) else mask
    // Every inner draw samples the bitmap through a BitmapShader, which is not pinned at sync: the next build must not
    // rewrite this bitmap.
    entry.sampledByShader = true
    val bitmaps = entry.bitmaps
    if (!bitmaps.hasBands || !keepsDestinationUnderClear(blendMode) || NativeShadows.forceSingleDraw) {
      fillOutline(bitmaps, target, alpha, filter, blendMode)
      return
    }
    // The bitmap is 0 inside the hole, so there the draw blends a transparent source, which leaves the destination as
    // it is under this blend mode: the same draw, clipped to each band around the hole, gives the same pixels. A rect
    // clip is not anti-aliased (HWUI's `clipRect`): adjacent bands share their edges without a pixel drawn twice or
    // missed, and on the GPU an axis-aligned one is a scissor, so the hole is never shaded.
    entry.bandDrawCount++
    val l = bitmaps.bandLeft.toFloat()
    val t = bitmaps.bandTop.toFloat()
    val r = bitmaps.bandRight.toFloat()
    val b = bitmaps.bandBottom.toFloat()
    val hl = bitmaps.holeLeft.toFloat()
    val ht = bitmaps.holeTop.toFloat()
    val hr = bitmaps.holeRight.toFloat()
    val hb = bitmaps.holeBottom.toFloat()
    clipRect(l, t, r, ht) { fillOutline(bitmaps, target, alpha, filter, blendMode) }
    clipRect(l, hb, r, b) { fillOutline(bitmaps, target, alpha, filter, blendMode) }
    clipRect(l, ht, hl, hb) { fillOutline(bitmaps, target, alpha, filter, blendMode) }
    clipRect(hr, ht, r, hb) { fillOutline(bitmaps, target, alpha, filter, blendMode) }
  }

  /** `InnerShadowRenderer.onDrawShadow`'s draw: the outline of [bitmaps] filled with [brush]. */
  private fun DrawScope.fillOutline(bitmaps: ShadowBitmaps, brush: Brush, alpha: Float, filter: ColorFilter?, blendMode: BlendMode) {
    val path = bitmaps.path
    val cornerRadius = bitmaps.cornerRadius
    if (path != null) {
      drawPath(path, brush = brush, alpha = alpha, colorFilter = filter, blendMode = blendMode)
    } else if (cornerRadius == CornerRadius.Zero) {
      drawRect(brush = brush, alpha = alpha, colorFilter = filter, blendMode = blendMode)
    } else {
      drawRoundRect(brush = brush, cornerRadius = cornerRadius, alpha = alpha, colorFilter = filter, blendMode = blendMode)
    }
  }

  /**
   * Whether a transparent source (all channels 0) leaves the destination unchanged under [mode]: the Porter-Duff
   * modes whose destination factor is 1 at source alpha 0 (`SrcOver`, `DstOver`, `DstOut`, `SrcAtop`, `Xor`, `Dst`)
   * and `Plus` and `Screen` (`s + d − s·d`), all coefficient modes the GPU blends in fixed function. Not `Clear`, `Src`,
   * `SrcIn`, `DstIn`, `SrcOut`, `DstAtop` and `Modulate` (a transparent source changes the destination), nor the
   * modes blended in a shader (`Overlay` … `Luminosity`, `Multiply`), whose arithmetic at alpha 0 is not guaranteed
   * to return the destination's bytes.
   */
  private fun keepsDestinationUnderClear(mode: BlendMode): Boolean =
    mode == BlendMode.SrcOver ||
      mode == BlendMode.DstOver ||
      mode == BlendMode.DstOut ||
      mode == BlendMode.SrcAtop ||
      mode == BlendMode.Xor ||
      mode == BlendMode.Dst ||
      mode == BlendMode.Plus ||
      mode == BlendMode.Screen

  /** Whether the node has not built yet: [prefetch] may queue its first build. */
  val wantsPrefetch: Boolean
    get() = entry == null

  /**
   * Queues the first build of a node that has not drawn yet ([wantsPrefetch]) when it is measured, for the key the
   * draw will compute from the same values ([density] is the node's, as the draw scope's is). A new key replaces the
   * job of an earlier measure; an empty size (the draw draws nothing) drops it.
   */
  fun prefetch(
    density: Density,
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    radiusPx: Float,
    spreadPx: Float,
    inner: Boolean,
    offsetXPx: Float,
    offsetYPx: Float,
    extent: ShadowExtent,
  ) {
    val store = store ?: ShadowStore.current().also { this.store = it }
    val key =
      store.key(shape, size, layoutDirection, density.density, radiusPx, spreadPx, inner, offsetXPx, offsetYPx, extent)
    val held = prefetch
    if (held?.key != key) {
      held?.let { store.dropPrefetch(it) }
      prefetch = if (size.width > 0 && size.height > 0) store.prefetch(key, density) else null
    }
  }

  /**
   * The entry of this node's prefetched first build if it has this key, a worker built it (waiting while one is
   * building it) and no other holder took it, else null: the caller then obtains the entry as usual
   * ([ShadowStore.obtain]), also when no worker had started the job. A build that threw on the worker throws here, as
   * it would have thrown from [ShadowStore.obtain]. The job is let go on every way out.
   */
  private fun takePrefetch(
    store: ShadowStore,
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Float,
    radiusPx: Float,
    spreadPx: Float,
    inner: Boolean,
    offsetXPx: Float,
    offsetYPx: Float,
    extent: ShadowExtent,
  ): ShadowEntry? {
    val job = prefetch ?: return null
    prefetch = null
    var taken: ShadowEntry? = null
    try {
      val key =
        store.key(shape, size, layoutDirection, density, radiusPx, spreadPx, inner, offsetXPx, offsetYPx, extent)
      if (job.key == key && store.mayAdopt(job) && !job.claimForDraw()) {
        job.awaitWorker()?.let { throw it }
        taken = store.adopt(job)
      }
    } finally {
      if (taken == null) store.dropPrefetch(job)
    }
    return taken
  }

  /** Test hook: waits until a worker built this node's queued first build (none queued: returns at once). */
  fun awaitPrefetchForTest() {
    prefetch?.awaitWorker()
  }

  fun release() {
    prefetch?.let { store?.dropPrefetch(it) }
    prefetch = null
    entry?.let { store?.release(it) }
    entry = null
    store = null
    composite = null
    compositeSrc = null
    compositeEntry = null
    mask = null
    maskEntry = null
    innerComposite = null
    innerCompositeSrc = null
    innerCompositeMask = null
  }

  /**
   * The inner shadow's mask, `ShaderBrush(ImageShader(bitmap))` as `createInner…ShadowBrush` returns it: one per build
   * of [entry] (the shader holds the bitmap as it was, and every build writes the other buffer).
   */
  private fun obtainMask(entry: ShadowEntry): ShaderBrush {
    val cached = mask
    if (cached != null && maskEntry === entry && maskBuild == entry.buildCount) return cached
    return ShaderBrush(ImageShader(entry.imageBitmap())).also {
      mask = it
      maskEntry = entry
      maskBuild = entry.buildCount
    }
  }

  /**
   * `InnerShadowRenderer.obtainCompositeBrush`: the [mask] composited `SrcIn` with [brush], which is passed as is, so
   * the draw sizes it to the component. Rebuilt when the brush or the mask changes.
   */
  private fun obtainInnerComposite(mask: ShaderBrush, brush: Brush): Brush {
    val cached = innerComposite
    if (cached != null && innerCompositeSrc == brush && innerCompositeMask === mask) return cached
    return Brush.composite(mask, brush, BlendMode.SrcIn).also {
      innerComposite = it
      innerCompositeSrc = brush
      innerCompositeMask = mask
    }
  }

  /** `ShadowRenderer.obtainTint`. */
  private fun obtainTint(color: Color): ColorFilter {
    var filter = tint
    if (filter == null || tintColor != color) {
      filter = ColorFilter.tint(color)
      tintColor = color
      tint = filter
    }
    return filter
  }

  /**
   * `DropShadowRenderer.obtainCompositeBrush`, with the brush's shader sized to Compose's bitmap size (the used size
   * for Compose's window). Drawn in Compose's window, so the gradient lies where Compose puts it (and is clamped past
   * the window for the full extent). Rebuilt when the brush or the bitmap changes (every build: the shader holds the
   * bitmap as it was).
   *
   * The full extent's bitmap starts at its origin in that window: Obvid's image shader is moved by it. The brush's
   * shader is never changed: `ShaderBrush(shader)` returns the caller's own shader, maybe shared, with its own local
   * matrix.
   */
  private fun obtainCompositeBrush(entry: ShadowEntry, brush: Brush): Brush {
    val cached = composite
    if (cached != null && compositeSrc == brush && compositeEntry === entry && compositeBuild == entry.buildCount) {
      return cached
    }
    val bitmaps = entry.bitmaps
    val image = ImageShader(entry.imageBitmap())
    if (bitmaps.originX != 0 || bitmaps.originY != 0) {
      image.setLocalMatrix(
        android.graphics.Matrix().apply { setTranslate(bitmaps.originX.toFloat(), bitmaps.originY.toFloat()) }
      )
    }
    val size = Size(bitmaps.composeWidth.toFloat(), bitmaps.composeHeight.toFloat())
    val source = if (brush is ShaderBrush) ShaderBrush(brush.createShader(size)) else brush
    return Brush.composite(ShaderBrush(image), source, BlendMode.SrcIn)
      .also {
        composite = it
        compositeSrc = brush
        compositeEntry = entry
        compositeBuild = entry.buildCount
      }
  }
}

/**
 * Registers the stores' memory-pressure callbacks on the application context, once per process; after that, one
 * volatile read per attach.
 */
internal fun CompositionLocalConsumerModifierNode.installShadowMemoryCallbacks() {
  if (ShadowMemoryCallbacks.isInstalled) return
  ShadowMemoryCallbacks.install(currentValueOf(LocalContext))
}

/**
 * A shadow node: draw-only, as Compose's own. With [ShadowBuilds.Parallel] at attach, it also delegates a
 * [ShadowMeasureNode], so that its layout's measurement queues the first build ([onMeasured]). Only then: Compose
 * remeasures the layout of a layout-aware node when the node is inserted, so a node with [ShadowBuilds.Inline] stays
 * draw-only. A node attached with [ShadowBuilds.Inline] builds in the draw until it is attached again.
 */
internal abstract class CpuShadowNode :
  DelegatingNode(), DrawModifierNode, CompositionLocalConsumerModifierNode {

  private var measureNode: ShadowMeasureNode? = null

  // Read at attach on purpose: the mode decides then whether the node is layout-aware ([LocalShadowBuilds]). A node
  // reused by a lazy layout keeps its delegate while detached, so each attach adds or drops it.
  @SuppressLint("SuspiciousCompositionLocalModifierRead")
  override fun onAttach() {
    installShadowMemoryCallbacks()
    val parallel = currentValueOf(LocalShadowBuilds) == ShadowBuilds.Parallel
    val kept = measureNode
    when {
      parallel && kept == null -> measureNode = delegate(ShadowMeasureNode(::onMeasured))
      !parallel && kept != null -> {
        undelegate(kept)
        measureNode = null
      }
    }
  }

  /** The layout measured this node at [size] ([ShadowBuilds.Parallel] at attach). */
  protected abstract fun onMeasured(size: IntSize)
}

/** The layout-aware part of a [CpuShadowNode] with [ShadowBuilds.Parallel]. */
internal class ShadowMeasureNode(private val onMeasured: (IntSize) -> Unit) :
  Modifier.Node(), LayoutAwareModifierNode {

  // Compose's automatic invalidation of an updated layout-aware node remeasures its layout; an update of the shadow
  // changes only the draw, which the owner's own (automatic) invalidation covers.
  override val shouldAutoInvalidate: Boolean
    get() = false

  override fun onRemeasured(size: IntSize) {
    onMeasured(size)
  }
}

/**
 * Compose's `SimpleDropShadowNode`, rebuilding only on a geometry change. [LocalShadowExtent] is read in the draw: a
 * change of it invalidates the draw of every node that reads it, which then rebuilds.
 */
internal class CpuDropShadowNode(var shape: Shape, var shadow: Shadow) : CpuShadowNode() {

  private val state = ShadowDrawState()

  override fun onMeasured(size: IntSize) {
    if (state.wantsPrefetch && currentValueOf(LocalShadowBuilds) == ShadowBuilds.Parallel) {
      val density = requireDensity()
      val radiusPx = with(density) { shadow.radius.toPx() }
      val spreadPx = with(density) { shadow.spread.toPx() }
      val extent = currentValueOf(LocalShadowExtent)
      state.prefetch(
        density, shape, size.toSize(), requireLayoutDirection(), radiusPx, spreadPx, false, 0f, 0f, extent,
      )
    }
  }

  override fun ContentDrawScope.draw() {
    with(state) { drawShadow(shape, shadow, currentValueOf(LocalShadowExtent)) }
    drawContent()
  }

  override fun onDetach() {
    state.release()
  }
}

/**
 * Compose's `BlockDropShadowNode` / `BlockInnerShadowNode`, on the block's scope [S] (`DropShadowScope` /
 * `InnerShadowScope`, which the subclass implements with this class's members): the parameters are converted
 * px → Dp → px exactly as Compose does (through the `Shadow` it builds), but only a geometry change rebuilds.
 * Subclasses supply [scope] and the final draw ([drawFinalShadow]).
 *
 * Unlike Compose's node, the block runs on every draw instead of once per change of its observed reads: its State
 * reads are then recorded by the draw phase's own observation, which the owner registers again on every draw. A
 * cached result with its own `observeReads` goes stale when the node is detached and reattached with an unchanged
 * element, or when the owner clears its observations (its view detached and reattached): nothing re-runs the block.
 */
internal abstract class CpuBlockShadowNode<S : Density>(shape: Shape, block: S.() -> Unit) :
  CpuShadowNode(), Density {

  protected val state = ShadowDrawState()

  private var densityObject: Density? = null

  private var shape: Shape = shape
    set(value) {
      if (field != value) {
        field = value
        invalidateDraw()
      }
    }

  private var block: S.() -> Unit = block
    set(value) {
      if (field !== value) {
        field = value
        invalidateDraw()
      }
    }

  /** This node as the block's receiver. */
  protected abstract val scope: S

  override val density: Float
    get() = densityObject?.density ?: 1f

  override val fontScale: Float
    get() = densityObject?.fontScale ?: 1f

  // The scope's parameters. Written only by the block, which runs in the draw (and, with ShadowBuilds.Parallel, once
  // when the node is measured before its first build): no invalidation needed (the draw observes the block's reads).
  var radius: Float = 0f
  var spread: Float = 0f
  var offset: Offset = Offset.Zero
  var color: Color = Color.Black
    set(value) {
      field = if (value.isSpecified) value else Color.Black
    }
  var brush: Brush? = null
  var alpha: Float = 1f
  var blendMode: BlendMode = BlendMode.SrcOver

  fun update(shape: Shape, block: S.() -> Unit) {
    this.shape = shape
    this.block = block
  }

  override fun onAttach() {
    super.onAttach()
    updateDensity()
  }

  override fun onDensityChange() {
    if (isAttached) updateDensity()
  }

  private fun updateDensity() {
    val newDensity = requireDensity()
    if (densityObject != newDensity) {
      densityObject = newDensity
      invalidateDraw()
    }
  }

  override fun onDetach() {
    state.release()
  }

  /** Compose's `resetShadow()`. */
  private fun resetShadow() {
    radius = 0f
    spread = 0f
    offset = Offset.Zero
    color = Color.Black
    brush = null
    alpha = 1f
    blendMode = BlendMode.SrcOver
  }

  override fun ContentDrawScope.draw() {
    resetShadow()
    scope.block()
    drawBlockShadow(this)
    drawContent()
  }

  override fun onMeasured(size: IntSize) {
    if (state.wantsPrefetch && currentValueOf(LocalShadowBuilds) == ShadowBuilds.Parallel) {
      prefetchBlockShadow(size.toSize())
    }
  }

  /**
   * The block run once as the draw will run it, its parameters converted as [drawBlockShadow] converts them, to queue
   * the first build. A guess: the draw runs the block again, and another key builds inline. Compose calls
   * `onRemeasured` without observing reads, so the block's reads stay the draw's alone.
   */
  private fun prefetchBlockShadow(size: Size) {
    resetShadow()
    scope.block()
    val radiusDp = radius.toDp()
    val spreadDp = spread.toDp()
    val offsetXDp = offset.x.toDp()
    val offsetYDp = offset.y.toDp()
    val density = requireDensity()
    with(density) {
      // As the drop and the inner draw key it: a drop shadow's offset is not in its bitmap, an inner one's extent is
      // always Compose's.
      state.prefetch(
        density,
        shape,
        size,
        requireLayoutDirection(),
        radiusDp.toPx(),
        spreadDp.toPx(),
        inner,
        if (inner) offsetXDp.toPx() else 0f,
        if (inner) offsetYDp.toPx() else 0f,
        if (inner) ShadowExtent.Compose else currentValueOf(LocalShadowExtent),
      )
    }
  }

  /** An inner shadow ([drawFinalShadow] draws [ShadowDrawState.drawInnerShadow]) rather than a drop shadow. */
  protected abstract val inner: Boolean

  /** The block node's `obtainPainter`'s `Shadow`, without allocating it, drawn by [drawFinalShadow]. */
  private fun drawBlockShadow(drawScope: DrawScope) {
    // px → Dp with the node's density (this node is the Density here, not the draw scope).
    val radiusDp = radius.toDp()
    val spreadDp = spread.toDp()
    val offsetXDp = offset.x.toDp()
    val offsetYDp = offset.y.toDp()
    // Shadow.init: a SolidColor brush becomes the color; the alpha is clamped.
    val blockBrush = brush
    val shadowColor = if (blockBrush is SolidColor) blockBrush.value else color
    val shadowBrush = if (blockBrush is SolidColor) null else blockBrush
    val shadowAlpha = alpha.coerceIn(0f, 1f)
    val shadowShape = shape
    val shadowBlendMode = blendMode
    // The renderer: Dp → px with the draw scope's density.
    with(drawScope) {
      drawFinalShadow(
        shadowShape,
        radiusDp.toPx(),
        spreadDp.toPx(),
        offsetXDp.toPx(),
        offsetYDp.toPx(),
        shadowColor,
        shadowBrush,
        shadowAlpha,
        shadowBlendMode,
      )
    }
  }

  /** The final draw of the shadow, with [state]: [ShadowDrawState]'s drop or inner draw of these parameters. */
  protected abstract fun DrawScope.drawFinalShadow(
    shape: Shape,
    radiusPx: Float,
    spreadPx: Float,
    offsetXPx: Float,
    offsetYPx: Float,
    color: Color,
    brush: Brush?,
    alpha: Float,
    blendMode: BlendMode,
  )
}

/**
 * Compose's `BlockDropShadowNode` ([CpuBlockShadowNode]), drawn as [CpuDropShadowNode] draws a `Shadow`, with the
 * [LocalShadowExtent] read in the draw.
 */
internal class CpuBlockDropShadowNode(shape: Shape, block: DropShadowScope.() -> Unit) :
  CpuBlockShadowNode<DropShadowScope>(shape, block), DropShadowScope {

  override val scope: DropShadowScope
    get() = this

  override val inner: Boolean
    get() = false

  override fun DrawScope.drawFinalShadow(
    shape: Shape,
    radiusPx: Float,
    spreadPx: Float,
    offsetXPx: Float,
    offsetYPx: Float,
    color: Color,
    brush: Brush?,
    alpha: Float,
    blendMode: BlendMode,
  ) {
    with(state) {
      val extent = currentValueOf(LocalShadowExtent)
      drawShadow(shape, radiusPx, spreadPx, offsetXPx, offsetYPx, color, brush, alpha, blendMode, extent)
    }
  }
}
