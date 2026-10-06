package fr.bubblebd

import java.util.Locale

/** Presentation only: never translate stored sort keys, bibliographic data or filenames. */
fun ui(value: String): String = UiText.translate(value, Locale.getDefault().language)

object UiText {
    private val placeholder = Regex("\\{(\\d+)\\}")
    private data class Template(val pattern: Regex, val indices: List<Int>, val english: String)
    private val exact = UiCatalog.entries.filterKeys { !placeholder.containsMatchIn(it) }
    private val templates = UiCatalog.entries.filterKeys { placeholder.containsMatchIn(it) }.map { (french, english) ->
        val slots = placeholder.findAll(french).toList()
        val pattern = buildString {
            append("^")
            var start = 0
            slots.forEach { slot ->
                append(Regex.escape(french.substring(start, slot.range.first)))
                append("(.*?)")
                start = slot.range.last + 1
            }
            append(Regex.escape(french.substring(start)))
            append("$")
        }
        Template(Regex(pattern, RegexOption.DOT_MATCHES_ALL), slots.map { it.groupValues[1].toInt() }, english)
    }

    fun translate(value: String, language: String): String {
        if (language.equals("fr", ignoreCase = true)) return value
        exact[value]?.let { return it }
        for (template in templates) {
            val match = template.pattern.matchEntire(value) ?: continue
            val values = template.indices.mapIndexed { index, slot -> slot to match.groupValues[index + 1] }.toMap()
            // Replace placeholders in the template, never inside an album's captured title.
            return placeholder.replace(template.english) { values[it.groupValues[1].toInt()].orEmpty() }
        }
        return value
    }
}
