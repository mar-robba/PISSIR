-- ================================================================
-- SCHEMA DEL DATABASE LOCALE - STAZIONE
-- ================================================================

CREATE TABLE Stato (
    id_stazione                     INT PRIMARY KEY,
    nomeStazione                    VARCHAR(100) NOT NULL,
    regioneGeograficaDiAppartenenza VARCHAR(100),
    funzionanteONo                  BOOLEAN,
    flagFaultInCorso                BOOLEAN,
    timestampInizioUltimoFault      TIMESTAMP,
    timestampFineUltimoFault        TIMESTAMP
);

CREATE TABLE Tratte (
    id_tratta        INT PRIMARY KEY,  -- deve essere lo stesso del DB centrale
    stazionePartenza INT NOT NULL,
    stazioneArrivo   INT NOT NULL
);

CREATE TABLE Guasto_Locale_Momentaneo (
    id_Guasto        INT PRIMARY KEY,
    statoRisoltoONo  BOOLEAN,
    tipo             VARCHAR(50),
    descrizione      VARCHAR(255),
    timestamp        TIMESTAMP
);

-- Tabella associativa per la relazione (0,N)-(0,N) Tratte <-> Guasto_Locale_Momentaneo
CREATE TABLE Tratta_Guasto (
    id_tratta INT NOT NULL,
    id_Guasto INT NOT NULL,
    PRIMARY KEY (id_tratta, id_Guasto),
    FOREIGN KEY (id_tratta) REFERENCES Tratte(id_tratta),
    FOREIGN KEY (id_Guasto) REFERENCES Guasto_Locale_Momentaneo(id_Guasto)
);

CREATE TABLE Treno (
    id_Treno      INT PRIMARY KEY,
    id_transito   INT,
    tempoEntrata   TIMESTAMP,
    tempoPartenza TIMESTAMP
);

CREATE TABLE bufferChache (
    id_Evento INT PRIMARY KEY,
    tipo      VARCHAR(50),
    id_Guasto INT,
    id_Treno  INT NOT NULL, 
    FOREIGN KEY (id_Guasto) REFERENCES Guasto_Locale_Momentaneo(id_Guasto),
    FOREIGN KEY (id_Treno)  REFERENCES Treno(id_Treno)
);
