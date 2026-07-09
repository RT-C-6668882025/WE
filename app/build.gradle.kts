plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// —— 开源版：不再从任何 env 文件注入密钥 ——
// 墨灵的大模型密钥/节点/模型名全部改为运行时由用户在 App 设置面板填写，
// 存 SharedPreferences。构建产物（APK）内不含任何密钥，可放心开源。

android {
    namespace = "com.opc.baize"   // 源码包目录沿用 com.opc.baize（改名只动 applicationId，降编译风险）
    compileSdk = 34

    defaultConfig {
        applicationId = "com.opc.moling"   // 墨灵：对外应用标识
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // 可选的局域网降级识别通道默认地址：开源版留空，用户如自建 Mac 端 server.py
        // 可在设置里填（不填则纯走 ML Kit 本地识别 + 用户配置的大模型）。
        buildConfigField("String", "DEFAULT_FALLBACK_ASK_URL", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // 保持默认严格度；构建自测会跑 lintDebug 确认零 fatal
        abortOnError = true
    }

    testOptions {
        // AHW 容错单测里会打 Log.i；默认 android.jar 存根会抛"not mocked"，
        // 让 stub 返回默认值即可（不影响纯逻辑测试的断言）。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 识别主通道：ML Kit Digital Ink（直接吃笔画轨迹，不走图片大模型）
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")

    // 一级低延迟：AndroidX 运动预测（Kalman，minSdk 23 兼容我们 minSdk 26，
    // 只依赖 annotation+core，无华为私有依赖）。API：MotionEventPredictor.newInstance(view)
    // → record(MotionEvent) → predict()。1.0.0 为稳定正式版（2025-11 发布）。
    implementation("androidx.input:input-motionprediction:1.0.0")

    testImplementation("junit:junit:4.13.2")
    // JVM 单测里要用真的 org.json（android.jar 里的是 Stub!）
    testImplementation("org.json:json:20240303")
}
