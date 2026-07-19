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
               IN ('capolinea','partenza','normale'))
);

-- --- Tratte ---
CREATE TABLE Tratte (
    id_Tratta          VARCHAR(50) PRIMARY KEY,
    StazionePartenzaFK VARCHAR(50) NOT NULL,
    StazioneArrivoFK   VARCHAR(50) NOT NULL,
    FOREIGN KEY (StazionePartenzaFK) REFERENCES Stazione(id_stazione),
    FOREIGN KEY (StazioneArrivoFK)   REFERENCES Stazione(id_stazione)
);

-- --- Utenti ---
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
    FOREIGN KEY (id_stazione)  REFERENCES Stazione(id_stazione),
    FOREIGN KEY (id_convoglio) REFERENCES Treni(id_convoglio),
    FOREIGN KEY (id_Tratta)    REFERENCES Tratte(id_Tratta)
);

-- --- Guasti Pervenuti da Treni o Stazioni ---
CREATE TABLE Guasti_Pervenuti_da_treni_o_Staz (
    id_Guasto                    VARCHAR(50) PRIMARY KEY,
    Stato_RisoltoONO             BOOLEAN NOT NULL DEFAULT FALSE,
    OperatoreCheSeNeStaOccupandoFK VARCHAR(50),
    FOREIGN KEY (OperatoreCheSeNeStaOccupandoFK)
        REFERENCES Utenti(id_utente)
);

-- ================================================================
-- STORICI (Tabelle Storiche)
-- ================================================================

-- --- Storico Transiti ---
CREATE TABLE Storico_Transiti (
    id_storico_transito SERIAL PRIMARY KEY,
    id_transito         VARCHAR(50) NOT NULL,
    id_stazione         VARCHAR(50) NOT NULL,
    id_convoglio        VARCHAR(50) NOT NULL,
    id_Tratta           VARCHAR(50),
    tempoEntrata        TIMESTAMP NOT NULL,
    tempoUscita         TIMESTAMP,
    ts_storicizzazione  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_stazione)  REFERENCES Stazione(id_stazione),
    FOREIGN KEY (id_convoglio) REFERENCES Treni(id_convoglio),
    FOREIGN KEY (id_Tratta)    REFERENCES Tratte(id_Tratta)
);

-- --- Storico Guasti ---
CREATE TABLE Storico_Guasti (
    id_storico_guasto              SERIAL PRIMARY KEY,
    id_Guasto                      VARCHAR(50) NOT NULL,
    Stato_RisoltoONO               BOOLEAN NOT NULL,
    OperatoreCheSeNeStaOccupandoFK VARCHAR(50),
    ts_apertura                    TIMESTAMP NOT NULL,
    ts_chiusura                    TIMESTAMP,
    ts_storicizzazione             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_Guasto)
        REFERENCES Guasti_Pervenuti_da_treni_o_Staz(id_Guasto),
    FOREIGN KEY (OperatoreCheSeNeStaOccupandoFK)
        REFERENCES Utenti(id_utente)
);

-- --- Storico Stato Treni ---
CREATE TABLE Storico_Stato_Treni (
    id_storico_treno                SERIAL PRIMARY KEY,
    id_convoglio                    VARCHAR(50) NOT NULL,
    stato                           VARCHAR(30) NOT NULL,
    itinerario                      VARCHAR(50),
    PosizioneAttualeTrattaOStazione VARCHAR(50),
    ts_storicizzazione              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_convoglio) REFERENCES Treni(id_convoglio)
);

-- --- Storico Stato Stazioni ---
CREATE TABLE Storico_Stato_Stazioni (
    id_storico_stazione SERIAL PRIMARY KEY,
    id_stazione         VARCHAR(50) NOT NULL,
    nome                VARCHAR(100) NOT NULL,
    tipoCapolineaPartenzaoNormale VARCHAR(50),
    funzionanteONo      BOOLEAN,
    ts_storicizzazione  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_stazione) REFERENCES Stazione(id_stazione)
);

-- --- Storico Itinerari ---
CREATE TABLE Storico_Itinerari (
    id_storico_itinerario SERIAL PRIMARY KEY,
    id_itinerario         VARCHAR(50) NOT NULL,
    id_convoglio          VARCHAR(50) NOT NULL,
    ts_assegnazione       TIMESTAMP NOT NULL,
    ts_completamento      TIMESTAMP,
    ts_storicizzazione    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_itinerario) REFERENCES Itinerari(id_itinerario),
    FOREIGN KEY (id_convoglio)  REFERENCES Treni(id_convoglio)
);

-- --- Storico Assegnazione Operatori a Guasti ---
CREATE TABLE Storico_Assegnazioni_Guasti (
    id_storico_assegnazione SERIAL PRIMARY KEY,
    id_Guasto               VARCHAR(50) NOT NULL,
    id_utente               VARCHAR(50) NOT NULL,
    ts_assegnazione         TIMESTAMP NOT NULL,
    ts_risoluzione          TIMESTAMP,
    ts_storicizzazione      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_Guasto) REFERENCES
        Guasti_Pervenuti_da_treni_o_Staz(id_Guasto),
    FOREIGN KEY (id_utente) REFERENCES Utenti(id_utente)
);
