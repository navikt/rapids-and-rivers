val slf4jVersion = "2.0.17"
val micrometerRegistryPrometheusVersion = "1.14.5"

dependencies {
    api("org.slf4j:slf4j-api:$slf4jVersion")

    api("io.micrometer:micrometer-core:$micrometerRegistryPrometheusVersion")
}
