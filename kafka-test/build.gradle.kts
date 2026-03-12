val testcontainersVersion: String by project
val kafkaVersion = "4.2.0"

dependencies {
    api("org.testcontainers:testcontainers-kafka:$testcontainersVersion")
    // konsumenter av biblioteket må selv vurdere hvilken kafkaversjon de vil ha
    // (implementation 'lekker' ikke ut på compile-classpath til konsumentene
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion") {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
