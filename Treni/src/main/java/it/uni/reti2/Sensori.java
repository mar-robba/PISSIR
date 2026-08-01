package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;

/**
 * Console interattiva per simulare i sensori di bordo.
 * Le azioni non modificano direttamente il modello: passano sempre dalle API
 * REST esposte da {@link TrainIngestion}, come farebbe un sensore esterno.
 */
@ApplicationScoped
public class Sensori {

    private final HttpClient httpClient = HttpClient.newHttpClient();

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
                case "1" -> simulaGuasto(scanner);
                case "2" -> simulaPassaggio(scanner);
                case "0", "q", "Q" -> attivo = false;
                default -> System.out.println("Scelta non valida.");
            }
        }
    }

    private void stampaMenu() {
        System.out.println();
        System.out.println("=== SIMULAZIONE SENSORI TRENO ===");
        System.out.println("1) Sensore guasto");
        System.out.println("2) Sensore passaggio stazione");
        System.out.println("0) Esci");
        System.out.print("Scegli il sensore da attivare: ");
    }

    private void simulaGuasto(Scanner scanner) {
        System.out.print("Descrizione del guasto: ");
        String descrizione = scanner.nextLine().trim();
        if (descrizione.isEmpty()) {
            descrizione = "Guasto generico treno";
        }
        inviaPost("/treno/sensore/guasto", "{\"descrizione\":\"" + escapeJson(descrizione) + "\"}");
    }

    private void simulaPassaggio(Scanner scanner) {
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

        String body = "{\"stazioneId\":\"" + escapeJson(stazioneId)
                + "\",\"tipo\":\"" + tipo + "\"}";
        inviaPost("/treno/sensore/passaggio", body);
    }

    private void inviaPost(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("Sensore attivato: " + response.body());
            } else {
                System.out.printf("Attivazione rifiutata (HTTP %d): %s%n", response.statusCode(), response.body());
            }
        } catch (IOException e) {
            System.out.println("Impossibile raggiungere TrainIngestion: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Invio al sensore interrotto.");
        }
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
