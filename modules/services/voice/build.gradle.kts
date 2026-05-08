plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "au.com.shiftyjelly.pocketcasts.voice"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.hilt.compiler)

    api(libs.dagger.hilt.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.vosk.android)
    implementation(libs.timber)

    implementation(projects.modules.services.analytics)
    implementation(projects.modules.services.coroutines)
    implementation(projects.modules.services.localization)
    implementation(projects.modules.services.preferences)
    implementation(projects.modules.services.repositories)
    implementation(projects.modules.services.utils)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(projects.modules.services.sharedtest)
}
