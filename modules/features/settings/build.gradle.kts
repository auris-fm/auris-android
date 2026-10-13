import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

android {
    namespace = "au.com.shiftyjelly.pocketcasts.settings"
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }
}

/**
 * Writes the About page's build timestamp as a resource.
 *
 * The timestamp has to be produced when the task runs, not while the build script is configured: the
 * configuration cache would freeze a value computed at configuration time and replay it, so every build
 * would report the first timestamp it ever saw. A generated resource is used instead of a BuildConfig
 * field for the same reason.
 */
abstract class GenerateBuildDateTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val valuesDir = outputDirectory.get().asFile.resolve("values")
        valuesDir.mkdirs()
        // The numeric offset is included so the wall-clock reading isn't ambiguous across timezones.
        val buildDate = SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.US).format(Date())
        valuesDir.resolve("build_date.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="build_date" translatable="false">$buildDate</string>
            </resources>
            """.trimIndent() + "\n",
        )
    }
}

val generateBuildDate = tasks.register<GenerateBuildDateTask>("generateBuildDate") {
    // A build timestamp changes on every build by definition, so there is nothing for the up-to-date
    // check or the build cache to reuse.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateBuildDate, GenerateBuildDateTask::outputDirectory)
    }
}

dependencies {
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.moshi.kotlin.codegen)

    api(libs.androidx.appcompat)
    api(libs.androidx.cardview)
    api(libs.androidx.constraintlayout)
    api(libs.androidx.preference.ktx)
    api(libs.androidx.recyclerview)
    api(libs.automattic.crashlogging)
    api(libs.dagger.hilt.android)
    api(libs.material)

    api(projects.modules.services.analytics)
    api(projects.modules.services.compose)
    api(projects.modules.services.coroutines)
    api(projects.modules.services.model)
    api(projects.modules.services.preferences)
    api(projects.modules.services.repositories)
    api(projects.modules.services.servers)
    api(projects.modules.services.ui)
    api(projects.modules.services.utils)
    api(projects.modules.services.views)

    implementation(platform(libs.compose.bom))
    implementation(platform(libs.firebase.bom))

    implementation(libs.aboutlibraries.compose)
    implementation(libs.aboutlibraries.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.animation)
    implementation(libs.compose.material)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.rxjava2)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.webview)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.rx2)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.config)
    implementation(libs.fragment.compose)
    implementation(libs.fragment.ktx)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.reactivestreams.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.material.dialogs)
    implementation(libs.okhttp)
    implementation(libs.play.cast)
    implementation(libs.rx2.android)
    implementation(libs.rx2.java)
    implementation(libs.rx2.kotlin)
    implementation(libs.rx2.relay)
    implementation(libs.timber)

    implementation(projects.modules.services.images)
    implementation(projects.modules.services.localization)
    implementation(projects.modules.services.voice)

    debugImplementation(libs.compose.ui.tooling)

    debugProdImplementation(libs.compose.ui.tooling)

    testImplementation(libs.androidx.arch.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.turbine)

    testImplementation(projects.modules.services.sharedtest)
    testImplementation(projects.modules.services.analytics.testing)
}
