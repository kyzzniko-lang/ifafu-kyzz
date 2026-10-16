plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.ifafu.kyzz"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ifafu.kyzz"
        minSdk = 24
        targetSdk = 34
        versionCode = 27
        versionName = "2.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // LLM 密钥已迁至杭州服务器代理(/ifafu/api/pet-chat)，不再随 APK 分发；
        // local.properties 中的 zhipu/qwen enc 条目已废弃。

        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        getByName("debug") {
            // 使用默认 debug keystore
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // 解析器单测依赖 android.util.Log（返回默认值而非抛异常）
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 < 26：WeatherApi/CacheManager/HomeFragment 用了 java.time，
        // 不开启 desugaring 时 Android 7.x 设备抛 NoClassDefFoundError（Error
        // 不是 Exception，catch 不住），首页天气直接崩溃。
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        disable += "MockLocation"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    // 2.10+ requires compileSdk 35 / newer AGP; 2.9.1 matches this project's SDK 34 toolchain.
    implementation("androidx.work:work-runtime-ktx:2.9.1")



    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("org.jsoup:jsoup:1.17.2")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    implementation("com.airbnb.android:lottie:6.3.0")

    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    testImplementation("junit:junit:4.13.2")
    // 本地单测跑真 Android 框架子集（SharedPreferences 等），无需设备
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
