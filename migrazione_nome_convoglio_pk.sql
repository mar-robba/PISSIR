-- ===================================================================
-- MIGRAZIONE UNA TANTUM: il nome del convoglio diventa la chiave primaria
-- ===================================================================
-- Prima la tabella Treni aveva due identita' per lo stesso convoglio:
--   * id_convoglio  -> chiave primaria (spesso un id generato, es. tr-1785881107516)
--   * nome          -> nome mostrato in interfaccia (es. "Genoveffa"), rinominabile
-- Ora l'unico identificativo e' il nome: e' quello che l'amministratore digita nel
-- campo "Convoglio" e diventa id_convoglio. La colonna nome viene eliminata.
--
-- Lo script porta il database esistente al nuovo modello senza perdere gli storici:
-- le FK di Transiti/Storici NON sono ON UPDATE CASCADE, quindi per ogni treno da
-- rinominare si inserisce la riga con la nuova chiave, si ripuntano i figli e si
-- cancella la riga vecchia.
--
-- Uso:  psql -h localhost -U postgres -d railway -f migrazione_nome_convoglio_pk.sql
-- Va lanciato con la Centrale Operativa SPENTA (la sua cache in RAM e' l'immagine
-- della tabella Treni: si riallinea al riavvio). I digital twin dei treni rinominati
-- vanno rilanciati col nome nuovo, che e' il loro nuovo id.
-- ===================================================================

BEGIN;

-- Se la colonna non c'e' piu', la migrazione e' gia' stata applicata: si annulla tutto.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'treni' AND column_name = 'nome'
    ) THEN
        RAISE EXCEPTION 'Migrazione già applicata: la colonna Treni.nome non esiste più.';
    END IF;
END $$;

-- 1. Mappa vecchia chiave -> nuova chiave (solo i treni che cambiano davvero).
CREATE TEMP TABLE mappa_rinomina ON COMMIT DROP AS
SELECT id_convoglio AS vecchio, nome AS nuovo
FROM Treni
WHERE nome IS NOT NULL AND nome <> '' AND nome <> id_convoglio;

-- 2. Controlli di sicurezza: la nuova chiave deve essere univoca e non deve
--    collidere con un treno che resta col suo id attuale.
DO $$
DECLARE
    conflitto TEXT;
BEGIN
    SELECT string_agg(nuovo, ', ') INTO conflitto
    FROM (SELECT nuovo FROM mappa_rinomina GROUP BY nuovo HAVING count(*) > 1) d;
    IF conflitto IS NOT NULL THEN
        RAISE EXCEPTION 'Due treni hanno lo stesso nome, impossibile usarlo come chiave: %', conflitto;
    END IF;

    SELECT string_agg(m.nuovo, ', ') INTO conflitto
    FROM mappa_rinomina m
    JOIN Treni t ON t.id_convoglio = m.nuovo
    WHERE t.id_convoglio NOT IN (SELECT vecchio FROM mappa_rinomina);
    IF conflitto IS NOT NULL THEN
        RAISE EXCEPTION 'Il nome è già la chiave di un altro treno: %', conflitto;
    END IF;
END $$;

-- 3. La colonna nome non serve piu': il nome E' la chiave primaria. Si elimina adesso,
--    prima di inserire le righe nuove: la mappa del punto 1 la ha gia' letta, e finche'
--    la colonna esiste il suo vincolo UNIQUE impedirebbe di avere per un istante due
--    righe (la vecchia e la nuova) con lo stesso nome.
--    (il DROP porta via anche il vincolo UNIQUE che c'era sulla colonna)
ALTER TABLE Treni DROP COLUMN nome;

-- 4. Inserisce le righe con la nuova chiave (copia dello stato attuale del treno).
INSERT INTO Treni (id_convoglio, stato, itinerario, PosizioneAttualeTrattaOStazione)
SELECT m.nuovo, t.stato, t.itinerario, t.PosizioneAttualeTrattaOStazione
FROM mappa_rinomina m
JOIN Treni t ON t.id_convoglio = m.vecchio;

-- 5. Ripunta storici e transiti sulla nuova chiave.
UPDATE Transiti f            SET id_convoglio = m.nuovo FROM mappa_rinomina m WHERE f.id_convoglio = m.vecchio;
UPDATE Storico_Transiti f    SET id_convoglio = m.nuovo FROM mappa_rinomina m WHERE f.id_convoglio = m.vecchio;
UPDATE Storico_Stato_Treni f SET id_convoglio = m.nuovo FROM mappa_rinomina m WHERE f.id_convoglio = m.vecchio;
UPDATE Storico_Itinerari f   SET id_convoglio = m.nuovo FROM mappa_rinomina m WHERE f.id_convoglio = m.vecchio;

-- 6. Elimina le righe con la vecchia chiave, ormai senza figli che le referenziano.
DELETE FROM Treni t WHERE t.id_convoglio IN (SELECT vecchio FROM mappa_rinomina);

COMMIT;

-- Verifica: la tabella deve avere solo id_convoglio come identificativo.
SELECT id_convoglio, stato, itinerario FROM Treni ORDER BY id_convoglio;
