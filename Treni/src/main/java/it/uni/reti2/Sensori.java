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
 * Console interattiva per pilotare i sensori di bordo del convoglio.
 * È la gemella di quella della Stazione ({@code ConsoleSensori}): le azioni non toccano
 * mai direttamente il modello, passano sempre dalle API REST esposte da
 * {@link TrainIngestion}, come farebbe il computer di bordo attaccato alla diagnostica.
 *
 * <p>Serve soprattutto per le prove a mano: si guarda la fotografia del viaggio (fase,
 * stazione corrente, ritardo comunicato dalla Centrale), si inietta un guasto e si vede
 * il convoglio andare in EMERGENZA e la Centrale accorgersene, oppure si forza un
 * transito senza aspettare che il treno arrivi davvero in stazione.</p>
 */
@ApplicationScoped
public class Sensori {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @ConfigProperty(name = "treno.id", defaultValue = "TRN001")
    String trenoId;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8082")
    int httpPort;

    /** Avvia il menu e resta in ascolto finché l'utente non sceglie di uscire. */
    public void avviaMenu() {
        Scanner scanner = new Scanner(System.in);
        boolean attivo = true;

        while (attivo) {
            stampaMenu();
            String scelta = scanner.nextLine().trim();

            switch (scelta) {
                case "1" -> stampaStatoTreno();
                case "2" -> stampaTratta();
                case "3" -> simulaGuasto(scanner);
                case "4" -> cambiaDestinazioneLog();
                case "0", "q", "Q" -> attivo = false;
                default -> System.out.println("Scelta non valida.");
            }
        }
    }

    private void stampaMenu() {
        System.out.println();
        System.out.println("=== SENSORI DI BORDO - TRENO " + trenoId + " ===");
        System.out.println("1) Stato del convoglio (sensori di bordo)");
        System.out.println("2) Tratta e itinerario");
        System.out.println("3) Segnala un guasto di bordo");
        System.out.println("4) " + (LogSuFile.attivo()
                ? "Riporta i log a schermo (ora vanno su " + LogSuFile.fileCorrente() + ")"
                : "Manda i log su file (cartella logs/) e lascia solo il menu qui"));
        System.out.println("0) Esci");
        System.out.print("Scegli l'operazione: ");
    }

    /**
     * Stampa quello che risponde GET /treno/viaggio: è la fotografia completa del digital
     * twin, cioè quello che i sensori di bordo (posizione, tachimetro, contapasseggeri)
     * dicono in questo momento, più il ritardo che la Centrale ha comunicato al convoglio.
     */
    private void stampaStatoTreno() {
        String risposta = inviaGet("/treno/viaggio");
        if (risposta == null) {
            return;
        }

        try {
            JsonNode json = mapper.readTree(risposta);
            System.out.printf("Treno %s -> stato %s (fase %s, direzione %s)%n",
                    json.path("trenoId").asText(), json.path("stato").asText(),
                    json.path("faseViaggio").asText(), json.path("direzione").asText());
            System.out.printf("%-14s %-14s %-10s %-10s %s%n",
                    "STAZIONE", "PROSSIMA", "PROGRESSO", "VELOCITA", "PASSEGGERI");
            System.out.printf("%-14s %-14s %-10s %-10s %s%n",
                    vuotoSeAssente(json.path("stazioneCorrente").asText()),
                    vuotoSeAssente(json.path("prossimaStazione").asText()),
                    String.format("%.0f%%", json.path("progressPercent").asDouble()),
                    String.format("%.1f km/h", json.path("velocita").asDouble()),
                    json.path("passeggeri").asText());
            System.out.printf("Posizione: %s, %s%n",
                    json.path("latitudine").asText(), json.path("longitudine").asText());

            // Il ritardo non lo conta il treno: è l'ultimo valore arrivato dalla Centrale
            System.out.printf("Ritardo comunicato dalla Centrale: %s min%s%n",
                    json.path("ritardoMinuti").asText(),
                    json.path("ritardoRilevatoA").asText().isEmpty()
                            ? ""
                            : " (rilevato a " + json.path("ritardoRilevatoA").asText()
                              + " il " + json.path("ritardoAggiornatoIl").asText() + ")");

            String bloccante = json.path("stazioneBloccante").asText();
            if (!bloccante.isEmpty()) {
                System.out.println("⚠️  Fermo per la stazione " + bloccante);
            }
        } catch (Exception e) {
            // Se il JSON non si legge si stampa comunque la risposta grezza: meglio di niente
            System.out.println(risposta);
        }
    }

