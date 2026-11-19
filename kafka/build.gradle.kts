val kafkaVersion = "4.4.0"

dependencies {
    api("org.apache.kafka:kafka-clients:$kafkaVersion")

    testImplementation(project(":kafka-test"))
}
