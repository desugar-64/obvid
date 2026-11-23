// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Where the device tests write their results: the app's files dir (or its [subdir]), then AGP's additional test
 * output dir (pulled to the host) when set.
 */
internal fun outputDirs(context: Context, subdir: String? = null): List<File> =
  listOfNotNull(
    if (subdir == null) context.filesDir else File(context.filesDir, subdir),
    InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File),
  )

/** Writes [lines], one per line, to the file [name] in every [outputDirs]. */
internal fun writeResults(context: Context, name: String, lines: List<String>, subdir: String? = null) {
  for (d in outputDirs(context, subdir)) {
    d.mkdirs()
    File(d, name).writeText(lines.joinToString("\n", postfix = "\n"))
  }
}
