-- ================================================================
-- SCHEMA DEL DATABASE LOCALE - TRENO
-- ================================================================

CREATE TABLE Stato_convoglio (
    -- Chiave primaria
    id_convoglio INT PRIMARY KEY,
    
    -- Attributi
    direzione VARCHAR(50) NOT NULL,
    stazione_corrente VARCHAR(100),
    
    -- Gestione dello stato con vincolo di controllo
    stato VARCHAR(30) NOT NULL CHECK (stato IN ('attivo', 'fermo', 'rotto', 'in manutenzione')),
    
    prossima_stazione VARCHAR(100),
    tratta_corrente VARCHAR(100)
);
