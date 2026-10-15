plugins {
    id("com.android.library")
}

android {
    namespace = "app.reventor.dictation"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    // sherpa-onnx runtime for the Parakeet engine. JitPack builds the official AAR;
    // arm64 JNI libs add ~27 MB installed (other ABIs are stripped by the app module).
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8")
}
