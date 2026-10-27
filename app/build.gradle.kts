import java.util.Properties
import java.net.NetworkInterface
import java.net.Inet4Address
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}


val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun detectDevHostIp(localProps: Properties): String {
    val explicitHost = localProps.getProperty("HOST_IP")
        ?: System.getenv("HOST_IP")
    if (!explicitHost.isNullOrBlank()) {
        return explicitHost.trim()
    }

    val virtualKeywords = listOf("vethernet", "virtual", "virtualbox", "vmware", "loopback", "wsl", "hyper-v", "bluetooth", "npcap", "default switch")
    val virtualSubnets = mutableListOf("192.168.137.", "192.168.56.", "169.254.")
    for (i in 17..31) {
        virtualSubnets.add("172.$i.")
    }

    val physicalCandidates = mutableListOf<String>()
    val fallbackCandidates = mutableListOf<String>()

    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "10.0.2.2"
        for (netIf in interfaces.asSequence()) {
            val ifName = netIf.name.lowercase()
            val displayName = (netIf.displayName ?: "").lowercase()
            val isVirtualName = virtualKeywords.any { ifName.contains(it) || displayName.contains(it) }

            if (netIf.isLoopback || !netIf.isUp) continue
            for (addr in netIf.inetAddresses.asSequence()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.hostAddress.startsWith("127.")) {
                    val ip = addr.hostAddress
                    val isVirtualSubnet = virtualSubnets.any { ip.startsWith(it) }
                    if (!isVirtualName && !isVirtualSubnet) {
                        if (ip.startsWith("192.168.105.") || ip.startsWith("192.168.1.") || ip.startsWith("192.168.0.") || ip.startsWith("10.0.0.")) {
                            return ip
                        }
                        physicalCandidates.add(ip)
                    } else {
                        fallbackCandidates.add(ip)
                    }
                }
            }
        }
    } catch (_: Exception) {}

    return physicalCandidates.firstOrNull() ?: fallbackCandidates.firstOrNull() ?: "10.0.2.2"
}

val devHostIp = detectDevHostIp(localProperties)

val googleClientId = localProperties.getProperty("GOOGLE_CLIENT_ID")
    ?: System.getenv("GOOGLE_CLIENT_ID")
    ?: ""

val maxParallelUploads = (localProperties.getProperty("MAX_PARALLEL_UPLOADS")
    ?: System.getenv("MAX_PARALLEL_UPLOADS")
    ?: "2").toIntOrNull() ?: 2

val chunkSizeBytes = (localProperties.getProperty("CHUNK_SIZE_BYTES")
    ?: System.getenv("CHUNK_SIZE_BYTES")
    ?: "8388608").toLongOrNull() ?: 8388608L

val versionFile = rootProject.file("version.json")
val versionFileText = if (versionFile.exists()) versionFile.readText() else ""

val versionNameRegex = "\"versionName\"\\s*:\\s*\"([^\"]+)\"".toRegex()
val versionCodeRegex = "\"versionCode\"\\s*:\\s*(\\d+)".toRegex()

val parsedVersionName = versionNameRegex.find(versionFileText)?.groupValues?.get(1) ?: "1.0.0"
val parsedVersionCode = versionCodeRegex.find(versionFileText)?.groupValues?.get(1)?.toIntOrNull() ?: 100

val gitCommitHash = try {
    val stdout = ByteArrayOutputStream()
    project.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        standardOutput = stdout
        isIgnoreExitValue = true
    }
    val hash = stdout.toString().trim()
    if (hash.isNotEmpty() && !hash.startsWith("fatal")) {
        hash
    } else {
        System.getenv("GIT_COMMIT_HASH") ?: "a1b2c3d"
    }
} catch (e: Exception) {
    System.getenv("GIT_COMMIT_HASH") ?: "a1b2c3d"
}

val buildTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())

val environment = System.getenv("ENVIRONMENT")
    ?: localProperties.getProperty("ENVIRONMENT")
    ?: "development"

