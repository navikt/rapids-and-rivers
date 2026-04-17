group = "com.github.navikt"
version = properties["version"] ?: "local-build"

plugins {
    alias(libs.plugins.kotlin.jvm)

    `maven-publish`
}

dependencies {
    api(libs.slf4j.api)

    api(project("rapids-and-rivers-impl"))

    api(libs.ktor.server.cio)
    api(libs.ktor.server.metrics.micrometer)
    api(libs.micrometer.registry.prometheus)

    api(libs.logback.classic)
    api(libs.logstash.logback.encoder)

    testImplementation(project("rapids-and-rivers-test"))
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
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
        /*  ihht. https://github.com/navikt/utvikling/blob/main/docs/teknisk/Konsumere%20biblioteker%20fra%20Github%20Package%20Registry.md
            så plasseres GitHub-Maven-repo (med autentisering) før Nav-mirror slik at GitHub actions kan anvende førstnevnte.
            Det er fordi Nav-mirror kjører i Google Cloud og da ville man ellers fått unødvendige utgifter til datatrafikk mellom Google Cloud og GitHub.
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

    val jacksonVersion = gradle.extra["jacksonVersion"] as String
    val jacksonAnnotationsVersion = gradle.extra["jacksonAnnotationsVersion"] as String
    val junitJupiterVersion = gradle.extra["junitJupiterVersion"] as String
    val testcontainersVersion = gradle.extra["testcontainersVersion"] as String

    ext.set("testcontainersVersion", testcontainersVersion)
    val api by configurations
    val testImplementation by configurations
    val testRuntimeOnly by configurations
    dependencies {
        constraints {
            api("tools.jackson:jackson-bom:$jacksonVersion") {
                because("Alle moduler skal bruke samme versjon av jackson")
            }
            api("com.fasterxml.jackson.core:jackson-annotations:$jacksonAnnotationsVersion") {
                because("Alle moduler skal bruke samme versjon av jackson")
            }
            api("tools.jackson.module:jackson-module-kotlin:$jacksonVersion") {
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
                attributes(
                    mapOf(
                        "Implementation-Title" to project.name,
                        "Implementation-Version" to project.version
                    )
                )
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
