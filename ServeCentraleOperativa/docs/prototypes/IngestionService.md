## IngestionService.java

### Event handlers pubblici

- public CompletionStage<Void> onTelemetry(Message<byte[]> message)
- public CompletionStage<Void> onHeartbeat(Message<byte[]> message)
- public CompletionStage<Void> onTransit(Message<byte[]> message)
- public CompletionStage<Void> onAlert(Message<byte[]> message)
- public CompletionStage<Void> onPassaggio(Message<byte[]> message)
- public void broadcastAlert(Guasto guasto)

### Metodi privati di utilità

- private String normalizeDecimalComma(String s)
- private String normalizzaStatoTreno(String rawStato)
- private String statoStazionePerFrontend(String stato)
- private Instant parseTimestamp(JsonNode root)
- private void storicizzaTransito(Transito transito)
- private void broadcastTransit(JsonNode root, String trenoId, String stazioneId, String tipo)
- private String tipoGuastoPerFrontend(String sorgenteTipo, String messaggio)
- private String calcolaProssimaStazione(Treno treno, String stazioneId, String direzione)
- private List<String> stazioniOrdinateDiItinerario(String itinerarioId)
