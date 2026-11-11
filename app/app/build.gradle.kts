import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 비밀값(ITS 키)은 local.properties 에만 둔다. 저장소에는 올리지 않는다.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 공개 저장소에 올리는 APK는 -PnoItsKey 로 키 없이 빌드한다
val itsKey: String = if (project.hasProperty("noItsKey")) "" else localProps.getProperty("its.apiKey", "")

android {
    namespace = "io.lanealert"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.lanealert"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "ITS_API_KEY", "\"$itsKey\"")
    }

    signingConfigs {
        // 공모전 심사용 release 빌드. 키스토어는 저장소에 넣지 않는다(.gitignore).
        create("release") {
            val ks = rootProject.file("release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = localProps.getProperty("release.storePassword", "")
                keyAlias = localProps.getProperty("release.keyAlias", "lanealert")
                keyPassword = localProps.getProperty("release.keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // AAOS 표준 차속(PERF_VEHICLE_SPEED)을 읽기 위한 android.car 라이브러리
    useLibrary("android.car")

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Pleos Connect SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
