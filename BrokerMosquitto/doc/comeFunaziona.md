Listed directory BrokerMosquitto
Viewed docker-compose.yml:1-14
Listed directory centrale-operativa

L'architettura che è stata configurata in questa cartella si basa su un pattern di messaggistica **Publish/Subscribe (Pub/Sub)**, ed è un'ottima base per un'architettura a **microservizi**. 

Ecco una spiegazione semplice di come funziona l'intero sistema e di come puoi integrarlo con i tuoi microservizi.

### 1. Come funziona il sistema?

Il sistema si divide principalmente in due attori: il **Broker** e i **Client** (che nel tuo caso saranno i microservizi).

*   **Eclipse Mosquitto (Il Broker):** 
    È il "postino" o "centralino" del sistema. Gira all'interno di un container Docker (configurato nel `docker-compose.yml`) ed è sempre in ascolto sulla porta `1883`. Il suo unico compito è ricevere messaggi da chi li spedisce e inoltrarli a chi è interessato a riceverli.
*   **I Topic (Gli Indirizzi):**
    I messaggi non vengono inviati da un servizio A a un servizio B. Invece, vengono inviati su un "canale" chiamato **Topic** (es. `treni/posizione` o `sensori/temperatura`).
*   **Publish (Pubblicare) e Subscribe (Iscriversi):**
    *   Un microservizio **pubblica** (invia) un messaggio su un determinato Topic (es. un sensore sul treno che invia le sue coordinate al topic `treni/123/posizione`).
    *   Un altro microservizio si **iscrive** a quel Topic. Appena Mosquitto riceve il messaggio, lo smista automaticamente a tutti i microservizi iscritti (es. la `centrale-operativa` che aggiorna la mappa).

I microservizi **non si conoscono direttamente tra loro**: sanno solo dell'esistenza di Mosquitto. Questo disaccoppiamento è il vero potere di questa architettura!

---

### 2. Come usare i microservizi con questo sistema?

Per usare i microservizi, ogni nuovo servizio che crei diventerà un **Client MQTT**. Dato che stai usando **Quarkus** (nella cartella `centrale-operativa`), ecco i passi pratici per creare e far comunicare i tuoi microservizi:

#### A. Creare un nuovo microservizio
Puoi creare un nuovo progetto Quarkus (es. `gestione-treni` o `rilevamento-sensori`) e aggiungere l'estensione per MQTT. 
Se usi Maven, il comando è simile a questo:
```bash
./mvnw quarkus:add-extension -Dextensions="messaging-mqtt"
```

#### B. Configurare la connessione al Broker Mosquitto
Nel file `src/main/resources/application.properties` di ogni tuo microservizio, dovrai dire a Quarkus dove trovare Mosquitto e su quali Topic comunicare. Ad esempio:

```properties
# Indirizzo del broker Mosquitto (se lo avvii in locale con docker-compose)
mp.messaging.connector.smallrye-mqtt.host=localhost
mp.messaging.connector.smallrye-mqtt.port=1883

# --- Configurazione di un SUBSCRIBER (chi riceve) ---
# Il nome "ricevitore-posizione" è un nome interno che scegli tu.
mp.messaging.incoming.ricevitore-posizione.connector=smallrye-mqtt
mp.messaging.incoming.ricevitore-posizione.topic=treni/+/posizione
# (il '+' è un carattere jolly, significa "qualsiasi treno")

# --- Configurazione di un PUBLISHER (chi invia) ---
mp.messaging.outgoing.emettitore-allarmi.connector=smallrye-mqtt
mp.messaging.outgoing.emettitore-allarmi.topic=allarmi/generali
```

#### C. Scrivere il codice in Java (Quarkus)
Grazie a *SmallRye Reactive Messaging* integrato in Quarkus, inviare e ricevere messaggi è facilissimo tramite annotazioni:

**Esempio di Microservizio che RICEVE dati (Subscriber):**
```java
import org.eclipse.microprofile.reactive.messaging.Incoming;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GestorePosizioneTreni {

    // Si mette in ascolto sul canale definito nell'application.properties
    @Incoming("ricevitore-posizione")
    public void elaboraPosizione(byte[] messaggio) {
        String payload = new String(messaggio);
        System.out.println("Nuova posizione ricevuta: " + payload);
        // Qui scrivi la logica (es. salva nel database)
    }
}
```

**Esempio di Microservizio che INVIA dati (Publisher):**
```java
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SensoreAllarme {

    // Inietta l'emettitore collegato al canale definito nelle properties
    @Inject
    @Channel("emettitore-allarmi")
    Emitter<String> allarmeEmitter;

    public void segnalaProblema() {
        String messaggio = "Attenzione: Ostacolo sul binario 3!";
        allarmeEmitter.send(messaggio);
        System.out.println("Allarme inviato a Mosquitto!");
    }
}
```

#### D. Architettura Multilingua (Opzionale)
Il bello di MQTT è che **non sei obbligato ad usare solo Java**. 
Vuoi fare un microservizio in **Python** (usando la libreria `paho-mqtt`) per fare analisi dati? O un'app web in **Node.js**? Puoi farlo! Basta che si connettano tutti a `localhost:1883` e inizino a pubblicare/sottoscriversi ai topic.

### In sintesi, i passi per procedere:
1. Assicurati che Mosquitto sia acceso (`docker compose up -d` dentro la cartella `BrokerMosquitto`).
2. Sfrutta il file `alberoDeiTopic.md` per progettare bene la gerarchia dei tuoi messaggi (es. `stazione/binario/treno/...`).
3. Avvia la `centrale-operativa` e aggiungi eventuali altri microservizi Quarkus facendoli connettere alla stessa porta `1883`.

Hai in mente un microservizio specifico che vorresti creare ora per testarlo? Se vuoi possiamo implementarne uno di prova insieme.
