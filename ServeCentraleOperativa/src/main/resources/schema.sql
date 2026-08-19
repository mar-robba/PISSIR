-- ================================================================
-- SCHEMA DEL DATABASE CENTRALE
-- Monitoraggio e Gestione del Traffico Ferroviario
-- ================================================================

-- --- Stazioni ---
CREATE TABLE Stazione (
    id_stazione                  VARCHAR(50) PRIMARY KEY,
    nome                         VARCHAR(100) NOT NULL,
    tipoCapolineaPartenzaoNormale VARCHAR(50)
        CHECK (tipoCapolineaPartenzaoNormale
               IN ('capolinea','partenza','normale')),
    latitudine                   DOUBLE PRECISION,   -- ESTENSIONE: coordinate GPS per la mappa
    longitudine                  DOUBLE PRECISION,   -- ESTENSIONE
    binari                       INT                 -- ESTENSIONE: numero di binari
);

-- --- Tratte ---
CREATE TABLE Tratte (
    id_Tratta          VARCHAR(50) PRIMARY KEY,
    StazionePartenzaFK VARCHAR(50) NOT NULL,
    StazioneArrivoFK   VARCHAR(50) NOT NULL,
    tempoPercorrenzaMinuti INT DEFAULT 15,           -- ESTENSIONE: tempo nominale di percorrenza
    FOREIGN KEY (StazionePartenzaFK) REFERENCES Stazione(id_stazione),
    FOREIGN KEY (StazioneArrivoFK)   REFERENCES Stazione(id_stazione)
);

-- --- Utenti ---
-- Solo anagrafica: credenziali e ruoli stanno in Keycloak (realm "railway"), il
-- database centrale non conserva nessuna password. La matricola e' il campo che
-- lega la riga all'utente Keycloak (claim "matricola" del token).
CREATE TABLE Utenti (
    id_utente  VARCHAR(50) PRIMARY KEY,
    tipo       VARCHAR(50) NOT NULL,   -- es. 'operatore','tecnico','admin'
    nome       VARCHAR(100) NOT NULL,
    cognome    VARCHAR(100) NOT NULL,
    matricola  VARCHAR(50)  UNIQUE NOT NULL
);

-- --- Itinerari ---
CREATE TABLE Itinerari (
    id_itinerario VARCHAR(50) PRIMARY KEY
);

-- --- Treni ---
-- id_convoglio E' il nome del convoglio (es. "IC 351"): e' il valore che l'amministratore
-- digita nel campo "Convoglio" dell'interfaccia. Non esiste una colonna "nome" separata:
-- il nome coincide con la chiave primaria, quindi e' univoco per costruzione e immutabile
-- (per rinominare un convoglio si elimina il treno e si ricrea).
CREATE TABLE Treni (
    id_convoglio                    VARCHAR(50) PRIMARY KEY,
    stato                           VARCHAR(30) NOT NULL
        CHECK (stato IN ('attivo','fermo','rotto','in manutenzione')),
    itinerario                      VARCHAR(50),
    PosizioneAttualeTrattaOStazione VARCHAR(50),
    FOREIGN KEY (itinerario)
        REFERENCES Itinerari(id_itinerario),
    FOREIGN KEY (PosizioneAttualeTrattaOStazione)
        REFERENCES Tratte(id_Tratta)
);

-- Tabella associativa N:M Itinerari <-> Tratte
-- Un itinerario e' composto da piu' tratte ordinate
CREATE TABLE Itinerario_Tratta (
    id_itinerario VARCHAR(50) NOT NULL,
    id_Tratta     VARCHAR(50) NOT NULL,
    ordine        INT NOT NULL,   -- posizione della tratta nell'itinerario
    PRIMARY KEY (id_itinerario, id_Tratta),
    FOREIGN KEY (id_itinerario) REFERENCES Itinerari(id_itinerario),
    FOREIGN KEY (id_Tratta)     REFERENCES Tratte(id_Tratta)
);

-- --- Transiti ---
CREATE TABLE Transiti (
    id_transito    VARCHAR(50) PRIMARY KEY,
    id_stazione    VARCHAR(50) NOT NULL,
    id_convoglio   VARCHAR(50) NOT NULL,
    id_Tratta      VARCHAR(50),
    tempoEntrata   TIMESTAMP NOT NULL,
    tempoUscita    TIMESTAMP,
    ritardoMinuti  INT,        -- ESTENSIONE: ritardo del convoglio NELL'ISTANTE del passaggio
                               -- (congelato qui: quello in cache cambia di continuo)
    FOREIGN KEY (id_stazione)  REFERENCES Stazione(id_stazione),
    FOREIGN KEY (id_convoglio) REFERENCES Treni(id_convoglio),
    FOREIGN KEY (id_Tratta)    REFERENCES Tratte(id_Tratta)
);

