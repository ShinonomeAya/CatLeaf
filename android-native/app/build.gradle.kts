import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// T24 发布签名：材料由用户保管（keystore.properties 与 keystore/ 均不入库）。
// 缺失时 release 会产出不签名 APK——apksigner 核对直接失败，不会假装已签名。
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "dev.local.catleaf.nativeapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.local.catleaf.nativeapp"
        minSdk = 29
        targetSdk = 36
        versionCode = 25
        versionName = "0.1.24"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 两阶段探针（跨进程持久化）依赖人工分次运行，默认套件跳过
        testInstrumentationRunnerArguments["notAnnotation"] = "dev.local.catleaf.nativeapp.ManualProbe"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                // storeFile 相对 app 模块目录解析：properties 里写的是 android-native/ 相对路径
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Room schema 导出到版本控制；迁移测试依赖它，禁止用破坏性迁移顶替
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.okhttp.mockwebserver)
    kspAndroidTest(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.readability4j)
    implementation(libs.androidx.webkit)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // JVM regressions exercise the same org.json API supplied by Android; this is never packaged in the app.
    testImplementation(libs.json.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // F18（独立验收）：译文排版用本地假数据在设备 UI 层验证，不依赖付费 AI
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
