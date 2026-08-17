-- ================================================================
-- MIGRAZIONE RF02.7 - gli storici perdono le chiavi esterne
-- ================================================================
-- Da eseguire UNA VOLTA SOLA sul database railway gia' in uso:
--
--   docker exec -i railway-postgres psql -U postgres -d railway \
--       < ServeCentraleOperativa/src/main/resources/migrazione_storici_rf0207.sql
--
-- Poi va riavviata la Centrale, perche' le entita' sono cambiate.
--
-- PERCHE' SERVE: la Centrale gira con quarkus.hibernate-orm.database.generation=update.
-- In quella modalita' Hibernate AGGIUNGE da solo le colonne e le tabelle nuove, ma non
-- toglie mai un vincolo gia' esistente e non rinomina niente. Le chiavi esterne degli
-- storici resterebbero quindi dentro il database anche dopo essere sparite dalle entita',
-- e la prima DELETE di una stazione con dei transiti storici fallirebbe con una
-- violazione di vincolo (adesso il gateway non cancella piu' la storia, apposta).
--
-- Su un database creato da zero con schema.sql questo file NON serve.
-- Lo script e' idempotente: si puo' rilanciare senza fare danni.

BEGIN;

-- ----------------------------------------------------------------
-- 1. Via le chiavi esterne verso le tabelle dello stato corrente
-- ----------------------------------------------------------------
ALTER TABLE Storico_Transiti            DROP CONSTRAINT IF EXISTS storico_transiti_id_stazione_fkey;
ALTER TABLE Storico_Transiti            DROP CONSTRAINT IF EXISTS storico_transiti_id_convoglio_fkey;
ALTER TABLE Storico_Transiti            DROP CONSTRAINT IF EXISTS storico_transiti_id_tratta_fkey;
ALTER TABLE Storico_Stato_Treni         DROP CONSTRAINT IF EXISTS storico_stato_treni_id_convoglio_fkey;
ALTER TABLE Storico_Stato_Stazioni      DROP CONSTRAINT IF EXISTS storico_stato_stazioni_id_stazione_fkey;
ALTER TABLE Storico_Guasti              DROP CONSTRAINT IF EXISTS storico_guasti_id_guasto_fkey;
ALTER TABLE Storico_Guasti              DROP CONSTRAINT IF EXISTS storico_guasti_operatorechesenestaoccupandofk_fkey;
ALTER TABLE Storico_Itinerari           DROP CONSTRAINT IF EXISTS storico_itinerari_id_itinerario_fkey;
ALTER TABLE Storico_Itinerari           DROP CONSTRAINT IF EXISTS storico_itinerari_id_convoglio_fkey;
ALTER TABLE Storico_Assegnazioni_Guasti DROP CONSTRAINT IF EXISTS storico_assegnazioni_guasti_id_guasto_fkey;
ALTER TABLE Storico_Assegnazioni_Guasti DROP CONSTRAINT IF EXISTS storico_assegnazioni_guasti_id_utente_fkey;

-- ----------------------------------------------------------------
-- 2. Colonne nuove: al posto della FK, l'attore descritto per esteso
-- ----------------------------------------------------------------
ALTER TABLE Storico_Transiti  ADD COLUMN IF NOT EXISTS nome_stazione      VARCHAR(100);
ALTER TABLE Storico_Transiti  ADD COLUMN IF NOT EXISTS descrizione_tratta VARCHAR(255);

ALTER TABLE Storico_Stato_Treni ADD COLUMN IF NOT EXISTS stato_precedente      VARCHAR(30);
ALTER TABLE Storico_Stato_Treni ADD COLUMN IF NOT EXISTS descrizione_posizione VARCHAR(255);

ALTER TABLE Storico_Stato_Stazioni ADD COLUMN IF NOT EXISTS stato            VARCHAR(30);
ALTER TABLE Storico_Stato_Stazioni ADD COLUMN IF NOT EXISTS stato_precedente VARCHAR(30);

ALTER TABLE Storico_Guasti ADD COLUMN IF NOT EXISTS tipo                VARCHAR(50);
ALTER TABLE Storico_Guasti ADD COLUMN IF NOT EXISTS severita            VARCHAR(20);
ALTER TABLE Storico_Guasti ADD COLUMN IF NOT EXISTS sorgenteTipo        VARCHAR(20);
ALTER TABLE Storico_Guasti ADD COLUMN IF NOT EXISTS sorgenteId          VARCHAR(50);
ALTER TABLE Storico_Guasti ADD COLUMN IF NOT EXISTS nome_sorgente       VARCHAR(100);
ALTER TABLE Storico_Guasti ADD COLUMN IF NOT EXISTS messaggio           VARCHAR(500);
ALTER TABLE Storico_Guasti ADD COLUMN IF NOT EXISTS nome_operatore      VARCHAR(255);
ALTER TABLE Storico_Guasti ADD COLUMN IF NOT EXISTS matricola_operatore VARCHAR(50);

ALTER TABLE Storico_Itinerari ADD COLUMN IF NOT EXISTS descrizione_percorso VARCHAR(1000);
ALTER TABLE Storico_Itinerari ADD COLUMN IF NOT EXISTS numero_tratte        INT;

ALTER TABLE Storico_Assegnazioni_Guasti ADD COLUMN IF NOT EXISTS tipo_guasto         VARCHAR(50);
ALTER TABLE Storico_Assegnazioni_Guasti ADD COLUMN IF NOT EXISTS sorgenteTipo        VARCHAR(20);
ALTER TABLE Storico_Assegnazioni_Guasti ADD COLUMN IF NOT EXISTS sorgenteId          VARCHAR(50);
ALTER TABLE Storico_Assegnazioni_Guasti ADD COLUMN IF NOT EXISTS nome_sorgente       VARCHAR(100);
ALTER TABLE Storico_Assegnazioni_Guasti ADD COLUMN IF NOT EXISTS nome_operatore      VARCHAR(255);
ALTER TABLE Storico_Assegnazioni_Guasti ADD COLUMN IF NOT EXISTS matricola_operatore VARCHAR(50);
ALTER TABLE Storico_Assegnazioni_Guasti ADD COLUMN IF NOT EXISTS ruolo_operatore     VARCHAR(50);

-- eventi_stazioni non c'e' piu': ogni sua colonna era gia' in Storico_Guasti (id e nome
-- della stazione, istante, id del guasto dentro la descrizione) e le altre due erano due
-- costanti scritte a mano, 'OFFLINE' e 'HEARTBEAT_LOST'. La scriveva un punto solo del
-- codice e non la leggeva nessuno. La DROP sta in fondo al file, dopo il COMMIT, perche'
-- e' l'unica istruzione che butta via dei dati: se non la si vuole eseguire basta non
-- lanciare quelle righe, il resto della migrazione e' indipendente.

-- Larghezze: le descrizioni sono "nome -> nome" e i nomi delle stazioni arrivano a 100
-- caratteri l'uno, quindi 200 non basterebbero. Stessa cosa per "nome cognome"
-- dell'operatore. (Se le colonne sono gia' larghe cosi', l'ALTER non fa niente.)
ALTER TABLE Storico_Transiti            ALTER COLUMN descrizione_tratta    TYPE VARCHAR(255);
ALTER TABLE Storico_Stato_Treni         ALTER COLUMN descrizione_posizione TYPE VARCHAR(255);
ALTER TABLE Storico_Guasti              ALTER COLUMN nome_operatore        TYPE VARCHAR(255);
ALTER TABLE Storico_Assegnazioni_Guasti ALTER COLUMN nome_operatore        TYPE VARCHAR(255);
ALTER TABLE Storico_Itinerari           ALTER COLUMN descrizione_percorso  TYPE VARCHAR(1000);

