val awaitilityVersion = "4.3.0"
val otelVersion = "2.26.1"
val kotlinxCoroutinesVersion = "1.10.2"

dependencies {
    api(project(":kafka"))
    api(project(":rapids-and-rivers-api"))

    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations:$otelVersion")

    testImplementation(project(":rapids-and-rivers-test"))
    testImplementation(project(":kafka-test"))
    testImplementation("org.awaitility:awaitility:$awaitilityVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$kotlinxCoroutinesVersion")

    testImplementation("ch.qos.logback:logback-classic:1.5.32")
    testImplementation("net.logstash.logback:logstash-logback-encoder:9.0")
}
