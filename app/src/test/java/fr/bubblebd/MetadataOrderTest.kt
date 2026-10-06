package fr.bubblebd

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataOrderTest {
    @Test fun recentReadingPrecedesNewImportsAndUnopenedAlbumsUseImportDate() {
        val oldRead=Book("old-read","","","A",lastRead=10,added=1)
        val recentRead=Book("recent-read","","","Z",lastRead=20,added=2)
        val imported=Book("new-import","","","B",added=1000)
        val olderImport=Book("older-import","","","C",added=500)
        assertEquals(listOf(recentRead,oldRead,imported,olderImport),
            MetadataOrder.ordered(listOf(olderImport,imported,oldRead,recentRead)))
    }
    @Test fun equalDatesKeepStableOrderAcrossRepositoryReloads() {
        val a=Book("a","","","Z",lastRead=20,added=1)
        val b=Book("b","","","A",lastRead=20,added=1)
        assertEquals(listOf(a,b),MetadataOrder.ordered(listOf(b,a)))
        assertEquals(listOf(a,b),MetadataOrder.ordered(listOf(a,b)))
    }
}
