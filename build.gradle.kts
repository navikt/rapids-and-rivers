val slf4jVersion = "2.0.17"
val ktorVersion = "3.4.0"
val micrometerRegistryPrometheusVersion = "1.16.2"
val junitJupiterVersion = "6.0.2"
val logbackClassicVersion = "1.5.25"
val logbackEncoderVersion = "9.0"
val otelLogbackVersion = "2.9.0-alpha"
val awaitilityVersion = "4.3.0"
val testcontainersVersion = "2.0.3"
val jacksonVersion = "2.18.3"

group = "com.github.navikt"
version = properties["version"] ?: "local-build"

plugins {
    kotlin("jvm") version "2.3.0"
    id("java")
    id("maven-publish")
}

dependencies {
    api("org.slf4j:slf4j-api:$slf4jVersion")

    api(project("rapids-and-rivers-impl"))

    api("io.ktor:ktor-server-cio:$ktorVersion")

    api("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")
    api("io.micrometer:micrometer-registry-prometheus:$micrometerRegistryPrometheusVersion")

    api("ch.qos.logback:logback-classic:$logbackClassicVersion")
    api("net.logstash.logback:logstash-logback-encoder:$logbackEncoderVersion")
    api("io.opentelemetry.instrumentation:opentelemetry-logback-mdc-1.0:$otelLogbackVersion")

    //testImplementation("org.junit.jupiter:junit-jupiter")
    //testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation(project("rapids-and-rivers-test"))

    testImplementation("org.testcontainers:testcontainers-kafka:$testcontainersVersion")
    testImplementation("org.awaitility:awaitility:$awaitilityVersion")
}

java {
    withSourcesJar()
}

tasks {
    jar {
        manifest {
            attributes(
                mapOf(
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to project.version
                )
            )
        }
    }
}

subprojects {
    group = "com.github.navikt.rapids-and-rivers"
}

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.gradle.maven-publish")

    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of("21"))
        }
    }

    repositories {
        mavenCentral()
        /* ihht. https://github.com/navikt/utvikling/blob/main/docs/teknisk/Konsumere%20biblioteker%20fra%20Github%20Package%20Registry.md
            så plasseres github-maven-repo (med autentisering) før nav-mirror slik at github actions kan anvende førstnevnte.
            Det er fordi nav-mirroret kjører i Google Cloud og da ville man ellers fått unødvendige utgifter til datatrafikk mellom Google Cloud og GitHub
         */
        maven {
            url = uri("https://maven.pkg.github.com/navikt/maven-release")
            credentials {
                username = System.getenv("GITHUB_USERNAME")
                password = System.getenv("GITHUB_PASSWORD")
            }
        }
        maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
    }

    ext.set("testcontainersVersion", testcontainersVersion)
    val api by configurations
    val testImplementation by configurations
    val testRuntimeOnly by configurations
    dependencies {
        constraints {
            api("com.fasterxml.jackson:jackson-bom:$jacksonVersion") {
                because("Alle moduler skal bruke samme versjon av jackson")
            }
            api("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion") {
                because("Alle moduler skal bruke samme versjon av jackson")
            }
            api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion") {
                because("Alle moduler skal bruke samme versjon av jackson")
            }
        }

        testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    configure<JavaPluginExtension> {
        withSourcesJar()
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = project.name
                version = "${this@allprojects.version}"
            }
        }
        repositories {
            maven {
                url = uri("https://maven.pkg.github.com/navikt/rapids-and-rivers")
                credentials {
                    username = System.getenv("GITHUB_USERNAME")
                    password = System.getenv("GITHUB_PASSWORD")
                }
            }
        }
    }

    tasks {
        withType<Jar> {
            manifest {
                attributes(mapOf(
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to project.version
                ))
            }
        }

        withType<Test> {
            useJUnitPlatform()
            testLogging {
                events("skipped", "failed")
            }

            systemProperty("junit.jupiter.execution.parallel.enabled", "true")
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
            systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
            systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "8")
        }
    }
}
