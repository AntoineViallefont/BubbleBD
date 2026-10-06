package fr.bubblebd

/** Search clues only: never rename the album or alter its library/progress data. */
object BibliographicIdentity {
    fun titleKey(value:String)=BookRules.normalized(value)
        .replace(Regex("(?i)\\b(?:nouvelle|nouvel|new)\\s+[eé]dition\\b"),"")
        .replace(Regex("[^a-z0-9]+")," ").trim()
        .replace(Regex("\\bint$"),"integrale")
    fun titleMatches(a:String,b:String):Boolean {
        fun withoutArticle(text:String)=titleKey(text).replace(Regex("^(?:l|le|la|les|un|une)\\s+"),"")
        return titleKey(a)==titleKey(b) || withoutArticle(a).length>=4 && withoutArticle(a)==withoutArticle(b)
    }
    private val marker=Regex("(?i)(?:^|[ ._\\-–(\\[])(?:tome|t|vol(?:ume)?|n[°o]|#)[ ._\\-]*(\\d{1,4})(?!\\d)")
    fun forLookup(book:Book):Book {
        if(BibliographicSources.integralTitle(book)!=null)return book
        val evidence=listOf(book.title,book.filename.substringBeforeLast('.').replace('_',' '))
        for(text in evidence) {
            val matches=marker.findAll(text).toList()
            if(matches.size!=1)continue // Ranges and multiple tome clues remain ambiguous.
            val match=matches.single();val number=match.groupValues[1].toIntOrNull()?.toString() ?: continue
            if(book.number.isNotBlank() && book.number.toIntOrNull()?.toString()!=number)return book
            val prefix=text.substring(0,match.range.first).trim(' ','-','–','.', '_','(', '[')
            val series=book.series.ifBlank {prefix}
            if(series.isBlank())continue
            return book.copy(series=series,number=book.number.ifBlank {number})
        }
        return book
    }
}
