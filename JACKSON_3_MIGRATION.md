# Jackson 3 Migration

This document summarises the changes made when migrating this project from Jackson 2.x to Jackson 3.x.

## Build files

- **`build.gradle.kts`**: Version `2.18.3` → `3.1.2`. Added separate `jacksonAnnotationsVersion = "2.21"` because `jackson-annotations` remains on 2.x in Jackson 3. Updated BOM groupId `com.fasterxml.jackson:jackson-bom` → `tools.jackson:jackson-bom`. Updated kotlin module constraint groupId `com.fasterxml.jackson.module` → `tools.jackson.module`.
- **`rapids-and-rivers-impl/build.gradle.kts`**: Updated module groupIds; removed `jackson-datatype-jsr310` (merged into `jackson-databind` in Jackson 3).
- **`rapids-and-rivers-test/build.gradle.kts`**: Same as above.
- **`kafka-test/build.gradle.kts`**: Updated module groupId.

## Package renames

All `com.fasterxml.jackson.*` imports changed to `tools.jackson.*` across all source files, with one exception: `jackson-annotations` keeps the `com.fasterxml.jackson.annotation` package name (and stays on version 2.x).

| Jackson 2.x | Jackson 3.x |
|---|---|
| `com.fasterxml.jackson.core.*` | `tools.jackson.core.*` |
| `com.fasterxml.jackson.databind.*` | `tools.jackson.databind.*` |
| `com.fasterxml.jackson.module.kotlin.*` | `tools.jackson.module.kotlin.*` |
| `com.fasterxml.jackson.annotation.*` | `com.fasterxml.jackson.annotation.*` *(unchanged)* |

## API changes

### JavaTimeModule removed
`jackson-datatype-jsr310` has been merged into `jackson-databind`. Remove the dependency and all `registerModule(JavaTimeModule())` calls — JSR-310 types (`LocalDate`, `LocalDateTime`, `Instant`, etc.) are supported natively.

### ObjectMapper configuration
`ObjectMapper.disable()` / `enable()` no longer exist — `ObjectMapper` is now effectively immutable. Use the builder instead:

```kotlin
// Before
jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

// After
jacksonMapperBuilder()
    .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .build()
```

Note: both `WRITE_DATES_AS_TIMESTAMPS` and `FAIL_ON_UNKNOWN_PROPERTIES` are **disabled by default** in Jackson 3, so the explicit calls are optional but kept for clarity.

### SerializationFeature.WRITE_DATES_AS_TIMESTAMPS moved
`SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` was moved to `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` (import `tools.jackson.databind.cfg.DateTimeFeature`).

### JsonParseException replaced
`com.fasterxml.jackson.core.JsonParseException` → `tools.jackson.core.exc.StreamReadException`.

Note: in Jackson 3, `JacksonException` (and its subclasses) extend `RuntimeException` rather than `IOException`.

### TextNode renamed to StringNode
`com.fasterxml.jackson.databind.node.TextNode` → `tools.jackson.databind.node.StringNode`.

### textValue() deprecated in favour of stringValue()
`JsonNode.textValue()` is deprecated; use `stringValue()` instead.

`stringValue()` (and the deprecated `textValue()`) now **throws `JsonNodeException`** on non-string node types (e.g. `MissingNode`). Use `stringValue(null)` to get null-safe behaviour equivalent to the old `textValue()`:

```kotlin
// Before — returned null for any non-string node
node.textValue()

// After — still throws on MissingNode/non-string nodes
node.stringValue()

// After — returns null for any non-string node (equivalent to old textValue())
node.stringValue(null)
```

### JsonNode.fieldNames() renamed
`JsonNode.fieldNames()` (returned `Iterator<String>`) → `JsonNode.propertyNames()` (returns `Collection<String>`):

```kotlin
// Before
node.fieldNames().asSequence().toList()

// After
node.propertyNames().toList()
```

### JsonNode.map() conflict with Kotlin's Iterable.map()
Jackson 3 added `JsonNode.map(Function<JsonNode, R>)` which applies a function to *this* node (not to each element). This shadows Kotlin's `Iterable<JsonNode>.map {}` extension and causes `JsonNodeException` at runtime when called on container nodes (e.g. `ArrayNode`).

Use `mapTo(mutableListOf(), ...)` to explicitly call the Kotlin `Iterable` extension:

```kotlin
// Before
node.map(JsonNode::asString)          // worked via Kotlin's Iterable extension
node.map { it.asString() }

// After — must disambiguate
node.mapTo(mutableListOf(), JsonNode::asString)
node.mapTo(mutableListOf()) { it.asString() }
```
