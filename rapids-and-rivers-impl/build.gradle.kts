val awaitilityVersion = "4.3.0"
val otelVersion = "2.9.0"
val kotlinxCoroutinesVersion = "1.9.0"

dependencies {
    api(project(":kafka"))
    api(project(":rapids-and-rivers-api"))

    api("tools.jackson.module:jackson-module-kotlin")

    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations:$otelVersion")

    testImplementation(project(":rapids-and-rivers-test"))
    testImplementation(project(":kafka-test"))
    testImplementation("org.awaitility:awaitility:$awaitilityVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$kotlinxCoroutinesVersion")

    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("net.logstash.logback:logstash-logback-encoder:8.0")
}
