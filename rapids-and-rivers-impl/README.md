Rapids and rivers
=================

## Eksempel

```kotlin
River(rapidsConnection).apply {
    // preconditions will run before any regular validations.
    // use precondition to include only relevant messages, and filter away irrelevant messages.
    // any failed checks will result in onPreconditionError() being called
    precondition { it.requireValue("@event_name", "greeting") }
    // every key you want to access in onPacket() must be declared.
    // use validate() to check that the message is of the structure you need it to be
    // Don't validate() keys your application doesn't need/use
    // any failed checks will result in onError() being called
    validate { it.requireKey("name") }
}.register(GreetingListener())

class GreetingListener : River.PacketListener {
    override fun onPacket(packet: JsonMessage, context: MessageContext, metadata: MessageMetadata, meterRegistry: MeterRegistry) {
        println("Hello, ${packet["name"].asText()}")
    }

    override fun onPreconditionError(
        error: MessageProblems,
        context: MessageContext,
        metadata: MessageMetadata
    ) {
        /* one or more of the River.precondition() validations failed.
         *   usually these errors are NOT logged */
    }

    override fun onError(problems: MessageProblems, context: MessageContext, metadata: MessageMetadata) {
        /* one or more of the River.validate() validations failed.
         *   usualy you will LOG these as errors so you can fix the problem */
    }
}
```