    /** Elenca le stazioni della tratta caricata, così si sa cosa scrivere nel passaggio. */
    private void stampaTratta() {
        String risposta = inviaGet("/treno/viaggio");
        if (risposta == null) {
            return;
        }

        try {
            JsonNode json = mapper.readTree(risposta);
            if (!json.path("itinerarioCaricato").asBoolean()) {
                System.out.println("Nessun itinerario caricato: il treno non ha ancora una tratta.");
                return;
            }
            System.out.println("Itinerario " + vuotoSeAssente(json.path("itinerarioId").asText())
                    + " (indice stazione corrente: " + json.path("indiceStazione").asText() + ")");
            int posizione = 0;
            for (JsonNode stazione : json.path("stazioni")) {
                boolean qui = posizione == json.path("indiceStazione").asInt();
                System.out.printf("  %s %d) %s%n", qui ? "->" : "  ", posizione, stazione.asText());
                posizione++;
            }
        } catch (Exception e) {
            System.out.println(risposta);
        }
    }

    /** Inietta un transito come se lo avesse visto il sensore di bordo, senza aspettare il viaggio. */
    private void simulaPassaggio(Scanner scanner) {
        stampaTratta();
        System.out.print("ID della stazione: ");
        String stazioneId = scanner.nextLine().trim();
        if (stazioneId.isEmpty()) {
            System.out.println("L'ID della stazione è obbligatorio.");
            return;
        }

        System.out.print("Tipo di passaggio (ENTRATA/USCITA): ");
        String tipo = scanner.nextLine().trim().toUpperCase();
        if (!"ENTRATA".equals(tipo) && !"USCITA".equals(tipo)) {
            System.out.println("Il tipo deve essere ENTRATA o USCITA.");
            return;
        }

        inviaPost("/treno/sensore/passaggio",
                "{\"stazioneId\":\"" + escapeJson(stazioneId) + "\",\"tipo\":\"" + tipo + "\"}");
    }

    /**
     * Avaria di bordo: la Centrale la classifica sempre come "treno_fermo" (lo deduce dal
     * tipo di sorgente), quindi qui non si sceglie il tipo come nella stazione, si sceglie
     * quale sensore diagnostico ha dato l'allarme. La scelta finisce nella descrizione,
     * che è quello che l'operatore legge nella dashboard.
     */
    private void simulaGuasto(Scanner scanner) {
        System.out.println("Quale sensore di bordo segnala l'avaria?");
        System.out.println("  1) Freni");
        System.out.println("  2) Motore / trazione");
        System.out.println("  3) Porte");
        System.out.println("  4) Altro");
        System.out.print("Scelta [1]: ");
        String scelta = scanner.nextLine().trim();

        String componente = switch (scelta) {
            case "2" -> "Avaria al motore/trazione";
            case "3" -> "Avaria alle porte";
            case "4" -> "Guasto generico treno";
            default -> "Avaria all'impianto frenante";
        };

        System.out.print("Descrizione del guasto [" + componente + "]: ");
        String descrizione = scanner.nextLine().trim();
        if (descrizione.isEmpty()) {
            descrizione = componente;
        }

        String esito = inviaPost("/treno/sensore/guasto",
                "{\"descrizione\":\"" + escapeJson(descrizione) + "\"}");
        if (esito != null) {
            System.out.println("Il convoglio si ferma subito (stato EMERGENZA) e la Centrale riceve l'alert.");
        }
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
                Path file = LogSuFile.attiva(trenoId);
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
            System.out.println("Impossibile raggiungere le API del treno: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Chiamata interrotta.");
            return null;
        }
    }

    private String vuotoSeAssente(String valore) {
        return valore == null || valore.isEmpty() ? "-" : valore;
    }

    private String escapeJson(String valore) {
        return valore.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
