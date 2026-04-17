rootProject.name = "rapids-and-rivers"

include(
    "kafka",
    "kafka-test",
    "rapids-and-rivers-api",
    "rapids-and-rivers-impl",
    "rapids-and-rivers-test",
)

// Expose catalog versions as Gradle extra properties for use in allprojects {}
val toml = file("gradle/libs.versions.toml").readText()
val versionsSection = toml.substringAfter("[versions]").substringBefore("\n[")
val versionMap = versionsSection.lines()
    .mapNotNull { Regex("""^([\w-]+)\s*=\s*"([^"]+)"""").find(it.trim()) }
    .associate { it.groupValues[1] to it.groupValues[2] }
gradle.extra["jacksonVersion"] = versionMap["jackson"]!!
gradle.extra["jacksonAnnotationsVersion"] = versionMap["jackson-annotations"]!!
gradle.extra["junitJupiterVersion"] = versionMap["junit-jupiter"]!!
gradle.extra["testcontainersVersion"] = versionMap["testcontainers"]!!
