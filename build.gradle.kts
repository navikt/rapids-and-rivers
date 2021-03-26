import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val ktorVersion = "1.5.0"
val kafkaVersion = "2.4.0"
val micrometerRegistryPrometheusVersion = "1.6.2"
val junitJupiterVersion = "5.7.0"
val jacksonVersion = "2.12.0"

group = "com.github.navikt"
version = properties["version"] ?: "local-build"

plugins {
    kotlin("jvm") version "1.4.21"
    id("java")
    id("maven-publish")
}

dependencies {
    api("ch.qos.logback:logback-classic:1.2.3")
    api("net.logstash.logback:logstash-logback-encoder:6.6") {
        exclude("com.fasterxml.jackson.core")
    }
    api("io.ktor:ktor-server-netty:$ktorVersion")

    api("org.apache.kafka:kafka-clients:$kafkaVersion")

    api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    api("io.ktor:ktor-metrics-micrometer:$ktorVersion")
    api("io.micrometer:micrometer-registry-prometheus:$micrometerRegistryPrometheusVersion")

    constraints {
        api("io.netty:netty-codec-http2:4.1.59.Final") {
            because("forrige versjon er rapportert av snyk")
        }
        api("io.netty:netty-transport-native-epoll:4.1.59.Final") {
            because("forrige versjon er rapportert av snyk")
        }
        api("io.netty:netty-transport-native-kqueue:4.1.59.Final") {
            because("forrige versjon er rapportert av snyk")
        }
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")

    testImplementation("no.nav:kafka-embedded-env:$kafkaVersion")
    testImplementation("org.awaitility:awaitility:4.0.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    withSourcesJar()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("skipped", "failed")
        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

tasks.withType<Wrapper> {
    gradleVersion = "6.7.1"
}

repositories {
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/ktor")
    maven("https://packages.confluent.io/maven/")
    maven("https://jitpack.io")
}

val githubUser: String? by project
val githubPassword: String? by project

publishing {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/navikt/rapids-and-rivers")
            credentials {
                username = githubUser
                password = githubPassword
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {

            pom {
                name.set("rapids-rivers")
                description.set("Rapids and Rivers")
                url.set("https://github.com/navikt/rapids-and-rivers")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/navikt/rapids-and-rivers.git")
                    developerConnection.set("scm:git:https://github.com/navikt/rapids-and-rivers.git")
                    url.set("https://github.com/navikt/rapids-and-rivers")
                }
            }
            from(components["java"])
        }
    }
}
