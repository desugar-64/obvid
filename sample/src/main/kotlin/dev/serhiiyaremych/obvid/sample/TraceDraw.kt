// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import android.os.Trace
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.tracing.trace

/** Trace section name Macrobenchmark's `TraceSectionMetric` reads. */
const val ShadowTraceSection = "demo:dropShadow"

/**
 * Wraps the rest of the draw chain in an [ShadowTraceSection] trace section. Placed immediately before
 * `dropShadow`, the section times the shadow draw (including any rebuild of its bitmap) on the main
 * thread. It also covers what follows `dropShadow` in the chain, here the card's `background`, which
 * is a single record op and negligible next to a shadow rebuild.
 */
fun Modifier.traceDraw(): Modifier = this then TraceDrawElement

private data object TraceDrawElement : ModifierNodeElement<TraceDrawNode>() {
  override fun create() = TraceDrawNode()

  override fun update(node: TraceDrawNode) = Unit
}

private class TraceDrawNode : Modifier.Node(), DrawModifierNode {
  override fun ContentDrawScope.draw() = trace(ShadowTraceSection) { drawContent() }
}

/** Trace section around each inner shadow draw of the tier scenarios. */
const val InnerShadowTraceSection = "demo:innerShadow"

/**
 * Draws [shadow] (a chain of shadow modifiers, or [Modifier] for none) inside trace section [name], and nothing else:
 * the section opens before [shadow] draws and closes where [shadow] hands over to the rest of the chain (its
 * `drawContent()`), so the background, text and children drawn after it stay outside. With an empty [shadow] the
 * section is the marker pair only, the same trace overhead without a shadow.
 */
fun Modifier.shadowSection(name: String, shadow: Modifier): Modifier =
  this then SectionStartElement(name) then shadow then SectionEndElement

/** Sections opened by [SectionStartNode] and not yet closed (draw runs on the main thread only). */
private var openSections = 0

private data class SectionStartElement(val name: String) : ModifierNodeElement<SectionStartNode>() {
  override fun create() = SectionStartNode(name)

  override fun update(node: SectionStartNode) {
    node.name = name
  }
}

private class SectionStartNode(var name: String) : Modifier.Node(), DrawModifierNode {
  override fun ContentDrawScope.draw() {
    Trace.beginSection(name)
    val level = ++openSections
    try {
      drawContent()
    } finally {
      // Closed here only if the chain never reached the end marker.
      if (openSections >= level) {
        Trace.endSection()
        openSections = level - 1
      }
    }
  }
}

private data object SectionEndElement : ModifierNodeElement<SectionEndNode>() {
  override fun create() = SectionEndNode()

  override fun update(node: SectionEndNode) = Unit
}

private class SectionEndNode : Modifier.Node(), DrawModifierNode {
  override fun ContentDrawScope.draw() {
    if (openSections > 0) {
      Trace.endSection()
      openSections--
    }
    drawContent()
  }
}
