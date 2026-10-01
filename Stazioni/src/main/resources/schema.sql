-- ================================================================
-- SCHEMA DEL DATABASE LOCALE - STAZIONE
-- ================================================================
-- Questo file e' il DDL che il nodo Stazione esegue all'avvio su un file
-- SQLite (vedi la classe
-- PersistenzaLocale). Serve a sopravvivere a un fault energetico del nodo: se
-- il processo muore, al riavvio la stazione ritrova lo stato che aveva (GUASTA
-- oppure ONLINE, i treni ancora fermi sui binari, la coda degli eventi non
-- ancora spediti alla Centrale) invece di ripartire da zero.
--
-- Differenze rispetto al modello concettuale, tutte volute:
--  * SQLite non ha i tipi BOOLEAN e TIMESTAMP: i booleani diventano INTEGER
--    0/1 e i timestamp diventano TEXT in ISO-8601 UTC, cioe' esattamente la
--    stringa che stampa Instant.toString().
--  * gli identificativi del sistema sono stringhe ("MI", "Jaz") e non interi,
--    quindi id_stazione e id_Treno sono TEXT.
--  * bufferChache ha in piu' la colonna payload: quello che va rispedito alla
--    Centrale e' il JSON gia' pronto per il topic, il riferimento al guasto o
--    al treno da solo non basterebbe a ricostruirlo. Per lo stesso motivo qui
--    id_Treno e' nullable, perche' un alert su un sensore muto non parla di
--    nessun treno.
--  * le FOREIGN KEY restano dichiarate perche' sono parte del modello, ma il
--    nodo NON attiva "PRAGMA foreign_keys=ON": l'endpoint di simulazione
--    POST /stazione/sensore/treno puo' generare un transito per un convoglio
--    che in questo nodo non e' mai entrato davvero, e con i vincoli attivi
--    quell'evento non riuscirebbe nemmeno a finire nel buffer.
--  * IF NOT EXISTS ovunque, perche' il DDL viene rieseguito a ogni avvio.

-- Lo stato del nodo: una sola riga, quella della stazione di questo processo.
-- nomeStazione e regioneGeograficaDiAppartenenza appartengono all'anagrafica
-- che vive nel database della Centrale: il nodo di campo conosce solo il
-- proprio id, quindi ci ricopia l'id e lascia la regione a NULL.
CREATE TABLE IF NOT EXISTS Stato (
    id_stazione                     TEXT PRIMARY KEY,
    nomeStazione                    TEXT NOT NULL,
    regioneGeograficaDiAppartenenza TEXT,
    funzionanteONo                  INTEGER,
    flagFaultInCorso                INTEGER,
    timestampInizioUltimoFault      TEXT,
    timestampFineUltimoFault        TEXT
);

-- Copia locale delle tratte che toccano la stazione (stessi id del db centrale).
-- Nessuno la riempie: il nodo Stazione non ragiona per tratte, e' la Centrale a
-- tenere il grafo della rete. La tabella resta perche' fa parte del modello.
CREATE TABLE IF NOT EXISTS Tratte (
    id_tratta        TEXT PRIMARY KEY,     -- deve essere lo stesso del DB centrale (li' e' un VARCHAR)
    stazionePartenza TEXT NOT NULL,
    stazioneArrivo   TEXT NOT NULL
);

-- I guasti per cui questo nodo e' GUASTO: quelli che segnala lui (sensori muti,
-- avarie dei sottosistemi locali) e quello che gli dichiara la Centrale per un
-- convoglio guasto sui binari (RF02.1.2.2.1). L'allarme di un treno trattenuto
-- non apre una riga: e' la conseguenza di un guasto gia' aperto, non un guasto
-- nuovo. Al riavvio e' da qui che si capisce se la stazione era guasta per i soli
-- sensori, e quindi puo' rientrare da sola (vedi AutomaStatoStazione).
-- id_Guasto e' INTEGER PRIMARY KEY, che in SQLite e' un alias di rowid: se non lo
-- si passa lo assegna lui in modo progressivo, ed e' una numerazione LOCALE (la
-- Centrale ha la sua).
CREATE TABLE IF NOT EXISTS Guasto_Locale_Momentaneo (
    id_Guasto        INTEGER PRIMARY KEY,
    statoRisoltoONo  INTEGER,
    tipo             TEXT,
    descrizione      TEXT,
    timestamp        TEXT
);

-- Tabella associativa per la relazione (0,N)-(0,N) Tratte <-> Guasto_Locale_Momentaneo
CREATE TABLE IF NOT EXISTS Tratta_Guasto (
    id_tratta TEXT NOT NULL,
    id_Guasto INTEGER NOT NULL,
    PRIMARY KEY (id_tratta, id_Guasto),
    FOREIGN KEY (id_tratta) REFERENCES Tratte(id_tratta),
    FOREIGN KEY (id_Guasto) REFERENCES Guasto_Locale_Momentaneo(id_Guasto)
);

-- L'ultimo transito di ogni treno. La chiave e' il treno, quindi ogni convoglio
-- ha una riga sola: chi ha tempoPartenza a NULL e' ancora fermo in stazione, ed
-- e' proprio da questa query che riparte la mappa dei treni presenti al riavvio.
CREATE TABLE IF NOT EXISTS Treno (
    id_Treno      TEXT PRIMARY KEY,
    id_transito   INTEGER,
    tempoEntrata  TEXT,
    tempoPartenza TEXT
);

-- La coda store and forward: ogni transito e ogni allarme per la Centrale ci
-- passa, e la riga si cancella solo quando il broker l'ha preso. Mentre la
-- Centrale non e' raggiungibile le righe si accumulano, e al ritorno della
-- connessione ripartono in blocco. L'ordine FIFO e' dato da id_Evento crescente
-- (vedi LocalBuffer e ConsumatoreBuffer).
CREATE TABLE IF NOT EXISTS bufferChache (
    id_Evento INTEGER PRIMARY KEY,
    tipo      TEXT,                      -- il canale di destinazione: "transit" oppure "alert"
    id_Guasto INTEGER,
    id_Treno  TEXT,
    payload   TEXT NOT NULL,             -- il JSON gia' pronto per il topic
    timestamp TEXT,
    FOREIGN KEY (id_Guasto) REFERENCES Guasto_Locale_Momentaneo(id_Guasto),
    FOREIGN KEY (id_Treno)  REFERENCES Treno(id_Treno)
);
