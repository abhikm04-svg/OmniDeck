plugins {
    id("omnideck.android.library")
}

android {
    namespace = "com.omnideck.core"
}

description = "Small, dependency-light primitives shared by every layer: dispatchers, Result, Clock."

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