android {
    namespace = "com.architect.driveaggregator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.architect.driveaggregator"
        minSdk = 26
        targetSdk = 34
        versionCode = parsedVersionCode
        versionName = parsedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("int", "MAX_PARALLEL_UPLOADS", "$maxParallelUploads")
        buildConfigField("long", "CHUNK_SIZE_BYTES", "${chunkSizeBytes}L")
        buildConfigField("String", "DEV_HOST_IP", "\"$devHostIp\"")

        buildConfigField("String", "APP_VERSION", "\"$parsedVersionName\"")
        buildConfigField("String", "SEMVER_VERSION", "\"$parsedVersionName\"")
        buildConfigField("String", "GIT_COMMIT_HASH", "\"$gitCommitHash\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("String", "ENVIRONMENT", "\"$environment\"")
        buildConfigField("String", "FULL_VERSION_STRING", "\"v$parsedVersionName+build.$gitCommitHash\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseEnv = System.getenv("ENVIRONMENT")
                ?: localProperties.getProperty("ENVIRONMENT")
                ?: "production"
            buildConfigField("String", "ENVIRONMENT", "\"$releaseEnv\"")
        }
        debug {
            val debugEnv = System.getenv("ENVIRONMENT")
                ?: localProperties.getProperty("ENVIRONMENT")
                ?: "development"
            buildConfigField("String", "ENVIRONMENT", "\"$debugEnv\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Google Client APIs include duplicate META-INF files (e.g. dependency-licenses, index.list)
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/INDEX.LIST"
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName = "mdrive.apk"
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Google API Client & Drive REST API
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Android Security-Crypto
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room DB
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Backblaze Reed-Solomon Erasure Coding
    implementation("com.github.Backblaze:JavaReedSolomon:master-SNAPSHOT")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.10.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")
    implementation("com.jakewharton:disklrucache:2.0.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.6.0")
}

tasks.register("copyDebugApk") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val debugApkDir = file("$buildDir/outputs/apk/debug")
        val mdriveApk = file("$debugApkDir/mdrive.apk")
        val fallbackApk = file("$debugApkDir/app-debug.apk")
        val sourceApk = when {
            mdriveApk.exists() -> mdriveApk
            fallbackApk.exists() -> fallbackApk
            else -> null
        }
        val targetDir = file("${projectDir}/build/outputs/apk/debug")
        sourceApk?.let { apk ->
            targetDir.mkdirs()
            apk.copyTo(file("$targetDir/mdrive.apk"), overwrite = true)
            apk.copyTo(file("$targetDir/app-debug.apk"), overwrite = true)
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("copyDebugApk")
}

tasks.register("copyReleaseApk") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val releaseApkDir = file("$buildDir/outputs/apk/release")
        val mdriveApk = file("$releaseApkDir/mdrive.apk")
        val fallbackApk = file("$releaseApkDir/app-release-unsigned.apk")
        val fallbackApk2 = file("$releaseApkDir/app-release.apk")
        val sourceApk = when {
            mdriveApk.exists() -> mdriveApk
            fallbackApk.exists() -> fallbackApk
            fallbackApk2.exists() -> fallbackApk2
            else -> null
        }
        val targetDir = file("${projectDir}/build/outputs/apk/release")
        sourceApk?.let { apk ->
            targetDir.mkdirs()
            apk.copyTo(file("$targetDir/mdrive.apk"), overwrite = true)
            apk.copyTo(file("$targetDir/app-release.apk"), overwrite = true)
            apk.copyTo(file("$targetDir/app-release-unsigned.apk"), overwrite = true)
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyReleaseApk")
}

tasks.register("generateVersionJson") {
    doLast {
        val assetsFile = file("src/main/assets/version.json")
        val buildOutputsFile = file("build/outputs/version.json")
        val dynamicBuildOutputsFile = layout.buildDirectory.file("outputs/version.json").get().asFile

        val env = System.getenv("ENVIRONMENT")
            ?: localProperties.getProperty("ENVIRONMENT")
            ?: "development"

        val jsonContent = """
        {
          "version": "$parsedVersionName",
          "fullVersion": "v$parsedVersionName+build.$gitCommitHash",
          "commitHash": "$gitCommitHash",
          "buildTimestamp": "$buildTimestamp",
          "environment": "$env",
          "versionCode": $parsedVersionCode
        }
        """.trimIndent()

        assetsFile.parentFile.mkdirs()
        assetsFile.writeText(jsonContent)

        buildOutputsFile.parentFile.mkdirs()
        buildOutputsFile.writeText(jsonContent)

        dynamicBuildOutputsFile.parentFile.mkdirs()
        dynamicBuildOutputsFile.writeText(jsonContent)
    }
}

tasks.named("preBuild") {
    dependsOn("generateVersionJson")
}
