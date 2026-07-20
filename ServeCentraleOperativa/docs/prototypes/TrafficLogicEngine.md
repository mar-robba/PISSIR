## TrafficLogicEngine.java

### Inizializzazione cache

- void onStart(@Observes StartupEvent ev)

### Metodi pubblici della cache

- public void aggiornaTreno(Treno treno)
- public Treno getTreno(String id)
- public List<Treno> getTuttiTreni()
- public void aggiornaStazione(Stazione stazione)
- public Stazione getStazione(String id)
- public List<Stazione> getTutteStazioni()
- public void aggiungiGuasto(Guasto guasto)
- public void risolviGuasto(String id)
- public List<Guasto> getGuastiAttivi()
- public void rimuoviTreno(String id)
- public void rimuoviStazione(String id)
- public Guasto getGuastoApertoPerSorgente(String sorgenteId, String tipo)
- public Map<String, Object> kpiDashboard()
