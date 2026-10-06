package fr.bubblebd

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class OpeningTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun nativeLaunchContainsLogoAndBranding() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=31)
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val theme=android.view.ContextThemeWrapper(ctx,R.style.Theme_BubbleBD)
        val value=android.util.TypedValue()
        org.junit.Assert.assertTrue(theme.theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon,value,true))
        org.junit.Assert.assertEquals(R.drawable.splash_icon,value.resourceId)
        org.junit.Assert.assertTrue(theme.theme.resolveAttribute(android.R.attr.windowSplashScreenBrandingImage,value,true))
        org.junit.Assert.assertEquals(R.drawable.launch_branding,value.resourceId)
        val branding=theme.getDrawable(value.resourceId)!!
        val bitmap=Bitmap.createBitmap(600,240,Bitmap.Config.ARGB_8888)
        branding.setBounds(0,0,600,240);branding.draw(android.graphics.Canvas(bitmap))
        val pixels=IntArray(600*240);bitmap.getPixels(pixels,0,600,0,0,600,240)
        // Both title and version must contain visible glyphs.
        org.junit.Assert.assertTrue(pixels.take(600*115).any {android.graphics.Color.alpha(it)>0})
        org.junit.Assert.assertTrue(pixels.drop(600*120).any {android.graphics.Color.alpha(it)>0})
        bitmap.recycle()
    }
    @Test fun homeDoesNotShowASecondSplashOrWaitForATimer() {
        compose.mainClock.autoAdvance=false
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10000) {compose.onAllNodesWithText("Accueil").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME}").assertDoesNotExist()
            compose.onNodeWithContentDescription("Icône Bubble BD").assertDoesNotExist()
        }
    }
}
