import org.gradle.api.tasks.testing.logging.TestExceptionFormat

val junitJupiterVersion = "5.11.3"
val logbackClassicVersion = "1.5.12"
val logbackEncoderVersion = "8.0"
val awaitilityVersion = "4.2.2"
val kafkaTestcontainerVersion = "1.20.3"
val tbdLibsVersion = "2024.11.12-09.00-eae8e3a5"

group = "com.github.navikt"
version = properties["version"] ?: "local-build"

plugins {
    kotlin("jvm") version "2.0.21"
    id("java")
    id("maven-publish")
}

dependencies {
    api("com.github.navikt.tbd-libs:naisful-app:$tbdLibsVersion")
    api("com.github.navikt.tbd-libs:rapids-and-rivers:$tbdLibsVersion")

    api("ch.qos.logback:logback-classic:$logbackClassicVersion")
    api("net.logstash.logback:logstash-logback-encoder:$logbackEncoderVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("com.github.navikt.tbd-libs:rapids-and-rivers-test:$tbdLibsVersion")

    testImplementation("org.testcontainers:kafka:$kafkaTestcontainerVersion")
    testImplementation("org.awaitility:awaitility:$awaitilityVersion")
}

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of("21"))
    }
}

tasks {
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
}

repositories {
    val githubPassword: String? by project
    mavenCentral()
    /* ihht. https://github.com/navikt/utvikling/blob/main/docs/teknisk/Konsumere%20biblioteker%20fra%20Github%20Package%20Registry.md
        så plasseres github-maven-repo (med autentisering) før nav-mirror slik at github actions kan anvende førstnevnte.
        Det er fordi nav-mirroret kjører i Google Cloud og da ville man ellers fått unødvendige utgifter til datatrafikk mellom Google Cloud og GitHub
     */
    maven {
        url = uri("https://maven.pkg.github.com/navikt/maven-release")
        credentials {
            username = "x-access-token"
            password = githubPassword
        }
    }
    maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
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
