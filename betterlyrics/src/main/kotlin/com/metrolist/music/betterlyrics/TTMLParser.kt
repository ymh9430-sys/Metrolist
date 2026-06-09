package com.metrolist.music.betterlyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import timber.log.Timber
import javax.xml.parsers.DocumentBuilderFactory

object TTMLParser {

    private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"

    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord>,
        val agent: String? = null,
        val isBackground: Boolean = false,
        val backgroundLines: List<ParsedLine> = emptyList()
    )

    data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean = true
    )

    private data class SpanInfo(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean
    )

    private fun getAttr(el: Element, localName: String): String {
        val ttm = el.getAttribute("ttm:$localName")
        if (ttm.isNotEmpty()) return ttm
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        return el.getAttributeNS("http://www.w3.org/ns/ttml#metadata", localName)
    }

    private fun timingAttr(el: Element, localName: String): String {
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        val param = el.getAttributeNS(TTML_PARAMETER_NS, localName)
        if (param.isNotEmpty()) return param
        return ""
    }

    private fun findFirstSpanBegin(p: Element): String? {
        var child = p.firstChild
        var best: String? = null
        var bestSeconds = Double.POSITIVE_INFINITY
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == "span") {
                    val b = timingAttr(child, "begin")
                    if (b.isNotEmpty()) {
                        val s = parseTime(b)
                        if (s < bestSeconds) {
                            bestSeconds = s
                            best = b
                        }
                    }
                }
            }
            child = child.nextSibling
        }
        return best
    }

    fun parseTTML(ttml: String): List<ParsedLine> {
        val lines = mutableListOf<ParsedLine>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (e: Exception) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (e: Exception) {}
            try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (e: Exception) {}
            try { factory.setXIncludeAware(false) } catch (e: Exception) {}
            try { factory.isExpandEntityReferences = false } catch (e: Exception) {}

            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ttml.byteInputStream())
            val root = doc.documentElement

            var globalOffset = 0.0
            val head = findChild(root, "head")
            if (head != null) {
                val meta = findChild(head, "metadata")
                if (meta != null) {
                    val audio = findChild(meta, "audio")
                    if (audio != null) {
                        globalOffset = audio.getAttribute("lyricOffset").toDoubleOrNull() ?: 0.0
                    }
                }
            }

            val body = findChild(root, "body")
            if (body != null) {
                walk(body, lines, globalOffset, null)
            }
        } catch (e: Exception) {
            Timber.e(e, "TTMLParser.parseTTML: Failed to parse TTML")
            return emptyList()
        }
        return lines
    }

    private fun findChild(parent: Element, localName: String): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == localName) return child
            }
            child = child.nextSibling
        }
        return null
    }

    private fun walk(element: Element, lines: MutableList<ParsedLine>, offset: Double, parentAgent: String?) {
        val name = element.localName ?: element.nodeName.substringAfterLast(':')
        var currentAgent = parentAgent

        when (name) {
            "div" -> {
                val a = getAttr(element, "agent")
                if (a.isNotEmpty()) currentAgent = a
            }
            "p" -> {
                parseP(element, lines, offset, currentAgent)
                return
            }
        }

        var child = element.firstChild
        while (child != null) {
            if (child is Element) walk(child, lines, offset, currentAgent)
            child = child.nextSibling
        }
    }

    private fun parseP(p: Element, lines: MutableList<ParsedLine>, offset: Double, divAgent: String?) {
        var begin = p.getAttribute("begin")
        if (begin.isEmpty()) {
            begin = p.getAttributeNS(TTML_PARAMETER_NS, "begin")
        }
        if (begin.isEmpty()) {
            begin = findFirstSpanBegin(p) ?: return
        }

        val startTime = parseTime(begin) + offset
        val spanInfos = mutableListOf<SpanInfo>()
        val backgroundLines = mutableListOf<ParsedLine>()

        val agent = getAttr(p, "agent").ifEmpty { divAgent }
        val isPBackground = getAttr(p, "role") == "x-bg"

        var child = p.firstChild
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == "span") {
                    val role = getAttr(child, "role")
                    when (role) {
                        "x-bg" -> {
                            if (isPBackground) parseWordSpan(child, offset, spanInfos, child)
                            else parseBackgroundSpan(child, startTime, offset)?.let { backgroundLines.add(it) }
                        }
                        "x-translation", "x-roman" -> {}
                        else -> parseWordSpan(child, offset, spanInfos, child)
                    }
                }
            }
            child = child.nextSibling
        }

        val words = mergeSpansIntoWords(spanInfos)
        val lineText = if (words.isEmpty()) getDirectText(p).trim() else buildLineText(words)

        if (lineText.isNotEmpty()) {
            val bgLines = if (backgroundLines.isNotEmpty()) {
                listOf(ParsedLine(
                    text = backgroundLines.joinToString(" ") { it.text },
                    startTime = backgroundLines.minOf { it.startTime },
                    words = backgroundLines.flatMap { it.words },
                    isBackground = true
                ))
            } else emptyList()
            lines.add(ParsedLine(lineText, startTime, words, agent, isPBackground, bgLines))
        } else if (backgroundLines.isNotEmpty()) {
            lines.add(ParsedLine(
                text = backgroundLines.joinToString(" ") { it.text },
                startTime = backgroundLines.minOf { it.startTime },
                words = backgroundLines.flatMap { it.words },
                isBackground = true
            ))
        }
    }

    private fun parseWordSpan(span: Element, offset: Double, spanInfos: MutableList<SpanInfo>, node: Node) {
        val begin = timingAttr(span, "begin")
        val end = timingAttr(span, "end")
        val text = span.textContent ?: ""
        if (begin.isNotEmpty() && end.isNotEmpty()) {
            val next = node.nextSibling
            val space = (text.isNotEmpty() && text.last().isWhitespace()) ||
                        (next?.nodeType == Node.TEXT_NODE && next.textContent?.firstOrNull()?.isWhitespace() == true)
            spanInfos.add(SpanInfo(text, parseTime(begin) + offset, parseTime(end) + offset, space))
        }
    }

    private fun parseBackgroundSpan(span: Element, parentStart: Double, offset: Double): ParsedLine? {
        val begin = timingAttr(span, "begin")
        val start = if (begin.isNotEmpty()) parseTime(begin) + offset else parentStart
        val spanInfos = mutableListOf<SpanInfo>()

        var child = span.firstChild
        var hasSpans = false
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == "span") {
                    hasSpans = true
                    val role = getAttr(child, "role")
                    if (role != "x-translation" && role != "x-roman") parseWordSpan(child, offset, spanInfos, child)
                }
            }
            child = child.nextSibling
        }

        if (!hasSpans) {
            val text = span.textContent?.trim() ?: ""
            return ParsedLine(text, start, emptyList(), isBackground = true)
        }

        val words = mergeSpansIntoWords(spanInfos)
        val text = if (words.isEmpty()) getDirectText(span).trim() else buildLineText(words)
        return ParsedLine(text, start, words, isBackground = true)
    }

    private fun getDirectText(el: Element): String {
        val sb = StringBuilder()
        var child = el.firstChild
        while (child != null) {
            if (child.nodeType == Node.TEXT_NODE) sb.append(child.textContent)
            else if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                val role = getAttr(child, "role")
                if (name == "span" && role != "x-bg" && role != "x-translation" && role != "x-roman") {
                    sb.append(child.textContent)
                }
            }
            child = child.nextSibling
        }
        return sb.toString()
    }

    private fun buildLineText(words: List<ParsedWord>) = buildString {
        words.forEachIndexed { i, w ->
            append(w.text)
            if (w.hasTrailingSpace && !w.text.endsWith('-') && i < words.lastIndex) append(" ")
        }
    }.trim()

    // ✅ كل span كلمة مستقلة بدون merge
    private fun mergeSpansIntoWords(spanInfos: List<SpanInfo>): List<ParsedWord> {
        if (spanInfos.isEmpty()) return emptyList()
        return spanInfos
            .filter { it.text.trim().isNotEmpty() }
            .map { span ->
                ParsedWord(
                    text = span.text.trim(),
                    startTime = span.startTime,
                    endTime = span.endTime,
                    hasTrailingSpace = span.hasTrailingSpace
                )
            }
    }

    fun toLRC(lines: List<ParsedLine>): String {
        val sb = StringBuilder(lines.size * 128)

        lines.forEach { line ->
            val time = formatLrcTime(line.startTime)

            // Main line
            if (line.words.isNotEmpty()) {
                sb.append(time)
                line.words.forEach { w ->
                    val start = formatLrcTime(w.startTime)
                    val end = formatLrcTime(w.endTime)
                    val text = w.text.trimEnd()
                    sb.append('<').append(start.drop(1).dropLast(1)).append('>')
                    sb.append(text)
                    sb.append('<').append(end.drop(1).dropLast(1)).append('>')
                    if (w.hasTrailingSpace) sb.append(' ')
                }
                sb.append('\n')
            } else if (line.text.isNotBlank()) {
                sb.append(time).append(line.text).append('\n')
            }

            // Background lines
            line.backgroundLines.forEach { bg ->
                val bgTime = formatLrcTime(bg.startTime)
                if (bg.words.isNotEmpty()) {
                    sb.append(bgTime)
                    bg.words.forEach { w ->
                        val start = formatLrcTime(w.startTime)
                        val end = formatLrcTime(w.endTime)
                        val text = w.text.trimEnd()
                        sb.append('<').append(start.drop(1).dropLast(1)).append('>')
                        sb.append(text)
                        sb.append('<').append(end.drop(1).dropLast(1)).append('>')
                        if (w.hasTrailingSpace) sb.append(' ')
                    }
                    sb.append('\n')
                } else if (bg.text.isNotBlank()) {
                    sb.append(bgTime).append(bg.text).append('\n')
                }
            }
        }

        return sb.toString()
    }

    // ✅ دقة 3 أرقام بعد الفاصلة [00:00.000]
    private fun formatLrcTime(time: Double): String {
        val ms = (time * 1000).toLong()
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val c = ms % 1000
        val sb = StringBuilder(12)
        sb.append('[')
        if (m < 10) sb.append('0')
        sb.append(m).append(':')
        if (s < 10) sb.append('0')
        sb.append(s).append('.')
        if (c < 100) sb.append('0')
        if (c < 10) sb.append('0')
        sb.append(c).append(']')
        return sb.toString()
    }

    private fun parseTime(time: String): Double {
        val t = time.trim()
        val c1 = t.indexOf(':')
        if (c1 != -1) {
            val c2 = t.lastIndexOf(':')
            return if (c1 == c2) {
                (t.substring(0, c1).toIntOrNull() ?: 0) * 60.0 +
                (t.substring(c1 + 1).toDoubleOrNull() ?: 0.0)
            } else {
                (t.substring(0, c1).toIntOrNull() ?: 0) * 3600.0 +
                (t.substring(c1 + 1, c2).toIntOrNull() ?: 0) * 60.0 +
                (t.substring(c2 + 1).toDoubleOrNull() ?: 0.0)
            }
        }
        if (t.endsWith("ms")) return (t.substring(0, t.length - 2).toDoubleOrNull() ?: 0.0) / 1000.0
        val s = if (t.endsWith("s") || t.endsWith("m") || t.endsWith("h")) t.substring(0, t.length - 1) else t
        val v = s.toDoubleOrNull() ?: 0.0
        return when {
            t.endsWith("m") -> v * 60.0
            t.endsWith("h") -> v * 3600.0
            else -> v
        }
    }
}
