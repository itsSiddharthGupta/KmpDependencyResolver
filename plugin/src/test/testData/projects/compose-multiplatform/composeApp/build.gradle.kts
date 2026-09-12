plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.compose) }
kotlin {
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework { baseName = "ComposeApp" }
    }
    sourceSets {
        commonMain.dependencies { implementation(compose.runtime) }
        iosMain.dependencies {}
    }
}
