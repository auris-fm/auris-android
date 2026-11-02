plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.com.shiftyjelly.pocketcasts.sharedtest"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(libs.automattic.crashlogging)
    api(libs.junit)

    implementation(libs.coroutines.test)
    implementation(libs.rx2.android)

    implementation(projects.modules.services.utils)

    // JvmTask implements the gms Task contract for JVM tests; implementation is enough: consumers
    // already compile against gms transitively, and this must never reach a production artifact.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
}
