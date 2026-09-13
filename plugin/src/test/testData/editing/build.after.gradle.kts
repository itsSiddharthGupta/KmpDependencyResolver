plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(kotlin("test")) // keep this comment
            implementation(libs.ktor.client.core)
        }
    }
}
