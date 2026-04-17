dependencies {
    api(libs.testcontainers.kafka)

    // Konsumenter av biblioteket må selv vurdere hvilken Kafka-versjon de vil ha
    // (implementation 'lekker' ikke ut på compile-classpath til konsumentene)
    implementation(libs.kafka.clients) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation(libs.jackson.module.kotlin)
}
