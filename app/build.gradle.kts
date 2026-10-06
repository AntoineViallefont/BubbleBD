import javax.imageio.ImageIO
import java.awt.AlphaComposite
import java.awt.geom.RoundRectangle2D
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val bubbleVersion = "0.3.32"
android {
    namespace = "fr.bubblebd"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "fr.bubblebd"
        minSdk = 26
        targetSdk = 36
        versionCode = 37
        versionName = bubbleVersion
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        val oneDriveClientId = local.getProperty("onedrive.clientId", "")
        require(oneDriveClientId.isBlank() || oneDriveClientId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Identifiant OneDrive invalide" }
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"$oneDriveClientId\"")
        val metadataEndpoint = local.getProperty("metadata.endpoint", "")
        require(metadataEndpoint.isBlank() || metadataEndpoint.matches(Regex("https://[A-Za-z0-9.-]+(?:/[A-Za-z0-9_/-]*)?"))) { "Adresse du service bibliographique invalide" }
        buildConfigField("String", "METADATA_ENDPOINT", "\"$metadataEndpoint\"")
        manifestPlaceholders["appAuthRedirectScheme"] = "fr.bubblebd.auth"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("net.openid:appauth:0.11.1")
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Native splash branding is regenerated from the APK version, never maintained by hand.
val launchBrandingDirectory = layout.buildDirectory.dir("generated/launchBranding/res")
val generateLaunchBranding by tasks.registering {
    inputs.property("version", bubbleVersion)
    inputs.file("src/main/res/drawable-nodpi/bubble_launcher.png")
    outputs.dir(launchBrandingDirectory)
    doLast {
        val original=ImageIO.read(file("src/main/res/drawable-nodpi/bubble_launcher.png"))
        val rounded=BufferedImage(original.width,original.height,BufferedImage.TYPE_INT_ARGB)
        val iconGraphics=rounded.createGraphics()
        iconGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
        val radius=original.width*.18
        iconGraphics.color=Color.WHITE
        iconGraphics.fill(RoundRectangle2D.Double(0.0,0.0,original.width.toDouble(),original.height.toDouble(),radius*2,radius*2))
        iconGraphics.composite=AlphaComposite.SrcIn
        iconGraphics.drawImage(original,0,0,null)
        iconGraphics.dispose()
        val iconOutput=launchBrandingDirectory.get().file("drawable-nodpi/splash_rounded.png").asFile
        iconOutput.parentFile.mkdirs()
        ImageIO.write(rounded,"png",iconOutput)
        for (night in listOf(false, true)) {
            val image = BufferedImage(600, 240, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.font = Font("SansSerif", Font.BOLD, 72)
            val left = (600 - graphics.fontMetrics.stringWidth("Bubble BD")) / 2
            graphics.color = Color(0x1368B5)
            graphics.drawString("Bubble ", left, 100)
            graphics.color = Color(0xC62836)
            graphics.drawString("BD", left + graphics.fontMetrics.stringWidth("Bubble "), 100)
            graphics.font = Font("SansSerif", Font.PLAIN, 42)
            graphics.color = Color(if (night) 0xB4BECF else 0x526176)
            val version = "Version $bubbleVersion"
            graphics.drawString(version, (600 - graphics.fontMetrics.stringWidth(version)) / 2, 172)
            graphics.dispose()
            val qualifier = if (night) "drawable-night-xxhdpi" else "drawable-xxhdpi"
            val output = launchBrandingDirectory.get().file("$qualifier/launch_branding.png").asFile
            output.parentFile.mkdirs()
            ImageIO.write(image, "png", output)
        }
    }
}
android.sourceSets.getByName("main").res.srcDir(launchBrandingDirectory)
tasks.named("preBuild").configure { dependsOn(generateLaunchBranding) }
