// Pure Kotlin logic of Post: counters, Markdown, threads, schedule, media plan and the network clients.
// It runs on the JVM, so its tests need neither Android nor Robolectric.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The app compiles to Java 17 bytecode; the core it bundles must not be newer.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test>().configureEach {
    // A failure on CI shows its whole stack, with the line of the test.
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commonmark)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // The device segments text with android.icu; the tests use the same ICU rules through ICU4J.
    testImplementation(libs.icu4j)
}
