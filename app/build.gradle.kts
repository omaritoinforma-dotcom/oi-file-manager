plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber =
    System.getenv("OI_BUILD_NUMBER")?.toIntOrNull()
        ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        ?: 1
require(buildNumber > 0) { "El número de compilación debe ser positivo" }

android {
    namespace = "com.omaritoinforma.oiarchivos"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.omaritoinforma.oiarchivos"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
        manifestPlaceholders["appAuthRedirectScheme"] = "com.omaritoinforma.oiarchivos.oauth"
    }

    // Clave de las versiones publicadas: solo existe en los secretos del repositorio (el CI la deja en
    // un archivo temporal y pasa su ruta y contraseña por el entorno). Sin ella, las compilaciones
    // locales y las de forks se firman con la clave de depuración, que es pública y no sirve para
    // publicar: con OI_REQUIRE_RELEASE_KEY=1 (las versiones de main) la compilación falla.
    val releaseKeystore = System.getenv("OI_RELEASE_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    if (releaseKeystore == null && System.getenv("OI_REQUIRE_RELEASE_KEY") == "1")
        throw GradleException("Falta la clave de firma de las versiones (secreto OI_RELEASE_KEYSTORE_B64)")

    signingConfigs {
        // Clave de depuración del repo: solo para pruebas.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null)
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("OI_RELEASE_PASSWORD")
                keyAlias = System.getenv("OI_RELEASE_KEY_ALIAS") ?: "oiarchivos"
                keyPassword = System.getenv("OI_RELEASE_PASSWORD")
            }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
    }

    testOptions {
        unitTests.all { test ->
            // Para probar FTPS contra un servidor de prueba con certificado propio: un almacén de
            // confianza con los certificados del sistema y el del servidor (scripts/remote_servers.py).
            System.getenv("OI_REMOTE_TEST_TRUSTSTORE")?.let {
                test.systemProperty("javax.net.ssl.trustStore", it)
                test.systemProperty("javax.net.ssl.trustStorePassword", "changeit")
                test.systemProperty("javax.net.ssl.trustStoreType", "JKS")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Miniaturas de imágenes y videos
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("net.openid:appauth:0.11.1")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    // NFSv3 (Dell EMC, Apache 2.0). Trae Netty 3, commons-lang3 y slf4j.
    implementation("com.emc.ecs:nfs-client:1.1.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; android.jar only has stubs.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
