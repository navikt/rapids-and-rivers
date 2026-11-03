val kafkaVersion = "4.3.1"

dependencies {
    api("org.apache.kafka:kafka-clients:$kafkaVersion")

    testImplementation(project(":kafka-test"))
}
