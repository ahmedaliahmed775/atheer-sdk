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
            // ✅ تم التعديل أمنياً: تفعيل تصغير وتشويش الكود لمنع الهندسة العكسية
            isMinifyEnabled = true 
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // مكتبات AndroidX الأساسية
    implementation("androidx.core:core-ktx:1.13.1")

    // مكتبة Coroutines لدعم البرمجة غير المتزامنة
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ✅ التعديل الأمني: إضافة مكتبة Security Crypto لتشفير SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ✅ التعديل المعماري: إضافة WorkManager للمزامنة في الخلفية (Background Sync)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // مكتبة Room لقاعدة البيانات المحلية
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // ✅ التعديل الأمني (PCI-DSS): إضافة SQLCipher لتشفير قاعدة البيانات بالكامل
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // مكتبة Gson لمعالجة JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // مكتبات الاختبار
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// ==========================================
// كود النشر (Publishing)
// ==========================================
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                // جلب المكون الذي تم تجهيزه داخل بلوك android
                from(components["release"])
                
                groupId = "com.github.ahmedaliahmed775" 
                artifactId = "atheer-sdk"
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