-- --- Guasti Pervenuti da Treni o Stazioni ---
CREATE TABLE Guasti_Pervenuti_da_treni_o_Staz (
    id_Guasto                    VARCHAR(50) PRIMARY KEY,
    Stato_RisoltoONO             BOOLEAN NOT NULL DEFAULT FALSE,
    OperatoreCheSeNeStaOccupandoFK VARCHAR(50),
    tipo            VARCHAR(50),   -- ESTENSIONE: tipologia guasto (stazione_guasta/treno_fermo/sensore_offline)
    severita        VARCHAR(20),   -- ESTENSIONE: gravita' (CRITICAL/WARNING/INFO)
    sorgenteTipo    VARCHAR(20),   -- ESTENSIONE: tipo sorgente (STAZIONE/TRENO)
    sorgenteId      VARCHAR(50),   -- ESTENSIONE: id della sorgente del guasto
    origine         VARCHAR(30),   -- ESTENSIONE: SEGNALATO_CAMPO o DEDOTTO_CENTRALE
    messaggio       VARCHAR(500),  -- ESTENSIONE: descrizione leggibile
    ts_apertura     TIMESTAMP,     -- ESTENSIONE: istante di apertura
    ts_risoluzione  TIMESTAMP,     -- ESTENSIONE: istante di risoluzione (null se aperto)
    FOREIGN KEY (OperatoreCheSeNeStaOccupandoFK)
        REFERENCES Utenti(id_utente)
);

