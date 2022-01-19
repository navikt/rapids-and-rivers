[![](https://jitpack.io/v/navikt/rapids-and-rivers.svg)](https://jitpack.io/#navikt/rapids-and-rivers)

# Rapids and rivers

Bibliotek for enkelt å kunne lage mikrotjenester som bruker konseptet rapids and rivers til [@fredgeorge](https://github.com/fredgeorge/). For mer info kan man se denne videoen https://vimeo.com/79866979

## Konsepter

- Alle publiserer på rapid. Kan lese fra flere topics, men publiserer kun på rapid-topic
- Rivers filtrerer meldinger etter hvilke kriterier de har
- `isalive` er true så snart rapids connection er startet
- `isready` er true så snart `onStartup`-lytterne er ferdige. KafkaRapid vil ikke begynne å polle meldinger før etter 
onStartup-lytterne er ferdige, og vil dermed ikke bli assignet partisjoner av brokerne.
- Rivers vil kun få packets i `onPacket` når `MessageProblems` er fri for feilmeldinger (errors og severe)
- Rivers kan bruke `require*()`-funksjoner for å akkumulere errors i et `MessageProblems`-objekt som sendes til `onError`
- Rivers kan bruke `demand*()`-funksjoner for å stoppe parsing ved feil. Exception sendes til `onSevere`

Man kan bruke en kombinasjon av `demand*()` og `require*()`. For eksempel om alle meldingene har et `@event_name`, så kan man bruke 
`demandValue("@event_name", "my_event")` for å avbryte parsing når event-navnet ikke er som forventet. Dersom man har alle andre former
for validering med `require*()`, så kan man f.eks. logge innholdet i pakken i `onError` i lag med en feilmelding som sier noe sånn som `klarte ikke å parse my_event`.
Dersom man ikke benytter seg av `demand*()` så er det umulig å vite i `onError()` hvorvidt `@event_name` var forventet verdi eller ikke, og logging vil dermed ende opp med å spamme
med alle meldinger på rapiden som riveren ikke forstår.

### Kjøreregler

#### Appen min har database

- Kjør migreringer i `onStartup`
- Bruk rollout strategy `Recreate`. Ellers vil du ha én pod som leser meldinger og skriver til db, mens den andre holder på med migreringer 

#### Appen min har rest-api (og database)

- Samme kjøreregler som over, bare at du vil få nedetid på api-et
- Rest-api-delen av appen bør skilles ut som egen app som har readonly-connection mot databasen. Dersom migreringene er 
bakover-kompatible så kan man unngå nedetid, og man kan migrere en "live" database

### Appen min består bare av kafka

- Tut og kjør. Rollout strategy `RollingUpdate` vil fungere helt utmerket

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
            validate { it.demandValue("@event_name", "my_event") }
            validate { it.requireKey("a_required_key") }
            // nested objects can be chained using "."
            validate { it.requireValue("nested.key", "works_as_well") }
        }.register(this)
    }
   
    override fun onError(problems: MessageProblems, context: MessageContext) {
        /* fordi vi bruker demandValue() på event_name kan vi trygt anta at meldingen
           er "my_event", og at det er minst én av de ulike require*() som har feilet */   
    }

    override fun onPacket(packet: JsonMessage, context: MessageContext) {
        println(packet["a_required_key"].asText())
        // nested objects can be chained using "."
        println(packet["nested.key"].asText())
    }
}    
```

### Forutsetninger/defaults

- Servicebruker mountes inn på `/var/run/secrets/nais.io/service_user`
- Bootstrap servers angis ved miljøvariabel `KAFKA_BOOTSTRAP_SERVERS`
- Consumer group angis med miljøvariabel `KAFKA_CONSUMER_GROUP_ID`
- Rapid topic angis med miljøvariabel `KAFKA_RAPID_TOPIC`
- Rivers angis med miljøvariabel `KAFKA_EXTRA_TOPIC`(Kommaseparert liste hvis flere rivers.)
- For å bruke SSL-autentisering (Aiven) må man angi miljøvariablene `KAFKA_KEYSTORE_PATH` og `KAFKA_KEYSTORE_PASSWORD`

Rapids-biblioteket bundler egen `logback.xml` så det trengs ikke spesifiseres i mikrotjenestene.
Den bundlede `logback.xml` har konfigurasjon for secureLogs (men husk å enable secureLogs i nais.yaml!), tilgjengelig med:
```
LoggerFactory.getLogger("tjenestekall")
```

# Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

## For NAV-ansatte

Interne henvendelser kan sendes via Slack i kanalen #rapids-and-rivers.
