[![](https://jitpack.io/v/navikt/rapids-and-rivers.svg)](https://jitpack.io/#navikt/rapids-and-rivers)

# Rapids and rivers

Bibliotek for enkelt å kunne lage mikrotjenester som bruker konseptet rapids and rivers til @fredgeorge.

## Konsepter

- Alle publiserer på rapid. Kan lese fra flere topics, men publiserer kun på rapid-topic
- Rivers filtrerer meldinger etter hvilke kriterier de har
- `isalive` er true så snart rapids connection er startet
- `isready` er true så snart `onStartup`-lytterne er ferdige. KafkaRapid vil ikke begynne å polle meldinger før etter 
onStartup-lytterne er ferdige, og vil dermed ikke bli assignet partisjoner av brokerne.

### Kjøreregler

#### Appen min har database

- Kjør migreringer i `onStartup`
- Bruk rollout strategy `Recreate`. Ellers vil du ha én pod som leser meldinger og skriver til db, mens den andre holder på med migreringer 

#### Appen min har rest-api (og database)

- Samme kjøreregler som over, bare at du vil få nedetid på api-et
- Rest-api-delen av appen bør skilles ut som egen app som har readonly-connection mot databasen. Dersom migreringene er 
bakover-kompatible så kan unngår man nedetid, og man kan migrere en "live" database

### Appen min består bare av kafka

- Tut å kjør. Rollout strategy `RollingUpdate` vil fungere helt utmerket

## Quick start 

```kotlin
fun main() {
    val env = System.getenv()

    val dataSourceBuilder = DataSourceBuilder(env)
    val dataSource = dataSourceBuilder.getDataSource()

    RapidApplication.create(env).apply {
        MyCoolApp(this, MyDao(dataSource))
    }.apply {
        register(object : RapidsConnection.StatusListener {
            override fun onStartup(rapidsConnection: RapidsConnection) {
                // migrate database before consuming messages, but after rapids have started (and isalive returns OK)
                dataSourceBuilder.migrate()
            }
        })
    }.start()
}

internal class MyCoolApp(
    rapidsConnection: RapidsConnection,
    private val myDao: MyDao
) : River.PacketListener {

    init {
        River(rapidsConnection).apply {
            validate { it.requireValue("@event_name", "my_event") }
            validate { it.requireKey("a_required_key") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        println(packet["a_required_key"].asText())
    }
}    
```

### Forutsetninger/defaults

- Servicebruker mountes inn på `/var/run/secrets/nais.io/service_user`
- Bootstrap servers angis ved miljøvariabel `KAFKA_BOOTSTRAP_SERVERS`
- Consumer group angis med miljøvariabel `KAFKA_CONSUMER_GROUP_ID`
- Rapid topic angis med miljøvariabel `KAFKA_RAPID_TOPIC`
- Rivers angis med miljøvariabel `KAFKA_EXTRA_TOPIC`(Komma separert liste hvis flere rivers.)

Rapids-biblioteket bundler egen `logback.xml` så det trengs ikke spesifiseres i mikrotjenestene.
Den bundlede `logback.xml` har konfigurasjon for secureLogs (men husk å enable secureLogs i nais.yaml!), tilgjengelig med:
```
LoggerFactory.getLogger("tjenestekall")
```

# Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

## For NAV-ansatte

Interne henvendelser kan sendes via Slack i kanalen #rapids-and-rivers.
