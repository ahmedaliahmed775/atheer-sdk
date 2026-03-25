// ملف بناء وحدة مكتبة Atheer SDK
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("maven-publish") // إضافة النشر
    id("org.jetbrains.dokka")
}

android {
    namespace = "com.atheer.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // ✅ يجب إضافة هذا البلوك لتجهيز نسخة الـ Release للنشر
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // SQLCipher
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // ✅ الأمن: Root Detection & Play Integrity
    implementation("com.scottyab:rootbeer-lib:0.1.0")
    implementation("com.google.android.play:integrity:1.3.0")

    // Test
    testImplementation("junit:junit:4.13.2")
}

// ==========================================
// ✅ إعدادات النشر (Publishing) إلى GitHub Packages
// ==========================================
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                // جلب المكون الذي تم تجهيزه داخل بلوك android
                from(components["release"])

                groupId = "com.github.ahmedaliahmed775"
                artifactId = "atheer-sdk"
                // جلب الإصدار من GitHub Actions أو استخدام قيمة افتراضية
                version = System.getenv("SDK_VERSION") ?: "1.0.0"
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/ahmedaliahmed775/atheer-sdk")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}