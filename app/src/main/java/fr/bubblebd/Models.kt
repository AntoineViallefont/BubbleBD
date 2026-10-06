package fr.bubblebd

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class Book(
    val id: String, val uri: String, val filename: String,
    val title: String, val series: String = "", val number: String = "",
    val date: String = "", val genre: String = "", val artist: String = "",
    val writer: String = "", val publisher: String = "", val isbn: String = "",
    val pages: Int = 0, val page: Int = 0, val started: Boolean = false,
    val added: Long = System.currentTimeMillis(), val lastRead: Long = 0,
    val folder: String = "", val cover: String = "", val pinned: Boolean = false,
    val bdtheque: String = "", val bdovore: String = "", val issue: String = "",
    val demo: Boolean = false, val metadataEdited: Boolean = false,
    val synopsis: String = "", val metadataSource: String = "", val metadataNote: String = "",
    val cloud:Boolean=false, val parentFolder:String="", val parentName:String="",
    val seriesFromFile:Boolean=false, val coverIssue:String="",
    val readingState:String="", val rating:Double?=null,val ratingScale:Double?=null,val reviewCount:Int?=null,val ratingSource:String="",val metadataAttribution:String="",val metadataLocked:Boolean=false,val metadataRevision:Long=0
) {
    val displayTitle get():String {
        val s=series.trim();val t=title.trim()
        return when {
            s.isBlank() -> t
            t.isBlank() || BibliographicIdentity.titleMatches(s,t) -> s
            BookRules.normalized(t).startsWith(BookRules.normalized(s)+" - ") -> t
            else -> "$s - $t"
        }
    }
    val local get() = !cloud || pinned
    val progress get() = if(readingState=="read")100 else if (!started || pages == 0) 0 else (((page + 1) * 100f / pages).toInt()).coerceIn(1,100)
    val status get() = if(readingState=="to_read") "À lire" else if(readingState=="read") "Lu" else if (!started) "Non lu" else if (progress == 100) "Terminé" else "$progress % lu"
    val volume get() = if (number.isBlank()) "" else "Tome $number"
}
data class SourceFolder(val uri: String, val name: String, val count: Int = 0, val scanned: Long = 0, val error: String = "")
data class Preferences(val theme: String = "system", val rtl: Boolean = false, val grid: Boolean = true, val columns: Int = 3, val sort: String = "Dernière ouverture", val descending: Boolean = false, val cacheMb: Int = 512, val homeGrid: Boolean = true, val outsideDim: Int = 60)
data class PanelPoint(val x:Float,val y:Float)
data class Panel(val left: Float, val top: Float, val right: Float, val bottom: Float, val readingOrderBounds: Panel? = null, val focusExclusions:List<Panel> = emptyList(), val jointFocus:List<Panel> = emptyList(), val focusOutline:List<PanelPoint> = emptyList(), val focusIncludes:List<Panel> = emptyList(), val foregroundOverhang:Boolean = false) {
    val isWholePage get() = left==0f && top==0f && right==1f && bottom==1f
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}
object BookRules {
    private val tokens = Regex("\\d+|\\D+")
    fun normalized(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    fun compareNatural(a: String, b: String): Int {
        val x = tokens.findAll(normalized(a)).map { it.value }.toList()
        val y = tokens.findAll(normalized(b)).map { it.value }.toList()
        for (i in 0 until minOf(x.size,y.size)) {
            val u=x[i]; val v=y[i]
            val c = if (u.first().isDigit() && v.first().isDigit()) {
                val un=u.trimStart('0').ifEmpty { "0" }; val vn=v.trimStart('0').ifEmpty { "0" }
                un.length.compareTo(vn.length).takeIf { it != 0 } ?: un.compareTo(vn)
            } else u.compareTo(v)
            if (c!=0) return c
        }
        return x.size.compareTo(y.size)
    }
    fun sort(books: List<Book>, field: String, descending: Boolean): List<Book> {
        val comparator = Comparator<Book> { a,b ->
            val first = when(field) {
                "Dernière ouverture" -> b.lastRead.compareTo(a.lastRead)
                "Ajout" -> a.added.compareTo(b.added)
                "Progression" -> a.progress.compareTo(b.progress)
                else -> compareNatural(key(a,field),key(b,field))
            }
            first.takeIf { it!=0 } ?: compareNatural(a.number,b.number).takeIf { it!=0 } ?: compareNatural(a.title,b.title)
        }
        return books.sortedWith(if(descending) comparator.reversed() else comparator)
    }
    private fun key(b:Book, field:String) = when(field) { "Titre"->b.title; "N°"->b.number; "Parution"->b.date; "Genre"->b.genre; "Dessinateur"->b.artist; "Scénariste"->b.writer; "Éditeur"->b.publisher; else->b.series.ifBlank { b.title } }
    data class SeriesGroup(val key:String,val title:String,val grouped:Boolean,val books:List<Book>)
    fun seriesGroups(ordered:List<Book>):List<SeriesGroup> = ordered.groupBy {b ->
        if(b.series.isBlank()) "album:${b.id}" else "series:${normalized(b.series).trim()}"
    }.map {(key,books) ->
        SeriesGroup(key,books.first().series.ifBlank {books.first().title},books.size>1 && books.first().series.isNotBlank(),books.sortedWith {a,b ->compareNatural(a.number,b.number).takeIf {it!=0} ?: compareNatural(a.title,b.title)})
    }
    fun orderPanels(panels: List<Panel>, rtl: Boolean): List<Panel> {
        // Cut complete horizontal bands first, then columns inside a band.
        // A tall panel links adjacent rows: finish the short column before reading it.
        fun order(group:List<Panel>):List<Panel> {
            if(group.size<2) return group
            // A wide gutter separates two independent blocks; finish one block
            // before crossing it. Equal grids still follow their horizontal rows.
            val horizontal=group.sortedBy {it.top}
            var lower=horizontal.first().bottom;var rowGap=0f
            for(p in horizontal.drop(1)) {rowGap=maxOf(rowGap,p.top-lower);lower=maxOf(lower,p.bottom)}
            val columns=group.sortedBy {it.left}
            var edge=columns.first().right
            for(i in 1 until columns.size) {
                val gap=columns[i].left-edge
                val left=columns.take(i);val right=columns.drop(i)
                if(gap>.045f && gap>rowGap*3f && left.size>=2 && right.size>=2 &&
                    abs(left.minOf {it.top}-right.minOf {it.top})<.03f &&
                    abs(left.maxOf {it.bottom}-right.maxOf {it.bottom})<.03f) {
                    return if(rtl)order(right)+order(left) else order(left)+order(right)
                }
                edge=maxOf(edge,columns[i].right)
            }
            for(horizontal in listOf(true,false)) {
                val sorted=group.sortedBy {if(horizontal)it.top else it.left}
                var edge=if(horizontal)sorted[0].bottom else sorted[0].right
                for(i in 1 until sorted.size) {
                    val start=if(horizontal)sorted[i].top else sorted[i].left
                    if(start>=edge-.021f) {
                        val first=order(sorted.take(i));val second=order(sorted.drop(i))
                        return if(!horizontal && rtl) second+first else first+second
                    }
                    edge=maxOf(edge,if(horizontal)sorted[i].bottom else sorted[i].right)
                }
            }
            // Overlapping/inset panels without a clean cut: stable row order.
            val rows=mutableListOf<MutableList<Panel>>()
            group.sortedBy {it.top}.forEach {p ->
                val row=rows.lastOrNull()
                if(row!=null && abs(row.first().top-p.top)<minOf(row.first().height,p.height)*.35f) row.add(p)
                else rows.add(mutableListOf(p))
            }
            return rows.flatMap {if(rtl)it.sortedByDescending {p->p.left} else it.sortedBy {p->p.left}}
        }
        val geometry=panels.map {it.readingOrderBounds ?: it}
        val sorted=order(geometry).toMutableList()
        // A small framed reaction crossing the boundary of two neighbours is
        // read between them, rather than after the right-hand scene.
        fun area(p:Panel)=p.width*p.height
        fun shared(a:Panel,b:Panel)=maxOf(0f,minOf(a.right,b.right)-maxOf(a.left,b.left))*maxOf(0f,minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
        for(inset in geometry) {
            val neighbours=geometry.filter {p ->p!==inset && area(inset)<area(p)*.25f && shared(inset,p)/area(inset)>.10f &&
                inset.top>p.top+.025f && inset.bottom<p.bottom-.015f}.sortedBy {it.left}
            if(neighbours.size!=2)continue
            val a=neighbours[0];val b=neighbours[1]
            if(abs(a.top-b.top)>.025f || b.left-a.right !in -.01f.. .035f)continue
            val first=if(rtl)b else a;val second=if(rtl)a else b
            if(sorted.indexOf(first)>=sorted.indexOf(second))continue
            sorted.remove(inset);sorted.add(sorted.indexOf(second),inset)
        }
        return sorted.map {p -> panels[geometry.indexOf(p)]}
    }
}
