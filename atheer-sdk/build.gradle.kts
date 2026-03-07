// ملف بناء وحدة مكتبة Atheer SDK
// يستخدم إضافة المكتبة بدلاً من إضافة التطبيق لإنتاج ملف AAR
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("maven-publish")
}

android {
    namespace = "com.atheer.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // لا يوجد targetSdk أو applicationId في مكتبة Android
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // تفعيل تصغير الكود في نسخة الإصدار لتقليل حجم الـ AAR
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // مكتبات AndroidX الأساسية
    implementation("androidx.core:core-ktx:1.13.1")

    // مكتبة Coroutines لدعم البرمجة غير المتزامنة
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // مكتبة Room لقاعدة البيانات المحلية مع تشفير على مستوى الحقول
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // مكتبات الاختبار
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
// ==========================================
// كود النشراً
// ==========================================
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.github.ahmedaliahmed775" 
                artifactId = "atheer-sdk"
                version = "1.0.0"

                afterEvaluate {
                    from(components["release"])
                }
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
