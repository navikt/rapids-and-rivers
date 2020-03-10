import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val ktorVersion = "1.2.4"
val kafkaVersion = "2.3.1"
val micrometerRegistryPrometheusVersion = "1.1.5"
val junitJupiterVersion = "5.4.0"
val jacksonVersion = "2.10.0"

group = "no.nav.helse"
version = properties["version"] ?: "local-build"

plugins {
    kotlin("jvm") version "1.3.60"
    id("java")
    id("maven-publish")
    id("org.sonarqube") version "2.7"
}

//apply(plugin = "org.jetbrains.kotlin.jvm")

dependencies {
    api(kotlin("stdlib-jdk8"))

    api("ch.qos.logback:logback-classic:1.2.3")
    api("net.logstash.logback:logstash-logback-encoder:5.2")
    api("io.ktor:ktor-server-netty:$ktorVersion")

    api("org.apache.kafka:kafka-clients:$kafkaVersion")

    api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    api("io.ktor:ktor-metrics-micrometer:$ktorVersion")
    api("io.micrometer:micrometer-registry-prometheus:$micrometerRegistryPrometheusVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")

    testImplementation("no.nav:kafka-embedded-env:2.3.0")
    testImplementation("org.awaitility:awaitility:4.0.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_12
    targetCompatibility = JavaVersion.VERSION_12
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "12"
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    kotlinOptions.jvmTarget = "12"
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

tasks.withType<Wrapper> {
    gradleVersion = "5.6.4"
}

repositories {
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/ktor")
    maven("http://packages.confluent.io/maven/")
}

val sourcesJar by tasks.registering(Jar::class) {
    classifier = "sources"
    from(sourceSets.main.get().allSource)
}

version = properties["rapidVersion"] ?: "local-build"
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
            artifact(sourcesJar.get())
        }
    }
}
