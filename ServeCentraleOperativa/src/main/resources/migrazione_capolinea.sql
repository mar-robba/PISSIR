-- ================================================================
-- MIGRAZIONE CAPOLINEA - l'itinerario puo' chiudere il servizio
-- ================================================================
-- Da eseguire UNA VOLTA SOLA sul database railway gia' in uso:
--
--   docker exec -i railway-postgres psql -U postgres -d railway \
--       < ServeCentraleOperativa/src/main/resources/migrazione_capolinea.sql
--
-- Poi va riavviata la Centrale, perche' le entita' sono cambiate.
--
-- NOTA (30/09/2026): la Centrale non usa piu' Hibernate. All'avvio esegue schema.sql, che
-- crea le tabelle che mancano ma NON aggiunge colonne a tabelle che esistono gia'. Il
-- "PERCHE' SERVE" qui sotto racconta la situazione di quando il file e' nato; oggi vale a
-- maggior ragione: su un database vecchio le colonne nuove le aggiunge solo questo file.
--
-- PERCHE' SERVE: la colonna nuova (Itinerari.capolinea) la aggiunge da sola Hibernate,
-- che gira con quarkus.hibernate-orm.database.generation=update. Il vincolo CHECK sulla
-- colonna Treni.stato no: in quella modalita' Hibernate non tocca mai un vincolo gia'
-- esistente, quindi un database nato da schema.sql continuerebbe a rifiutare il valore
-- 'capolinea' al momento del COMMIT. E' il caso peggiore, perche' l'eccezione non arriva
-- quando lo stato viene scritto in memoria ma molto dopo, e li' risale fino al connettore
-- reattivo e spegne il canale MQTT (vedi il commento in testa a StatiCanonici).
--
-- Su un database creato da zero, o creato da Hibernate (che i CHECK non li genera
-- affatto), questo file non cambia niente: e' scritto per essere innocuo in tutti e due
-- i casi, e si puo' rilanciare quante volte si vuole.

BEGIN;

-- ----------------------------------------------------------------
-- 1. La stazione che chiude il servizio, sull'itinerario
-- ----------------------------------------------------------------
-- NULL = comportamento di sempre: il convoglio percorre l'itinerario avanti e indietro
-- senza fine. Valorizzata = il treno che arriva li' termina la corsa.
ALTER TABLE Itinerari ADD COLUMN IF NOT EXISTS capolinea VARCHAR(50);

DO $$
BEGIN
    -- La chiave esterna si aggiunge solo se non c'e' gia': ALTER TABLE ... ADD CONSTRAINT
    -- non ha la forma "IF NOT EXISTS".
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'itinerari'::regclass
          AND conname  = 'itinerari_capolinea_fkey'
    ) THEN
        ALTER TABLE Itinerari
            ADD CONSTRAINT itinerari_capolinea_fkey
            FOREIGN KEY (capolinea) REFERENCES Stazione(id_stazione);
    END IF;
END $$;

-- ----------------------------------------------------------------
-- 2. 'capolinea' fra gli stati ammessi del convoglio
-- ----------------------------------------------------------------
-- Il vincolo si chiama treni_stato_check se il database e' nato da schema.sql, ma un
-- nome diverso se qualcuno l'ha ricreato a mano: si cercano tutti i CHECK della tabella
-- che nominano la colonna stato e si buttano, poi se ne rimette uno solo, quello giusto.
DO $$
DECLARE
    vincolo RECORD;
BEGIN
    FOR vincolo IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'treni'::regclass
          AND contype  = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%stato%'
    LOOP
        EXECUTE format('ALTER TABLE Treni DROP CONSTRAINT %I', vincolo.conname);
    END LOOP;

    ALTER TABLE Treni
        ADD CONSTRAINT treni_stato_check
        CHECK (stato IN ('attivo','fermo','rotto','in manutenzione','capolinea'));
END $$;

COMMIT;
