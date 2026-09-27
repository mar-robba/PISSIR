package it.uni.reti2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * Console interattiva per pilotare i sensori di binario della stazione.
 * È la gemella di quella del Treno: le azioni non toccano mai direttamente il modello,
 * passano sempre dalle API REST esposte da {@link StationIngestion}, come farebbe un
 * terminale di manutenzione attaccato al nodo.
 *
 * <p>Serve soprattutto per le prove del meccanismo di fail-stop: si spegne l'heartbeat di
 * un sensore, si aspetta il timeout e si guarda la stazione diventare GUASTA (e i treni
 * fermarsi); si riaccende e si guarda tornare tutto operativo.</p>
 */
@ApplicationScoped
public class ConsoleSensori {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @ConfigProperty(name = "stazione.id", defaultValue = "S1")
    String stazioneId;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8081")
    int httpPort;

    /** Avvia il menu e resta in ascolto finché l'utente non sceglie di uscire. */
    public void avviaMenu() {
        Scanner scanner = new Scanner(System.in);
        boolean attivo = true;

        while (attivo) {
            stampaMenu();
            String scelta = scanner.nextLine().trim();

            switch (scelta) {
                case "1" -> stampaStatoSensori();
                case "2" -> cambiaHeartbeat(scanner, "stop");
                case "3" -> cambiaHeartbeat(scanner, "start");
                case "4" -> simulaGuasto(scanner);
                case "5" -> cambiaDestinazioneLog();
                case "0", "q", "Q" -> attivo = false;
                default -> System.out.println("Scelta non valida.");
            }
        }
    }

    private void stampaMenu() {
        System.out.println();
        System.out.println("=== SENSORI DI BINARIO - STAZIONE " + stazioneId + " ===");
        System.out.println("1) Stato dei sensori");
        System.out.println("2) FERMA l'heartbeat di un sensore (simula il guasto)");
        System.out.println("3) RIPRENDI l'heartbeat di un sensore");
        System.out.println("4) Segnala un guasto (stazione intera o un binario)");
        System.out.println("5) " + (LogSuFile.attivo()
                ? "Riporta i log a schermo (ora vanno su " + LogSuFile.fileCorrente() + ")"
                : "Manda i log su file (cartella logs/) e lascia solo il menu qui"));
        System.out.println("0) Esci");
        System.out.print("Scegli l'operazione: ");
    }

    /**
     * Stampa in tabella quello che risponde GET /stazione/sensori: per ogni sensore si
     * vede se sta battendo ("acceso") e se la stazione lo considera fermo ("guasto").
     */
    private void stampaStatoSensori() {
        String risposta = inviaGet("/stazione/sensori");
        if (risposta == null) {
            return;
        }

        try {
            JsonNode json = mapper.readTree(risposta);
            System.out.printf("Stazione %s -> stato %s (guasta per i sensori: %s)%n",
                    json.path("stazioneId").asText(), json.path("stato").asText(),
                    json.path("guastoDaSensori").asText());
            System.out.printf("%-16s %-10s %-8s %-8s %s%n", "SENSORE", "TIPO", "ACCESO", "GUASTO", "ULTIMO BATTITO");
            for (JsonNode sensore : json.path("sensori")) {
                System.out.printf("%-16s %-10s %-8s %-8s %s%n",
                        sensore.path("sensoreId").asText(),
                        sensore.path("tipo").asText(),
                        sensore.path("acceso").asText(),
                        sensore.path("guasto").asText(),
                        sensore.path("ultimoBattito").asText());
            }
        } catch (Exception e) {
            // Se il JSON non si legge si stampa comunque la risposta grezza: meglio di niente
            System.out.println(risposta);
        }
    }

    /**
     * Spegne o riaccende l'heartbeat di un sensore.
     *
     * @param azione "stop" per fermarlo, "start" per farlo ripartire.
     */
    private void cambiaHeartbeat(Scanner scanner, String azione) {
        stampaStatoSensori();
        System.out.print("Quale sensore? (IN = entrata, OUT = uscita, oppure l'id per intero): ");
        String sensore = scanner.nextLine().trim();
        if (sensore.isEmpty()) {
            System.out.println("Devi indicare un sensore.");
            return;
        }

        String esito = inviaPost("/stazione/sensori/" + sensore + "/" + azione, null);
        if (esito != null && "stop".equals(azione)) {
            System.out.println("Ora aspetta il timeout dei keepalive: la stazione si dichiarerà GUASTA da sola.");
        }
    }

