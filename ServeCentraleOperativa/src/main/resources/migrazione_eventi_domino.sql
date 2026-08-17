-- ================================================================
-- MIGRAZIONE EVENTI DOMINO - gli storici registrano anche la CAUSA
-- ================================================================
-- Da eseguire sul database railway gia' in uso:
--
--   docker exec -i railway-postgres psql -U postgres -d railway \
--       < ServeCentraleOperativa/src/main/resources/migrazione_eventi_domino.sql
--
-- PERCHE' E' FACOLTATIVA: la Centrale gira con
-- quarkus.hibernate-orm.database.generation=update, e in quella modalita' Hibernate
-- aggiunge da solo le colonne nuove al primo avvio dopo questa modifica. Le colonne sono
-- tutte annullabili, quindi le righe che c'erano prima restano valide.
--
-- A COSA SERVE ALLORA: al riempimento della catena sulle righe dei guasti che esistono
-- gia'. Un guasto senza catena e' un guasto primario, cioe' la catena parte da lui: senza
-- questo riempimento le query sulle conseguenze dovrebbero trattare a parte il caso della
-- colonna vuota. Lo script e' idempotente: si puo' rilanciare senza fare danni.
--
-- Su un database creato da zero con schema.sql questo file NON serve.

BEGIN;

-- ----------------------------------------------------------------
-- 1. Le colonne (se Hibernate le ha gia' aggiunte, questi comandi non fanno niente)
-- ----------------------------------------------------------------
-- PERCHE' un nodo e' cambiato di stato. Sono riferimenti logici e NON chiavi esterne,
-- come tutto il resto degli storici (regola di RF02.7 scritta in schema.sql): la storia
-- deve restare leggibile anche quando il nodo che l'ha causata non e' piu' in rete.
ALTER TABLE Storico_Stato_Treni    ADD COLUMN IF NOT EXISTS causa_tipo VARCHAR(20);
ALTER TABLE Storico_Stato_Treni    ADD COLUMN IF NOT EXISTS causa_id   VARCHAR(50);
ALTER TABLE Storico_Stato_Treni    ADD COLUMN IF NOT EXISTS catena_id  VARCHAR(50);

ALTER TABLE Storico_Stato_Stazioni ADD COLUMN IF NOT EXISTS causa_tipo VARCHAR(20);
ALTER TABLE Storico_Stato_Stazioni ADD COLUMN IF NOT EXISTS causa_id   VARCHAR(50);
ALTER TABLE Storico_Stato_Stazioni ADD COLUMN IF NOT EXISTS catena_id  VARCHAR(50);

-- La catena sui guasti: un guasto puo' essere la conseguenza di un altro (la stazione resa
-- impercorribile da un convoglio guasto sui suoi binari e' un guasto vero, che l'operatore
-- deve risolvere, ma non e' un'avaria in piu'). Con la catena si sa che e' lo stesso fatto.
ALTER TABLE Guasti_Pervenuti_da_treni_o_Staz ADD COLUMN IF NOT EXISTS catena_id VARCHAR(50);
ALTER TABLE Storico_Guasti                   ADD COLUMN IF NOT EXISTS catena_id VARCHAR(50);

-- La percorribilita' delle tratte (RF02.1.2.2.2): tabella nuova, la crea Hibernate al
-- primo avvio. E' qui per chi preferisce vedere lo schema per intero in un posto solo.
CREATE TABLE IF NOT EXISTS Storico_Stato_Tratte (
    id_storico_tratta    BIGSERIAL PRIMARY KEY,
    id_Tratta            VARCHAR(50) NOT NULL,
    descrizione_tratta   VARCHAR(255),
    id_stazione_partenza VARCHAR(50),
    id_stazione_arrivo   VARCHAR(50),
    stato                VARCHAR(30),
    stato_precedente     VARCHAR(30),
    causa_tipo           VARCHAR(20),
    causa_id             VARCHAR(50),
    catena_id            VARCHAR(50),
    ts_storicizzazione   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------
-- 2. I guasti che c'erano gia' sono primari: la catena parte da loro
-- ----------------------------------------------------------------
UPDATE Guasti_Pervenuti_da_treni_o_Staz
SET catena_id = id_Guasto
WHERE catena_id IS NULL;

UPDATE Storico_Guasti
SET catena_id = id_Guasto
WHERE catena_id IS NULL;

COMMIT;

-- Controllo: nessun guasto deve restare senza catena. Le due query devono tornare zero.
-- SELECT count(*) FROM Guasti_Pervenuti_da_treni_o_Staz WHERE catena_id IS NULL;
-- SELECT count(*) FROM Storico_Guasti WHERE catena_id IS NULL;
--
-- E questa e' la domanda che le colonne nuove rendono possibile: che cosa ha prodotto in
-- tutta la rete un singolo guasto.
-- SELECT id_convoglio, stato, stato_precedente, causa_tipo, causa_id, ts_storicizzazione
--   FROM Storico_Stato_Treni WHERE catena_id = 'MI-1755438667123'
--  ORDER BY ts_storicizzazione;
