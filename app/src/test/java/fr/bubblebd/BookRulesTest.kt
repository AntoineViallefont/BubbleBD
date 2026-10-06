package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class BookRulesTest {
    @Test fun pageOrderIsNatural() {assertEquals(listOf("1.jpg","2.jpg","10.jpg","101.jpg"),listOf("101.jpg","10.jpg","2.jpg","1.jpg").sortedWith(BookRules::compareNatural))}
    @Test fun hugeVolumeNumbersDoNotOverflow() {assertTrue(BookRules.compareNatural("T99999999999999999999","T100000000000000000000")<0)}
    @Test fun unicodeSearchIsAccentInsensitive() {assertEquals("editeur",BookRules.normalized("Éditeur"))}
    @Test fun seriesSortKeepsVolumeOrder() {
        val books=listOf(Book("a","","","Z","Saga","10"),Book("b","","","B","Saga","2"),Book("c","","","C","Autre","1"))
        assertEquals(listOf("c","b","a"),BookRules.sort(books,"Série",false).map {it.id})
    }
    @Test fun progressHandlesUnreadAndEmptyBooks() {
        assertEquals(0,Book("a","","","",pages=50).progress)
        assertEquals(42,Book("a","","","",pages=50,page=20,started=true).progress)
        assertEquals(0,Book("a","","","",started=true).progress)
        assertEquals(100,Book("a","","","",pages=50,page=49,started=true).progress)
    }
    @Test fun mangaReversesWithinRowsOnly() {
        val a=Panel(0f,0f,.4f,.4f);val b=Panel(.6f,0f,1f,.4f);val c=Panel(0f,.6f,1f,1f)
        assertEquals(listOf(b,a,c),BookRules.orderPanels(listOf(c,a,b),true))
        assertEquals(listOf(a,b,c),BookRules.orderPanels(listOf(c,b,a),false))
    }
    @Test fun finishShortColumnBeforeTallPanel() {
        val a=Panel(0f,0f,.48f,.2f);val b=Panel(.52f,0f,1f,.2f)
        val wide=Panel(0f,.22f,1f,.45f)
        val c=Panel(0f,.48f,.48f,.7f);val d=Panel(0f,.73f,.48f,1f);val tall=Panel(.52f,.48f,1f,1f)
        val shuffled=listOf(tall,d,b,wide,c,a)
        assertEquals(listOf(a,b,wide,c,d,tall),BookRules.orderPanels(shuffled,false))
        assertEquals(listOf(b,a,wide,tall,c,d),BookRules.orderPanels(shuffled,true))
    }
    @Test fun wideGutterSeparatesIndependentBlocks() {
        val a=Panel(0f,0f,.44f,.48f);val b=Panel(0f,.49f,.16f,1f);val c=Panel(.17f,.49f,.44f,1f)
        val d=Panel(.53f,0f,1f,.48f);val e=Panel(.53f,.49f,.80f,1f);val f=Panel(.81f,.49f,1f,1f)
        assertEquals(listOf(a,b,c,d,e,f),BookRules.orderPanels(listOf(f,d,b,c,e,a),false))
        assertEquals(listOf(d,f,e,a,c,b),BookRules.orderPanels(listOf(f,d,b,c,e,a),true))
    }
    @Test fun wideEqualGridStillReadsByRowsWhenBothGuttersAreWide() {
        val a=Panel(0f,0f,.44f,.45f);val b=Panel(.56f,0f,1f,.45f)
        val c=Panel(0f,.55f,.44f,1f);val d=Panel(.56f,.55f,1f,1f)
        assertEquals(listOf(a,b,c,d),BookRules.orderPanels(listOf(d,b,c,a),false))
    }
    @Test fun equalGridReadsRowsBeforeNextRow() {
        val a=Panel(0f,0f,.48f,.48f);val b=Panel(.52f,0f,1f,.48f)
        val c=Panel(0f,.52f,.48f,1f);val d=Panel(.52f,.52f,1f,1f)
        assertEquals(listOf(a,b,c,d),BookRules.orderPanels(listOf(d,b,c,a),false))
    }

    @Test fun lastOpenedSortsSeriesByMostRecentReadButVolumesStayNatural() {
        val books=listOf(Book("a10","","","Dix",series="Série A",number="10",lastRead=100),Book("b","","","B",series="B",number="1",lastRead=80),Book("a2","","","Deux",series="Serie A",number="2",lastRead=0))
        val prefs=Preferences()
        assertEquals("Dernière ouverture",prefs.sort)
        val groups=BookRules.seriesGroups(BookRules.sort(books,prefs.sort,prefs.descending))
        assertEquals(2,groups.size)
        assertEquals(listOf("a2","a10"),groups[0].books.map {it.id})
        assertEquals("B",groups[1].title)
        assertTrue(groups[0].grouped)
        assertEquals("a2",groups[0].books.first().id)
        assertFalse(groups[1].grouped)
    }

}
