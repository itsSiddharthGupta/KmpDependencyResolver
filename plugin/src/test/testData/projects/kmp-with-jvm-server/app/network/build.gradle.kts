plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    android {
        namespace = "club.twentyfouram.socialmediaapp.network"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        androidMain.dependencies {
            implementation(libs.ktor.clientAndroid)
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.json.client)
        }
        iosMain.dependencies {
            implementation(libs.ktor.clientIos)
        }
    }
}
