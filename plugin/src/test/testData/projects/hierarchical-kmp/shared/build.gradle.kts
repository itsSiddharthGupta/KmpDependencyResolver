plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kmpdependencyresolver.smoke"
    compileSdk = 35
}

kotlin {
    androidTarget()
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies { implementation(libs.ktor.client.core) }
        val iosMain by creating { dependsOn(commonMain.get()) }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)
        androidMain.dependencies { implementation("androidx.core:core-ktx:1.15.0") }
        jvmMain.dependencies {}
    }
}