    /** Inietta un transito come se lo avesse visto il sensore, senza aspettare un treno vero. */
    private void simulaPassaggio(Scanner scanner) {
        System.out.print("ID del treno: ");
        String trenoId = scanner.nextLine().trim();
        if (trenoId.isEmpty()) {
            System.out.println("L'ID del treno è obbligatorio.");
            return;
        }

        System.out.print("Tipo di passaggio (ENTRATA/USCITA): ");
        String tipo = scanner.nextLine().trim().toUpperCase();
        if (!"ENTRATA".equals(tipo) && !"USCITA".equals(tipo)) {
            System.out.println("Il tipo deve essere ENTRATA o USCITA.");
            return;
        }

        inviaPost("/stazione/sensore/treno",
                "{\"trenoId\":\"" + escapeJson(trenoId) + "\",\"tipo\":\"" + tipo + "\"}");
    }

    /**
     * Guasto dell'infrastruttura di terra: puo' essere l'intera stazione (alimentazione,
     * edificio) oppure un singolo binario/scambio. Chiede all'operatore quale dei due e'
     * il caso, cosi' la Centrale lo classifica correttamente invece di vederli tutti come
     * lo stesso guasto generico.
     */
    private void simulaGuasto(Scanner scanner) {
        System.out.print("Tipo di guasto (1 = stazione intera, 2 = binario/scambio) [1]: ");
        String scelta = scanner.nextLine().trim();
        boolean binario = "2".equals(scelta);
        String tipoGuasto = binario ? "binario_guasto" : "stazione_guasta";

        System.out.print("Descrizione del guasto: ");
        String descrizione = scanner.nextLine().trim();
        if (descrizione.isEmpty()) {
            descrizione = binario ? "Guasto a un binario/scambio" : "Guasto generico ai sistemi di terra";
        }

        inviaPost("/stazione/sensore/guasto",
                "{\"descrizione\":\"" + escapeJson(descrizione) + "\",\"tipoGuasto\":\"" + tipoGuasto + "\"}");
    }

    /**
     * Sposta i log dal terminale a un file dentro logs/ (o li riporta a schermo).
     * Il menu resta comunque qui: le sue stampe non passano dal logger.
     */
    private void cambiaDestinazioneLog() {
        try {
            if (LogSuFile.attivo()) {
                Path file = LogSuFile.fileCorrente();
                LogSuFile.disattiva();
                System.out.println("Log di nuovo a schermo (il file " + file + " resta dov'e').");
            } else {
                Path file = LogSuFile.attiva(stazioneId);
                System.out.println("Log dirottati su " + file.toAbsolutePath()
                        + " (seguili con: tail -f " + file + ")");
            }
        } catch (IOException e) {
            System.out.println("Non riesco a scrivere nella cartella logs: " + e.getMessage());
        }
    }

    private String inviaGet(String path) {
        HttpRequest richiesta = HttpRequest.newBuilder(URI.create(indirizzo(path))).GET().build();
        return esegui(richiesta, false);
    }

    private String inviaPost(String path, String body) {
        HttpRequest richiesta = HttpRequest.newBuilder(URI.create(indirizzo(path)))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return esegui(richiesta, true);
    }

    private String indirizzo(String path) {
        return "http://localhost:" + httpPort + path;
    }

    /**
     * @param stampaEsito true per stampare la risposta (comandi), false per restituirla soltanto
     *                    a chi la deve formattare (interrogazioni).
     * @return il corpo della risposta, null se la chiamata non è andata a buon fine.
     */
    private String esegui(HttpRequest richiesta, boolean stampaEsito) {
        try {
            HttpResponse<String> risposta = httpClient.send(richiesta, HttpResponse.BodyHandlers.ofString());
            if (risposta.statusCode() >= 200 && risposta.statusCode() < 300) {
                if (stampaEsito) {
                    System.out.println("OK: " + risposta.body());
                }
                return risposta.body();
            }
            System.out.printf("Comando rifiutato (HTTP %d): %s%n", risposta.statusCode(), risposta.body());
            return null;
        } catch (IOException e) {
            System.out.println("Impossibile raggiungere le API della stazione: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Chiamata interrotta.");
            return null;
        }
    }

    private String escapeJson(String valore) {
        return valore.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
