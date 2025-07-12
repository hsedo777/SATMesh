plugins {
    alias(libs.plugins.android.application)
    id("androidx.room")
    id("com.google.protobuf") //Protocol Buffers
}

android {
    namespace = "org.sedo.satmesh"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.sedo.satmesh"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures{
        viewBinding = true
    }
    room {
        schemaDirectory("$projectDir/schemas")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.play.services.nearby)

    // Room
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.room.compiler)
    testImplementation(libs.androidx.room.testing)
    //SQLCipher for database file.s encryption/decryption
    implementation(libs.sqlcipher.android)
    //Protocol Buffers
    implementation(platform(libs.protobuf.javalite))
    implementation(libs.protobuf.javalite)
    // Signal Protocol
    implementation(libs.libsignal.protocol.java)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}