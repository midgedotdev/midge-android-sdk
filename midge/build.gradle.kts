// MidgeSDK for Android: a "Report a bug" for your own Android app (docs/superpowers/specs/2026-11-14-midge-android-sdk-design.md).
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.midge.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION", "\"0.2.0\"")
    }

    buildFeatures { buildConfig = true }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.javascriptengine)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
}
// Appended to midge/build.gradle.kts in the published repo: the library as dev.midge:sdk on GitHub Packages.
apply(plugin = "maven-publish")

android {
    publishing { singleVariant("release") { withSourcesJar() } }
}

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "dev.midge"
                artifactId = "sdk"
                version = System.getenv("MIDGE_SDK_VERSION") ?: "0.0.0-local"
                pom {
                    name.set("MidgeSDK for Android")
                    description.set("Report a bug from inside your Android app: the screen, the app's requests and logs, redacted on the phone.")
                    url.set("https://midge.dev/docs/share/android-sdk")
                    licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/midgedotdev/midge-android-sdk")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
