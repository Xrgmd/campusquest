plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.campusquest.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.campusquest.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "0.4.2"
    }

    // �̶�ǩ��:0.2.2~0.2.6 ȫ���ñ��� debug.keystore ǩ��(SHA-256 e41194a3��2937)��
    // ����� keystore ��������,�κλ���/�κ������κ�Ŀ¼���,ǩ��������,
    // ����"���ǰ�װ"�����ᴥ�� INSTALL_FAILED_UPDATE_INCOMPATIBLE(�Ǹ���һ������,
    // Ψһ��·��ж����װ,�ֻ��ϵĿγ̺������һ��û)��
    signingConfigs {
        create("cq") {
            storeFile = file("../keystore/cq-release.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("cq")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // xls/xlsx 解析:复用原型已验证的 SheetJS 管线(assets �?WebView 执行),弃用 POI(安卓兼容性差)
    // 照片 OCR:bundled 中文模型(打进 APK,离线可用,无需 GMS)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
