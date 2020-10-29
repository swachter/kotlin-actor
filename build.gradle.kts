import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinVersion = "1.4.10"
val junitVersion = "5.3.1"

val developmentOnly: Configuration by configurations.creating

plugins {
    kotlin("jvm") version "1.4.10"
    kotlin("kapt") version "1.4.10"
}

version = "1.0.0-SNAPSHOT"
group = "eu.swdev"

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://jcenter.bintray.com")
    maven("https://plugins.gradle.org/m2/")
    jcenter()
}

configurations {
    // for dependencies that are needed for development only
    developmentOnly
}

dependencies {
    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.0")

    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.13.3")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.hamcrest:hamcrest-library:2.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")

}

tasks {
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            // Will retain parameter names for Java reflection
            javaParameters = true
        }
    }

    withType<Test> {
        classpath = classpath.plus(configurations["developmentOnly"])
        // use JUnit 5 platform
        useJUnitPlatform()
    }

}
