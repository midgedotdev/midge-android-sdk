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
        buildConfigField("String", "VERSION", "\"0.2.2\"")
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
// Appended to midge/build.gradle.kts in the published repo: the library as dev.midge:sdk on Maven Central (staged
// into build/central-staging, signed, then uploaded by the workflow) and on GitHub Packages for earlier users.
apply(plugin = "maven-publish")
apply(plugin = "signing")

android {
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}

afterEvaluate {
    val publishing = extensions.getByType<PublishingExtension>()
    publishing.apply {
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
                    developers { developer { id.set("midge"); name.set("Midge"); email.set("info@midge.dev"); url.set("https://midge.dev") } }
                    scm {
                        url.set("https://github.com/midgedotdev/midge-android-sdk")
                        connection.set("scm:git:https://github.com/midgedotdev/midge-android-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/midgedotdev/midge-android-sdk.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "CentralStaging"
                url = uri(layout.buildDirectory.dir("central-staging"))
            }
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
    // Maven Central needs every file signed. The key comes from the workflow's secrets; without it (local builds) the
    // publication stays unsigned and only the GitHub Packages and local staging tasks make sense.
    val key = System.getenv("MIDGE_SIGNING_KEY")
    if (!key.isNullOrBlank()) {
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(key, System.getenv("MIDGE_SIGNING_PASSWORD"))
            sign(publishing.publications["release"])
        }
    }
}
