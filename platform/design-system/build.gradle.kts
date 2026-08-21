plugins {
    id("omnideck.android.library")
    id("omnideck.compose")
}

android {
    namespace = "com.omnideck.designsystem"
}

description = "Shared Material 3 theme and components. Goal G4 — one look and feel across every module."

dependencies {
    api(libs.compose.material3)
    api(libs.compose.material3.adaptive)
    api(libs.compose.material3.windowsize)
    api(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
}
