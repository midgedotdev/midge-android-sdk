# MidgeSDK for Android

A "Report a bug" for your own Android app. Someone shakes the phone (or taps your button), records the screen and says
what went wrong; the report reaches your [Midge](https://midge.dev) workspace with the app's OkHttp requests, its
logcat lines and the metadata you set, redacted on the phone before anything is sent.

Early access: the package is private. Docs: <https://midge.dev/docs/share/android-sdk>.

## Install

Android 8.0 (API 26) or later. Add GitHub Packages with a token that can read this repository's packages
(`read:packages`), for example in `~/.gradle/gradle.properties` as `gpr.user` and `gpr.key`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.github.com/midgedotdev/midge-android-sdk") {
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("dev.midge:sdk:0.1.0")
}
```

## Use

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Midge.start(this, link = "YOUR_LINK_TOKEN")   // Settings › Workspace › Recording links on midge.dev
        Midge.user(name = user.name, email = user.email)
        Midge.metadata(mapOf("plan" to "pro"))
    }
}

val http = OkHttpClient.Builder().addInterceptor(Midge.okHttpInterceptor()).build()
reportButton.setOnClickListener { Midge.report() }   // shaking the phone opens it too

// HttpURLConnection / Volley's HurlStack: Midge.openConnection(url); any other client: Midge.network(method, url, status, durationMs)
```

Register the app's application id under Settings › Workspace › Recording links › App SDK; only registered apps can
send. On Google Play, declare the `mediaProjection` foreground service the library uses to record the screen.

## License

MIT
