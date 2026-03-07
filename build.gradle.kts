// ملف إعداد المشروع الجذري - يحدد الإضافات المشتركة لجميع الوحدات
plugins {
    id("com.android.library") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
    id("maven-publish")
}
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                // تأكد من وضع اسم حسابك الصحيح في جيت هاب
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
