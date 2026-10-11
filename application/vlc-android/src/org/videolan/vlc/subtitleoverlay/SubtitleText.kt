/*
 * **************************************************************************
 *  SubtitleText.kt
 *  Screen-anchored subtitle overlay subsystem
 * **************************************************************************
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  **************************************************************************
 */

package org.videolan.vlc.subtitleoverlay

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Text helpers shared by the subtitle parsers: byte decoding and the small clean-up every
 * format needs (dropping the inline markup the overlay cannot draw, splitting lines).
 */
object SubtitleText {

    /** Inline markup the overlay does not render: `<i>`, `{...}` styling, `{\an8}` placement. */
    private val HTML_TAG = Regex("<[^>]*>")
    private val BRACE_TAG = Regex("\\{[^}]*}")
    private val BACKSLASH_TAG = Regex("\\\\[Nnh]")

    /** Charsets probed (in order) when the bytes are not valid UTF-8. */
    private val FALLBACKS = listOf(Charset.forName("windows-1252"), Charset.forName("GBK"))

    /** Decodes subtitle text bytes: UTF-8 first (BOM included), then the legacy fallbacks. */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf8 = StandardCharsets.UTF_8
        val decoded = utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val strict = utf8.newDecoder().runCatching { decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        if (strict != null && !looksGarbled(strict)) return strict
        for (charset in FALLBACKS) {
            val candidate = charset.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            if (!looksGarbled(candidate)) return candidate
        }
        return decoded
    }

    /** Decodes a `tx3g`/QuickTime text payload: UTF-8, or UTF-16 when it starts with a BOM. */
    fun decodeTimedText(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            }
        }
        return decode(bytes)
    }

    /** Splits a cue payload into the lines the overlay draws, markup already removed. */
    fun lines(text: String): List<String> =
            text.replace("\uFEFF", "")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .split('\n')
                    .map { cleanLine(it) }
                    .filter { it.isNotEmpty() }

    /** Removes inline markup from a single line and trims trailing whitespace. */
    fun cleanLine(line: String): String =
            line.replace(HTML_TAG, "")
                    .replace(BRACE_TAG, "")
                    .replace(BACKSLASH_TAG, " ")
                    .trim()

    /** True when the decoded text looks like a wrong-charset decoding (lots of replacement chars). */
    private fun looksGarbled(decoded: String): Boolean {
        if (decoded.isEmpty()) return false
        var bad = 0
        for (c in decoded) if (c == '\uFFFD') bad++
        return bad > decoded.length / 20
    }
}
