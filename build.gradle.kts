import org.gradle.api.tasks.testing.logging.TestExceptionFormat

val jvmTarget = 21

val ktorVersion = "2.3.11"
val kafkaVersion = "3.7.0"
val micrometerRegistryPrometheusVersion = "1.12.5"
val junitJupiterVersion = "5.10.2"
val jacksonVersion = "2.17.1"
val logbackClassicVersion = "1.5.6"
val logbackEncoderVersion = "7.4"
val awaitilityVersion = "4.2.1"
val kafkaTestcontainerVersion = "1.19.7"

group = "com.github.navikt"
version = properties["version"] ?: "local-build"

plugins {
    kotlin("jvm") version "1.9.24"
    id("java")
    id("maven-publish")
}

dependencies {
    api("ch.qos.logback:logback-classic:$logbackClassicVersion")
    api("net.logstash.logback:logstash-logback-encoder:$logbackEncoderVersion")

    api("io.ktor:ktor-server-cio:$ktorVersion")

    api("org.apache.kafka:kafka-clients:$kafkaVersion")

    api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    api("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")
    api("io.micrometer:micrometer-registry-prometheus:$micrometerRegistryPrometheusVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.testcontainers:kafka:$kafkaTestcontainerVersion")
    testImplementation("org.awaitility:awaitility:$awaitilityVersion")
}

java {
    withSourcesJar()
}

tasks {
    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(jvmTarget)
        }
    }

    withType<Test> {
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

    withType<Wrapper> {
        gradleVersion = "8.6"
    }
}

repositories {
    mavenCentral()
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
