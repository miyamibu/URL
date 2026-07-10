import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun configString(propertyName: String, envName: String, defaultValue: String = ""): String {
    return providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(envName).orNull
        ?: localProperties.getProperty(propertyName)
        ?: defaultValue
}

fun configBoolean(propertyName: String, envName: String, defaultValue: Boolean = false): Boolean {
    return configString(propertyName, envName, defaultValue.toString()).toBooleanStrictOrNull() ?: defaultValue
}

fun buildConfigString(value: String): String {
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

fun contactSupportEndpointFromSupabaseUrl(supabaseUrl: String): String {
    return supabaseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }
        ?.let { "$it/functions/v1/contact-support" }
        .orEmpty()
}

val publicInviteLinkBaseUrl = configString(
    propertyName = "invite.link.base.url",
    envName = "URLSAVER_INVITE_LINK_BASE_URL",
    defaultValue = "https://miyamibu.xyz",
).trim().trimEnd('/')
val mediaResolverBackendUrl = configString(
    propertyName = "media.resolver.backend.url",
    envName = "URLSAVER_MEDIA_RESOLVER_BACKEND_URL",
).trim().trimEnd('/')
val contactSupportEndpointUrl = configString(
    propertyName = "contact.support.endpoint.url",
    envName = "URLSAVER_CONTACT_SUPPORT_ENDPOINT_URL",
).trim()

val releaseSharedTagCloudEnabled = configBoolean(
    propertyName = "release.shared.tag.cloud.enabled",
    envName = "URLSAVER_RELEASE_SHARED_TAG_CLOUD_ENABLED",
)
val releaseSupabaseUrl = configString(
    propertyName = "release.supabase.url",
    envName = "URLSAVER_RELEASE_SUPABASE_URL",
).trim()
val releaseSupabaseAnonKey = configString(
    propertyName = "release.supabase.anon.key",
    envName = "URLSAVER_RELEASE_SUPABASE_ANON_KEY",
).trim()
val debugSupabaseUrl = configString("supabase.url", "URLSAVER_SUPABASE_URL").trim()
val debugSupabaseAnonKey = configString("supabase.anon.key", "URLSAVER_SUPABASE_ANON_KEY").trim()
val debugContactSupportEndpointUrl = contactSupportEndpointUrl.ifBlank {
    contactSupportEndpointFromSupabaseUrl(debugSupabaseUrl)
}
val releaseContactSupportEndpointUrl = contactSupportEndpointUrl.ifBlank {
    contactSupportEndpointFromSupabaseUrl(releaseSupabaseUrl)
}
val debugAiTransparencyEnabled = configBoolean(
    propertyName = "ai.transparency.enabled",
    envName = "URLSAVER_AI_TRANSPARENCY_ENABLED",
)
val releaseBuildRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true) || taskName == "build"
}
if (releaseBuildRequested &&
    releaseSharedTagCloudEnabled &&
    (releaseSupabaseUrl.isBlank() || releaseSupabaseAnonKey.isBlank() || releaseContactSupportEndpointUrl.isBlank())
) {
    throw GradleException(
        "Release builds with shared-tag cloud enabled require beta/production Supabase config. Set " +
            "release.shared.tag.cloud.enabled=true, release.supabase.url, release.supabase.anon.key, " +
            "and optionally contact.support.endpoint.url " +
            "or URLSAVER_RELEASE_SHARED_TAG_CLOUD_ENABLED=true, URLSAVER_RELEASE_SUPABASE_URL, " +
            "URLSAVER_RELEASE_SUPABASE_ANON_KEY, and optionally URLSAVER_CONTACT_SUPPORT_ENDPOINT_URL. " +
            "For a local-only pre-contract release build, leave " +
            "release.shared.tag.cloud.enabled unset or false. Use a publishable/anon key, never service_role/secret.",
    )
}

android {
    namespace = "jp.mimac.urlsaver"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.miyamibu.urlalbum"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.0.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        manifestPlaceholders["admobAppId"] =
            providers.gradleProperty("admobAppId").getOrElse("ca-app-pub-3940256099942544~3347511713")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ADS_TRIAL_LAUNCH_ADS_ENABLED", "false")
            buildConfigField("boolean", "ADS_ENABLED", configBoolean("ads.enabled", "URLSAVER_ADS_ENABLED").toString())
            buildConfigField("String", "ADMOB_APP_ID", buildConfigString("ca-app-pub-3940256099942544~3347511713"))
            buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", buildConfigString("ca-app-pub-3940256099942544/9214589741"))
            buildConfigField("String", "ADMOB_INTERSTITIAL_AD_UNIT_ID", buildConfigString("ca-app-pub-3940256099942544/1033173712"))
            buildConfigField("String", "INSTAGRAM_OEMBED_ACCESS_TOKEN", buildConfigString(configString("instagram.oembed.access.token", "URLSAVER_INSTAGRAM_OEMBED_ACCESS_TOKEN")))
            buildConfigField("boolean", "SHARED_TAG_CLOUD_ENABLED", configBoolean("shared.tag.cloud.enabled", "URLSAVER_SHARED_TAG_CLOUD_ENABLED").toString())
            buildConfigField("String", "SUPABASE_URL", buildConfigString(debugSupabaseUrl))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(debugSupabaseAnonKey))
            buildConfigField("String", "INVITE_LINK_BASE_URL", buildConfigString(publicInviteLinkBaseUrl))
            buildConfigField("String", "MEDIA_RESOLVER_BACKEND_URL", buildConfigString(mediaResolverBackendUrl))
            buildConfigField("String", "CONTACT_SUPPORT_ENDPOINT_URL", buildConfigString(debugContactSupportEndpointUrl))
            buildConfigField("boolean", "ALLOW_LOCAL_MEDIA_DOWNLOADS", "true")
            buildConfigField("boolean", "AI_TRANSPARENCY_ENABLED", debugAiTransparencyEnabled.toString())
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ADS_TRIAL_LAUNCH_ADS_ENABLED", "false")
            buildConfigField("boolean", "ADS_ENABLED", "false")
            buildConfigField("String", "ADMOB_APP_ID", buildConfigString(""))
            buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", buildConfigString(""))
            buildConfigField("String", "ADMOB_INTERSTITIAL_AD_UNIT_ID", buildConfigString(""))
            buildConfigField("String", "INSTAGRAM_OEMBED_ACCESS_TOKEN", buildConfigString(configString("instagram.oembed.access.token", "URLSAVER_INSTAGRAM_OEMBED_ACCESS_TOKEN")))
            buildConfigField("boolean", "SHARED_TAG_CLOUD_ENABLED", releaseSharedTagCloudEnabled.toString())
            buildConfigField("String", "SUPABASE_URL", buildConfigString(releaseSupabaseUrl))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(releaseSupabaseAnonKey))
            buildConfigField("String", "INVITE_LINK_BASE_URL", buildConfigString(publicInviteLinkBaseUrl))
            buildConfigField("String", "MEDIA_RESOLVER_BACKEND_URL", buildConfigString(mediaResolverBackendUrl))
            buildConfigField("String", "CONTACT_SUPPORT_ENDPOINT_URL", buildConfigString(releaseContactSupportEndpointUrl))
            buildConfigField("boolean", "ALLOW_LOCAL_MEDIA_DOWNLOADS", "false")
            buildConfigField("boolean", "AI_TRANSPARENCY_ENABLED", "false")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.android.billingclient:billing:8.3.0")
    compileOnly("com.google.android.gms:play-services-ads:23.6.0")
    debugImplementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.work:work-testing:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