-- --- Eventi delle Stazioni (audit log) ---
-- Tabella usata dall'entita' EventoStazione: non era prevista dallo schema iniziale
-- e in esecuzione la crea Hibernate (quarkus.hibernate-orm.database.generation=update).
-- E' riportata qui perche' questo file e' il DDL di riferimento allegato alla relazione.
-- E' gia' una tabella di storico: nessuna FK, e il nome della stazione congelato dentro
-- la riga (vedi la regola degli storici piu' sotto). I nomi delle colonne sono quelli
-- che genera Hibernate dai campi dell'entita', cioe' tutti attaccati e minuscoli: la
-- tabella non nasce da questo file, quindi qui va scritta com'e' davvero.
CREATE TABLE eventi_stazioni (
    id           BIGINT PRIMARY KEY,   -- generato da Hibernate (sequenza dedicata)
    stazioneid   VARCHAR(255),         -- riferimento logico a Stazione(id_stazione), NON vincolato
    nomestazione VARCHAR(255),         -- nome della stazione al momento dell'evento
    stato        VARCHAR(255),         -- stato della stazione al momento dell'evento
    tipoevento   VARCHAR(255),         -- es. 'HEARTBEAT_LOST', 'GUASTO', 'MANUTENZIONE'
    descrizione  VARCHAR(255),
    timestamp    TIMESTAMP
);

-- ================================================================
-- STORICI (Tabelle Storiche) -- RF02.7 Memoria storica
-- ================================================================
-- REGOLA DI QUESTA SEZIONE: le tabelle di storico NON hanno chiavi esterne verso le
-- tabelle dello stato corrente (Stazione, Treni, Tratte, Itinerari, Guasti, Utenti).
--
-- Il motivo e' nell'enunciato del requisito: la storia deve restare consultabile anche
-- quando i nodi che l'hanno prodotta non ci sono piu'. Con la chiave esterna succedeva
-- l'opposto: per cancellare una stazione bisognava prima cancellare i suoi transiti
-- storici, quindi eliminare una stazione dalla rete voleva dire perdere tutti i
-- passaggi mai avvenuti li' dentro (e la tratta storicizzata non si poteva togliere
-- affatto). Il vincolo di integrita' e' giusto per lo stato corrente, dove il dato deve
-- essere coerente adesso; su un fatto gia' avvenuto e' fuori posto.
--
-- Al posto della chiave esterna ogni riga porta:
--   * l'identificativo "logico" dell'attore (stessa colonna di prima, senza vincolo);
--   * il suo nome e le informazioni che servono a rileggere la riga (tipo della
--     stazione, descrizione della tratta, matricola dell'operatore...), congelati
--     all'istante del fatto.
-- Cosi' la riga si legge da sola, senza join, e dice com'erano le cose allora e non
-- come sono adesso (se la stazione viene rinominata, lo storico conserva il nome
-- vecchio: e' un pregio, non un difetto).
--
-- L'unica chiave esterna ammessa e' fra due tabelle di storico
-- (Storico_Itinerari_Tratte -> Storico_Itinerari): li' non si punta allo stato corrente
-- e le due righe nascono e muoiono insieme.

-- --- Storico Transiti ---
CREATE TABLE Storico_Transiti (
    id_storico_transito BIGSERIAL PRIMARY KEY,
    id_transito         VARCHAR(50) NOT NULL,  -- riferimento logico alla riga di Transiti
    id_stazione         VARCHAR(50) NOT NULL,  -- riferimento logico, NIENTE FK
    nome_stazione       VARCHAR(100),          -- nome della stazione al passaggio
    id_convoglio        VARCHAR(50) NOT NULL,  -- e' anche il nome del convoglio (es. "IC 351")
    id_Tratta           VARCHAR(50),
    descrizione_tratta  VARCHAR(255),          -- "Milano Centrale -> Bologna Centrale"
    tempoEntrata        TIMESTAMP NOT NULL,
    tempoUscita         TIMESTAMP,
    ritardoMinuti       INT,       -- ESTENSIONE: ritardo al momento del passaggio, copiato da Transiti
    ts_storicizzazione  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- --- Storico Guasti ---
-- La riga nasce all'apertura del guasto e viene completata con ts_chiusura alla
-- risoluzione. Porta dentro tutta la descrizione dell'allarme, percio' resta leggibile
-- anche quando la riga viva viene archiviata.
CREATE TABLE Storico_Guasti (
    id_storico_guasto   BIGSERIAL PRIMARY KEY,
    id_Guasto           VARCHAR(50) NOT NULL,  -- riferimento logico, NIENTE FK
    tipo                VARCHAR(50),   -- stazione_guasta / treno_fermo / sensore_offline
    severita            VARCHAR(20),   -- CRITICAL / WARNING / INFO
    sorgenteTipo        VARCHAR(20),   -- STAZIONE o TRENO
    sorgenteId          VARCHAR(50),   -- riferimento logico alla sorgente
    nome_sorgente       VARCHAR(100),  -- nome della stazione (per il treno coincide con l'id)
    messaggio           VARCHAR(500),
    Stato_RisoltoONO    BOOLEAN NOT NULL,
    id_operatore        VARCHAR(50),   -- ex OperatoreCheSeNeStaOccupandoFK: non e' piu' una FK
    nome_operatore      VARCHAR(255),
    matricola_operatore VARCHAR(50),
    ts_apertura         TIMESTAMP NOT NULL,
    ts_chiusura         TIMESTAMP,
    ts_storicizzazione  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- --- Storico Stato Treni ---
-- Una riga per ogni CAMBIO di stato (non per ogni frame di telemetria): e' la regola di
-- registrazione di RF02.7. stato_precedente serve a leggere la riga come un cambiamento.
CREATE TABLE Storico_Stato_Treni (
    id_storico_treno                BIGSERIAL PRIMARY KEY,
    id_convoglio                    VARCHAR(50) NOT NULL,  -- riferimento logico = nome del convoglio
    stato                           VARCHAR(30) NOT NULL,
    stato_precedente                VARCHAR(30),
    itinerario                      VARCHAR(50),
    PosizioneAttualeTrattaOStazione VARCHAR(50),
    descrizione_posizione           VARCHAR(255),  -- "Milano Centrale -> Bologna Centrale"
    ts_storicizzazione              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- --- Storico Stato Stazioni ---
CREATE TABLE Storico_Stato_Stazioni (
    id_storico_stazione BIGSERIAL PRIMARY KEY,
    id_stazione         VARCHAR(50) NOT NULL,   -- riferimento logico, NIENTE FK
    nome                VARCHAR(100) NOT NULL,  -- nome che la stazione aveva allora
    tipoCapolineaPartenzaoNormale VARCHAR(50),
    stato               VARCHAR(30),   -- ONLINE / OFFLINE / GUASTA / MANUTENZIONE
    stato_precedente    VARCHAR(30),
    funzionanteONo      BOOLEAN,       -- riassunto booleano dello stato, resta per compatibilita'
    ts_storicizzazione  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- --- Storico Itinerari (itinerari percorsi dai convogli) ---
-- L'itinerario nel modello corrente e' solo un id: il percorso vero sta nella tabella
-- associativa, che l'amministratore puo' riscrivere quando vuole. Percio' qui si copia
-- anche il percorso, in chiaro e (tratta per tratta) in Storico_Itinerari_Tratte.
CREATE TABLE Storico_Itinerari (
    id_storico_itinerario BIGSERIAL PRIMARY KEY,
    id_itinerario         VARCHAR(50) NOT NULL,  -- riferimento logico, NIENTE FK
    id_convoglio          VARCHAR(50) NOT NULL,
    descrizione_percorso  VARCHAR(1000),  -- "Milano Centrale - Bologna Centrale - Firenze SMN"
    numero_tratte         INT,
    ts_assegnazione       TIMESTAMP NOT NULL,
    ts_completamento      TIMESTAMP,
    ts_storicizzazione    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- --- Storico delle tratte di un itinerario percorso ---
-- Sta a Storico_Itinerari come Itinerario_Tratta sta a Itinerari. E' l'unica tabella
-- con una chiave esterna, e punta a un altro storico: le due righe si scrivono insieme.
CREATE TABLE Storico_Itinerari_Tratte (
    id_storico_itinerario_tratta BIGSERIAL PRIMARY KEY,
    id_storico_itinerario        BIGINT NOT NULL,   -- riga padre nello storico
    ordine                       INT NOT NULL,      -- posizione della tratta nel percorso
    id_Tratta                    VARCHAR(50) NOT NULL,
    id_stazione_partenza         VARCHAR(50),
    nome_stazione_partenza       VARCHAR(100),
    id_stazione_arrivo           VARCHAR(50),
    nome_stazione_arrivo         VARCHAR(100),
    tempoPercorrenzaMinuti       INT,   -- il tempo nominale di allora
    ts_storicizzazione           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_storico_itinerario)
        REFERENCES Storico_Itinerari(id_storico_itinerario) ON DELETE CASCADE
);

-- --- Storico Assegnazione Operatori a Guasti ---
-- Chi ha preso in carico l'allarme: senza questa tabella, di un guasto chiuso resta
-- solo il fatto che e' stato chiuso.
CREATE TABLE Storico_Assegnazioni_Guasti (
    id_storico_assegnazione BIGSERIAL PRIMARY KEY,
    id_Guasto               VARCHAR(50) NOT NULL,  -- riferimento logico, NIENTE FK
    tipo_guasto             VARCHAR(50),
    sorgenteTipo            VARCHAR(20),
    sorgenteId              VARCHAR(50),
    nome_sorgente           VARCHAR(100),
    id_operatore            VARCHAR(50) NOT NULL,  -- ex id_utente: non e' piu' una FK
    nome_operatore          VARCHAR(255),
    matricola_operatore     VARCHAR(50),           -- legame con l'utente di Keycloak
    ruolo_operatore         VARCHAR(50),
    ts_assegnazione         TIMESTAMP NOT NULL,
    ts_risoluzione          TIMESTAMP,
    ts_storicizzazione      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- --- Storico Interventi di Manutenzione (invio della squadra a una stazione) ---
-- L'altra faccia delle "assegnazioni degli operatori" di RF02.7: il comando "Invia
-- Operatori" (RF01.4.1) finora passava solo da MQTT e WebSocket e non lasciava traccia.
CREATE TABLE Storico_Interventi_Manutenzione (
    id_storico_intervento BIGSERIAL PRIMARY KEY,
    id_stazione           VARCHAR(50) NOT NULL,  -- riferimento logico, NIENTE FK
    nome_stazione         VARCHAR(100),
    id_Guasto             VARCHAR(50),   -- guasto che ha motivato l'invio, se c'era
    id_operatore          VARCHAR(50),   -- chi ha dato il comando dalla web app
    nome_operatore        VARCHAR(255),
    matricola_operatore   VARCHAR(50),
    stato_stazione_prima  VARCHAR(30),
    stato_stazione_dopo   VARCHAR(30),
    ts_invio              TIMESTAMP NOT NULL,
    ts_rientro            TIMESTAMP,
    ts_storicizzazione    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
