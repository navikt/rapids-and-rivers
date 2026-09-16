val slf4jVersion = "2.0.19"
val micrometerRegistryPrometheusVersion = "1.17.1"

dependencies {
    api("org.slf4j:slf4j-api:$slf4jVersion")

    api("io.micrometer:micrometer-core:$micrometerRegistryPrometheusVersion")
}
