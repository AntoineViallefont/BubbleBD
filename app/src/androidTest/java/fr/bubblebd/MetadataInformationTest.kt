package fr.bubblebd

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MetadataInformationTest {
    private val book=Book("private-id","content://private-document","private.cbz","Saga test - T01",series="Saga test",number="01",pages=58,writer="Correction utilisateur",synopsis="Résumé conservé")
    private fun response()=JSONObject().put("matched",true).put("series","Saga test").put("number","1").put("title","Titre du tome")
        .put("writer","Suggestion IA").put("artist","Artiste fictif").put("date","2020-04-12").put("publisher","Éditeur fictif")
        .put("synopsis","Nouveau résumé").put("sources",JSONArray().put(JSONObject().put("url","https://publisher.example/tome-1")))
        .put("searchAttribution","<div>Recherche Google</div>")
    @Test fun publicIdentityRequiresNeitherAccountNorPrivateDocument() {
        val identity=MetadataInformation.identity(book)
        assertEquals(setOf("title","series","number","isbn","artist","writer","publisher","date","genre","synopsis","knownSources"),identity.keys)
        assertEquals("Saga test",identity["series"]);assertEquals("01",identity["number"])
        assertFalse(identity.values.any {it.contains("private")})
        assertEquals(book.writer,identity["writer"])
        assertEquals(book.synopsis,identity["synopsis"])
        val multiline=MetadataInformation.identity(book.copy(artist="Louis\nAlloing",writer="Leo\tRodolphe",genre="BD\r\nAventure"))
        assertEquals("Louis Alloing",multiline["artist"]);assertEquals("Leo Rodolphe",multiline["writer"])
        assertFalse(multiline.values.any {it.any {c->c=='\n' || c=='\r' || c=='\t'}})
    }
    @Test fun coverIdentificationRequiresRequestedIdentityAndSourcesAndExactKnownTome() {
        val unknown=book.copy(title="scan inconnu",series="",number="",writer="")
        val found=response().put("identifiedFromCover",true).put("requestedIdentity",JSONObject(MetadataInformation.coreIdentity(unknown)))
        assertNotNull(MetadataInformation.parse(found.toString(),unknown))
        assertNull(MetadataInformation.parse(found.put("requestedIdentity",JSONObject().put("title","Un autre fichier")).toString(),unknown))
        assertNull(MetadataInformation.parse(response().put("identifiedFromCover",true).put("requestedIdentity",JSONObject(MetadataInformation.coreIdentity(book))).put("number","2").toString(),book))
    }
    @Test fun refusesAnotherTomeUncitedAndIncompleteIdentity() {
        assertNull(MetadataInformation.parse(response().put("number","2").toString(),book))
        assertNull(MetadataInformation.parse(response().put("series","Autre saga").toString(),book))
        assertNull(MetadataInformation.parse(response().put("sources",JSONArray()).toString(),book))
        assertNull(MetadataInformation.parse(response().put("searchAttribution","").toString(),book))
    }
    @Test fun allRequestedWebSourcesAcceptedWhenTheyIdentifyTheCorrectTome() {
        for(url in listOf("https://www.bdtheque.com/series/1/test","https://m.bedetheque.com/BD-test.html","https://fr.wikipedia.org/wiki/Test","https://www.amazon.fr/dp/9056460021")) {
            val raw=response().put("sources",JSONArray().put(JSONObject().put("url",url)))
            assertEquals(listOf(url),MetadataInformation.parse(raw.toString(),book)!!.details.urls)
            assertNull(MetadataInformation.parse(raw.put("number","2").toString(),book))
        }
        val unsafe=response().put("sources",JSONArray().put(JSONObject().put("url","https://user@www.bdtheque.com/test")))
        assertNull(MetadataInformation.parse(unsafe.toString(),book))
    }
    @Test fun fillsMissingFieldsButPreservesCorrectionsProgressAndSources() {
        val old=book.copy(page=12,started=true,readingState="read",metadataSource="https://catalogue.bnf.fr/ark:/12148/test")
        val parsed=MetadataInformation.parse(response().toString(),old)!!
        val merged=MetadataInformation.merge(old,parsed)
        assertEquals("Suggestion IA",merged.writer);assertEquals("Nouveau résumé",merged.synopsis)
        assertEquals("Artiste fictif",merged.artist);assertEquals(12,merged.page);assertEquals(58,merged.pages);assertEquals("read",merged.readingState)
        assertTrue(merged.metadataSource.contains(old.metadataSource));assertTrue(merged.metadataSource.contains("publisher.example"))
        val repo=Repository(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.saveBooks(listOf(merged));assertEquals(merged,repo.loadBooks().single())
    }
    @Test fun invalidDatesAndRatingsAreNotInvented() {
        val result=MetadataInformation.parse(response().put("date","2020-99-99").put("rating",4.7).toString(),book)!!
        assertEquals("",result.details.details.date);assertNull(result.details.details.rating)
    }
    @Test fun unsafeEndpointCannotCarryCredentialsOrPlaintextTraffic() {
        assertFalse(MetadataInformation.validEndpoint("http://host.example"))
        assertFalse(MetadataInformation.validEndpoint("https://key@host.example"))
        assertFalse(MetadataInformation.validEndpoint("https://host.example?key=secret"))
        assertTrue(MetadataInformation.validEndpoint("https://catalog.run.app"))
    }
    @Test fun deployedServiceWorksWithoutReaderAccount()=kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("metadataLive")=="true")
        val identity=Book("live-test","content://not-transmitted","not-transmitted.pdf","Demain - T02",series="Demain",number="02")
        val raw=MetadataInformation.fetch(identity)
        val parsed=MetadataInformation.parse(raw,identity)!!
        assertEquals("Delcourt",parsed.details.details.publisher)
        assertEquals("9782413047551",parsed.details.details.isbn)
        assertTrue(parsed.details.urls.any {it.startsWith("https://www.editions-delcourt.fr/")})
        val file=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"metadata-live-result.json")
        file.writeText(raw)
    }
    @Test fun publicCatalogUsesAReferenceEditionWithoutReaderAccount()=kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("metadataLive")=="true")
        val identity=Book("live-catalog","content://not-transmitted","not-transmitted.pdf","Furieuse")
        val cache=mutableMapOf<String,List<BibliographicSources.Entry>>()
        val found=BibliographicSources.bnf(identity,cache)!!
        assertEquals(found,BibliographicSources.bnf(identity.copy(title="Furieuse (Nouvelle édition)"),cache))
        assertEquals("Geoffroy Monde",found.details.writer)
        assertEquals("Mathieu Burniat",found.details.artist)
        assertEquals("Dargaud",found.details.publisher)
        assertEquals("2022",found.details.date)
        assertTrue(BibliographicSources.validIsbn(found.details.isbn))
        assertTrue(found.urls.all {it.startsWith("https://catalogue.bnf.fr/ark:/")})
        val file=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"metadata-catalog-live-result.json")
        file.writeText(JSONObject().put("title",found.details.title).put("writer",found.details.writer)
            .put("artist",found.details.artist).put("publisher",found.details.publisher).put("date",found.details.date)
            .put("isbn",found.details.isbn).put("genre",found.details.genre).put("sources",JSONArray(found.urls)).toString())
    }
    @Test fun newCatalogRulesRetryIncompleteAlbumsWithoutDiscardingOldChecks() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=ctx.getSharedPreferences("bubblebd",android.content.Context.MODE_PRIVATE)
        val id="test-catalog-retry"
        val now=System.currentTimeMillis()
        prefs.edit().putLong("metadataChecked:v5:"+id,now).putLong("metadataChecked:v6:"+id,now).remove("metadataChecked:v7:"+id).commit()
        val repo=Repository(ctx)
        assertTrue(repo.metadataDue(id))
        repo.metadataChecked(id);assertFalse(repo.metadataDue(id))
        assertEquals(now,prefs.getLong("metadataChecked:v5:"+id,0));assertEquals(now,prefs.getLong("metadataChecked:v6:"+id,0))
        repo.metadataInvalidate(id);assertTrue(repo.metadataDue(id))
        prefs.edit().remove("metadataChecked:v5:"+id).remove("metadataChecked:v6:"+id).remove("metadataChecked:v7:"+id).commit()
    }

    @Test fun encyclopediaFindsXhgC3WithoutAnAiQuotaOrReaderAccount()=kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("metadataLive")=="true")
        val identity=Book("live-encyclopedia","content://not-transmitted","XHG-C3.pdf","XHG-C3")
        val result=WebBibliographicSources.wikipedia(identity,mutableMapOf())!!
        assertEquals("William Vance",result.details.writer);assertEquals("William Vance",result.details.artist)
        assertEquals("Gibraltar",result.details.publisher);assertEquals("1995",result.details.date)
        assertEquals("9056460021",result.details.isbn)
        assertTrue(result.details.synopsis.isNotBlank())
        assertTrue(result.urls.single().startsWith("https://fr.wikipedia.org/wiki/XHG-C3"))
    }

    @Test fun publicCatalogFindsIntegralAndSummaryWithoutAiQuota()=kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("metadataLive")=="true")
        val identity=Book("live-integral","content://not-transmitted","Soleil froid INT.pdf","Soleil froid INT")
        val found=BibliographicSources.bnf(identity,mutableMapOf())!!
        assertEquals("Jean-Pierre Pécau",found.details.writer);assertEquals("Damien",found.details.artist)
        assertEquals("Delcourt",found.details.publisher);assertEquals("2023",found.details.date)
        assertEquals("978-2-413-03869-6",found.details.isbn)
        assertTrue(found.details.synopsis.contains("robot de portage"))
        assertEquals(listOf("https://catalogue.bnf.fr/ark:/12148/cb47239530w"),found.urls)
        val publisher=PublisherSources.delcourt(BibliographicSources.merge(identity,found),mutableMapOf())!!
        assertEquals("2023-03-22",publisher.details.date)
        assertTrue(publisher.details.synopsis.contains("robot de portage"))
        assertTrue(publisher.details.genre.isNotBlank())
        val merged=BibliographicSources.merge(BibliographicSources.merge(identity,publisher),found)
        val file=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"metadata-integral-live-result.json")
        file.writeText(JSONObject().put("writer",merged.writer).put("artist",merged.artist).put("publisher",merged.publisher)
            .put("date",merged.date).put("isbn",merged.isbn).put("genre",merged.genre).put("synopsis",merged.synopsis).put("sources",merged.metadataSource).toString())
        val repo=Repository(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.saveBooks(listOf(merged));assertEquals(merged,repo.loadBooks().single())
    }

    @Test fun tomeInTitleIsUsedForServiceIdentityAndRejectsOtherTomes() {
        val embedded=book.copy(title="Saga test - Tome 01 - Album",series="",number="")
        assertEquals("1",MetadataInformation.coreIdentity(embedded)["number"])
        assertEquals("Saga test",MetadataInformation.coreIdentity(embedded)["series"])
        assertNotNull(MetadataInformation.parse(response().toString(),embedded))
        assertNull(MetadataInformation.parse(response().put("number","2").toString(),embedded))
        val structured=embedded.copy(series="Saga test",number="2")
        assertEquals("2",MetadataInformation.coreIdentity(structured)["number"])
        assertNull(MetadataInformation.parse(response().toString(),structured))
    }

}
