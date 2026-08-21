plugins {
    `kotlin-dsl`
}

group = "com.omnideck.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// compileOnly is deliberate: AGP / Kotlin / KSP / Hilt are on the *consuming*
// build's classpath (declared `apply false` in the root build.gradle.kts).
// This avoids shipping two copies of AGP and is the standard convention-plugin
// pattern. See docs/adr/0008-monorepo-convention-plugins.md
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kotlin.composePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "omnideck.android.application"
            implementationClass = "com.omnideck.build.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "omnideck.android.library"
            implementationClass = "com.omnideck.build.AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "omnideck.android.feature"
            implementationClass = "com.omnideck.build.AndroidFeatureConventionPlugin"
        }
        register("omniModule") {
            id = "omnideck.module"
            implementationClass = "com.omnideck.build.OmniModuleConventionPlugin"
        }
        register("compose") {
            id = "omnideck.compose"
            implementationClass = "com.omnideck.build.ComposeConventionPlugin"
        }
        register("hilt") {
            id = "omnideck.hilt"
            implementationClass = "com.omnideck.build.HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "omnideck.jvm.library"
            implementationClass = "com.omnideck.build.JvmLibraryConventionPlugin"
        }
        register("quality") {
            id = "omnideck.quality"
            implementationClass = "com.omnideck.build.QualityConventionPlugin"
        }
    }
}
