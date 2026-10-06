package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class OctoberChangesTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun readingStatePersistsAndGuidedModeDimsOnlyOutside() {
        val repo=Repository(ctx)
        repo.savePrefs(repo.loadPrefs().copy(outsideDim=83));assertEquals(83,repo.loadPrefs().outsideDim)
        val b=Book("qa-october","","test.cbz","Test",readingState="read")
        repo.saveBooks(listOf(b))
        assertEquals("read",repo.loadBooks().single().readingState)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view=ComicView(ctx);view.focusTransitions=false;view.layout(0,0,400,600)
            val page=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
            val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
            view.setPage(page,listOf(Panel(.25f,.4f,.75f,.6f)),0)
            view.draw(Canvas(output))
            assertEquals(Color.WHITE,output.getPixel(200,300))
            assertTrue(Color.red(output.getPixel(200,50)) in 100..104)
            view.outsideDim=0;view.draw(Canvas(output));assertEquals(Color.WHITE,output.getPixel(200,50))
            view.outsideDim=100;view.draw(Canvas(output));assertEquals(Color.BLACK,output.getPixel(200,50));assertEquals(Color.WHITE,output.getPixel(200,300))
            view.fitPage();view.draw(Canvas(output))
            assertEquals(Color.WHITE,output.getPixel(200,50))
            page.recycle();output.recycle()
        }
    }
    @Test fun balloonOwnedByFollowingCaseIsDimmedWithPreviousCase() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view=ComicView(ctx);view.focusTransitions=false;view.layout(0,0,400,600)
            val page=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
            val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
            val hole=Panel(.4f,.35f,.6f,.45f)
            val upper=Panel(.25f,.1f,.75f,.5f,focusExclusions=listOf(hole))
            val lower=Panel(.1f,.35f,.9f,.9f)
            view.setPage(page,listOf(upper,lower),0)
            view.draw(Canvas(output))
            // Derive screen coordinates from the same reading frame, independent of device density.
            val frame=GuidedFrames.frame(listOf(upper,lower),0,400,600,400,600)
            val sx=200f-(frame.bounds.left+frame.bounds.right)*.5f*400*frame.scale
            val sy=300f-(frame.bounds.top+frame.bounds.bottom)*.5f*600*frame.scale
            val px=(sx+.5f*400*frame.scale).toInt();val py=(sy+.4f*600*frame.scale).toInt()
            assertTrue(Color.red(output.getPixel(px,py)) in 100..104)
            page.recycle();output.recycle()
        }
    }
    @Test fun focusFadeFinishesAndPageReturnCancelsIt() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        lateinit var view:ComicView
        val page=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            view=ComicView(ctx);view.layout(0,0,400,600)
            view.setPage(page,listOf(Panel(.25f,.4f,.75f,.6f)),0)
            view.draw(Canvas(output));assertEquals(Color.WHITE,output.getPixel(200,300))
        }
        android.os.SystemClock.sleep(250)
        instrumentation.runOnMainSync {
            view.draw(Canvas(output));assertTrue(Color.red(output.getPixel(200,50)) in 100..104)
            view.fitPage();view.setPage(page,listOf(Panel(.25f,.4f,.75f,.6f),Panel(.1f,.1f,.2f,.2f)),0)
            view.fitPage()
        }
        android.os.SystemClock.sleep(200)
        instrumentation.runOnMainSync {view.draw(Canvas(output));assertEquals(Color.WHITE,output.getPixel(200,50))}
        page.recycle();output.recycle()
    }
    @Test fun realRatingsRequireCountAndRetainSource() {
        val raw="""{"items":[{"id":"abc","volumeInfo":{"title":"Test","industryIdentifiers":[{"identifier":"9782808510899"}],"averageRating":4.3,"ratingsCount":17,"pageCount":48}}]}"""
        val c=ExtendedSources.parseGoogle(raw).single()
        assertEquals(4.3,c.details.rating!!,0.0)
        val merged=BibliographicSources.merge(Book("qa","","","Test"),BibliographicSources.Result(c.details,listOf(c.url)))
        assertEquals(c.url,merged.ratingSource)
        assertEquals(17,merged.reviewCount)
        assertNull(ExtendedSources.parseGoogle(raw.replace("\"ratingsCount\":17,","")).single().details.rating)
        val old=merged.copy(bdtheque="https://www.bdtheque.com/series/12/test")
        assertEquals(c.url,BdTheque.merge(old,BdTheque.Details(series="Test"),old.bdtheque).ratingSource)
        val ol=ExtendedSources.parseOpenLibrary("""{"docs":[{"key":"/works/OL12W","title":"Test","ratings_average":4.1,"ratings_count":8,"first_publish_year":1990,"isbn":["9782808510899"]}]}""").single()
        assertEquals("",ol.details.date);assertEquals("",ol.details.isbn)
        assertEquals(4.1,ol.details.rating!!,0.0)
        assertNotNull(ExtendedSources.select(listOf(ol),Book("qa","","","Test",isbn="9782808510899"),emptyList()))
    }
    @Test fun splashHasTransparentRoundedCorners() {
        val b=BitmapFactory.decodeResource(ctx.resources,R.drawable.splash_rounded)
        assertEquals(0,Color.alpha(b.getPixel(0,0)))
        assertEquals(255,Color.alpha(b.getPixel(b.width/2,b.height/2)))
        b.recycle()
    }
}