-- ----------------------------------------------------------------
-- 3. Colonne rinominate: il suffisso "FK" era diventato una bugia
-- ----------------------------------------------------------------
-- (RENAME COLUMN non ha IF EXISTS, quindi si controlla prima.)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'storico_guasti'
                 AND column_name = 'operatorechesenestaoccupandofk') THEN
        ALTER TABLE Storico_Guasti RENAME COLUMN OperatoreCheSeNeStaOccupandoFK TO id_operatore;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'storico_assegnazioni_guasti'
                 AND column_name = 'id_utente') THEN
        ALTER TABLE Storico_Assegnazioni_Guasti RENAME COLUMN id_utente TO id_operatore;
    END IF;
END $$;

-- ----------------------------------------------------------------
-- 4. Tabelle nuove: le tratte di un itinerario percorso e gli
--    interventi di manutenzione (le scrivono il repository e il
--    comando "Invia Operatori", vedi RailwayRepository)
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS Storico_Itinerari_Tratte (
    id_storico_itinerario_tratta BIGSERIAL PRIMARY KEY,
    id_storico_itinerario        BIGINT NOT NULL,
    ordine                       INT NOT NULL,
    id_Tratta                    VARCHAR(50) NOT NULL,
    id_stazione_partenza         VARCHAR(50),
    nome_stazione_partenza       VARCHAR(100),
    id_stazione_arrivo           VARCHAR(50),
    nome_stazione_arrivo         VARCHAR(100),
    tempoPercorrenzaMinuti       INT,
    ts_storicizzazione           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_storico_itinerario)
        REFERENCES Storico_Itinerari(id_storico_itinerario) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS Storico_Interventi_Manutenzione (
    id_storico_intervento BIGSERIAL PRIMARY KEY,
    id_stazione           VARCHAR(50) NOT NULL,
    nome_stazione         VARCHAR(100),
    id_Guasto             VARCHAR(50),
    id_operatore          VARCHAR(50),
    nome_operatore        VARCHAR(255),
    matricola_operatore   VARCHAR(50),
    stato_stazione_prima  VARCHAR(30),
    stato_stazione_dopo   VARCHAR(30),
    ts_invio              TIMESTAMP NOT NULL,
    ts_rientro            TIMESTAMP,
    ts_storicizzazione    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Stessa larghezza delle altre, nel caso la tabella fosse gia' stata creata da una
-- versione precedente di questo script.
ALTER TABLE Storico_Interventi_Manutenzione ALTER COLUMN nome_operatore TYPE VARCHAR(255);

-- ----------------------------------------------------------------
-- 5. Riempimento delle colonne nuove per le righe gia' presenti
-- ----------------------------------------------------------------
-- Best effort: si usa l'anagrafica di adesso, che per le righe vecchie e' la sola
-- informazione disponibile. Dove l'attore non esiste piu' la colonna resta vuota
-- (da qui in avanti, invece, il nome lo scrive chi registra l'evento).

UPDATE Storico_Transiti st
SET nome_stazione = s.nome
FROM Stazione s
WHERE st.id_stazione = s.id_stazione AND st.nome_stazione IS NULL;

UPDATE Storico_Transiti st
SET descrizione_tratta = sp.nome || ' -> ' || sa.nome
FROM Tratte t
JOIN Stazione sp ON sp.id_stazione = t.StazionePartenzaFK
JOIN Stazione sa ON sa.id_stazione = t.StazioneArrivoFK
WHERE st.id_Tratta = t.id_Tratta AND st.descrizione_tratta IS NULL;

UPDATE Storico_Stato_Treni sst
SET descrizione_posizione = sp.nome || ' -> ' || sa.nome
FROM Tratte t
JOIN Stazione sp ON sp.id_stazione = t.StazionePartenzaFK
JOIN Stazione sa ON sa.id_stazione = t.StazioneArrivoFK
WHERE sst.PosizioneAttualeTrattaOStazione = t.id_Tratta
  AND sst.descrizione_posizione IS NULL;

UPDATE Storico_Guasti sg
SET tipo          = g.tipo,
    severita      = g.severita,
    sorgenteTipo  = g.sorgenteTipo,
    sorgenteId    = g.sorgenteId,
    messaggio     = g.messaggio
FROM Guasti_Pervenuti_da_treni_o_Staz g
WHERE sg.id_Guasto = g.id_Guasto AND sg.sorgenteId IS NULL;

-- Nome della sorgente: per una stazione si legge dall'anagrafica, per un convoglio
-- l'identificativo E' gia' il nome.
UPDATE Storico_Guasti sg
SET nome_sorgente = s.nome
FROM Stazione s
WHERE sg.sorgenteId = s.id_stazione
  AND sg.sorgenteTipo = 'STAZIONE'
  AND sg.nome_sorgente IS NULL;

UPDATE Storico_Guasti
SET nome_sorgente = sorgenteId
WHERE sorgenteTipo = 'TRENO' AND nome_sorgente IS NULL AND sorgenteId IS NOT NULL;

COMMIT;

-- ================================================================
-- ELIMINAZIONE DI eventi_stazioni
-- ================================================================
-- Sta qui in fondo, fuori dalla transazione, perche' e' l'unico punto della migrazione
-- che cancella qualcosa. Prima di lanciarla si puo' controllare che ogni riga abbia
-- davvero la gemella nello storico dei guasti: questa query deve tornare zero.
--
--   SELECT count(*) FROM eventi_stazioni e
--    WHERE NOT EXISTS (SELECT 1 FROM Storico_Guasti sg
--                       WHERE sg.id_guasto = replace(e.descrizione,
--                             'Heartbeat mancante, guasto automatico ', ''));
--
-- DROP TABLE IF EXISTS eventi_stazioni;

-- Controllo finale: questa query non deve tornare piu' nessuna riga.
-- SELECT conrelid::regclass, conname FROM pg_constraint
-- WHERE contype = 'f' AND conrelid::regclass::text LIKE 'storico%'
--   AND confrelid::regclass::text NOT LIKE 'storico%';
