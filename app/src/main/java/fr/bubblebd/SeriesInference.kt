package fr.bubblebd

/** Filename evidence is compared within a physical parent folder, never across unrelated folders. */
object SeriesInference {
    private data class Candidate(val book:Book,val series:String,val number:String,val position:Int)
    private fun clean(value:String)=value.replace('_',' ').replace(Regex("[.\\s]+")," ").trim(' ','-','[',']','(',')')
    fun infer(books:List<Book>):List<Book> {
        val candidates=books.filterNot {it.metadataEdited || it.seriesFromFile}.flatMap {b ->
            val stem=b.filename.substringBeforeLast('.').replace('_',' ')
            Regex("\\d{1,4}(?:[.,]\\d{1,2})?").findAll(stem).mapNotNull {m ->
                val prefix=stem.substring(0,m.range.first)
                val explicit=Regex("(?i)(?:^|[ .\\-\\[(])(?:t(?:ome)?|vol(?:ume)?|n[°o]?|#)[ .\\-]*$").find(prefix)
                val number=m.value.replace(',','.')
                if(explicit==null && number.toIntOrNull() in 1900..2099) return@mapNotNull null
                if(m.range.last+1<stem.length && stem[m.range.last+1].isDigit()) return@mapNotNull null
                var series=clean(if(explicit!=null)prefix.substring(0,explicit.range.first) else prefix)
                if(series.isBlank()) series=clean(b.parentName)
                if(series.isBlank() || BookRules.normalized(series) in setOf("bd","bds","comics","mangas","onedrive","telechargements")) return@mapNotNull null
                Candidate(b,series,number.substringBefore('.').trimStart('0').ifBlank {"0"} + if('.' in number)"."+number.substringAfter('.') else "",m.range.first)
            }.toList()
        }
        val groups=candidates.groupBy { (it.book.parentFolder.ifBlank {it.book.folder}) to BookRules.normalized(it.series) }
            .values.filter {g -> g.map {it.book.id}.distinct().size>=2 && g.map {it.number}.distinct().size>=2}
        val choices=groups.sortedByDescending {it.map {c->c.book.id}.distinct().size}.flatMap {it.sortedBy {c->c.position}}
            .groupBy {it.book.id}.mapValues {it.value.first()}
        return books.map {b -> choices[b.id]?.let {b.copy(series=it.series,number=it.number)} ?: b}
    }
}
