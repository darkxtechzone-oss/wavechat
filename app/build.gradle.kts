plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.darkx.wave"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.darkx.wave"
        minSdk = 29; targetSdk = 34
        val vc = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionCode = vc
        versionName = "1.$vc"
    }
    buildFeatures { buildConfig = true }
    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/native-image/**")
        }
    }
    signingConfigs {
        create("dx") {
            storeFile = file("darkxyt.p12")
            storePassword = "darkxyt"
            keyAlias = "darkxyt"
            keyPassword = "darkxyt"
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("dx") } }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core:1.12.0")
    implementation("org.mongodb:mongodb-driver-sync:4.11.2") {
        exclude(group = "org.mongodb", module = "bson-record-codec")
    }
}
