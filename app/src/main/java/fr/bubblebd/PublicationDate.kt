package fr.bubblebd

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Keep partial dates partial: never invent a day or month from incomplete metadata. */
object PublicationDate {
    private val french=DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
    fun display(raw:String):String {
        val value=raw.trim()
        return runCatching {LocalDate.parse(value).format(french)}.getOrElse {
            if(Regex("\\d{4}-\\d{2}").matches(value))runCatching {YearMonth.parse(value).let {"%02d/%04d".format(it.monthValue,it.year)}}.getOrDefault(value)
            else value
        }
    }
    fun storage(raw:String):String? {
        val value=raw.trim()
        if(value.isBlank())return ""
        if(Regex("\\d{4}").matches(value))return value
        return runCatching {
            when {
                Regex("\\d{2}/\\d{2}/\\d{4}").matches(value)->LocalDate.parse(value,french).toString()
                Regex("\\d{2}/\\d{4}").matches(value)->YearMonth.of(value.takeLast(4).toInt(),value.take(2).toInt()).toString()
                Regex("\\d{4}-\\d{2}").matches(value)->YearMonth.parse(value).toString()
                else->LocalDate.parse(value).toString()
            }
        }.getOrNull()
    }
}
