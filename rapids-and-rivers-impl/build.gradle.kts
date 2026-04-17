dependencies {
    api(project(":kafka"))
    api(project(":rapids-and-rivers-api"))

    api(libs.jackson.module.kotlin)

    implementation(libs.opentelemetry.instrumentation.annotations)

    testImplementation(project(":rapids-and-rivers-test"))
    testImplementation(project(":kafka-test"))
    testImplementation(libs.awaitility)
    testImplementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.logback.classic)
    testImplementation(libs.logstash.logback.encoder)
}
