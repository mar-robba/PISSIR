-- Questo script serve per popolare il Database PostgreSQL (Centrale Operativa)
-- con dati fittizi sufficienti a simulare il comportamento della web app.
-- Assicurati di aver prima eseguito (o fatto eseguire ad Hibernate) la creazione delle tabelle tramite schema.sql.
-- Lo script e' idempotente: puo' essere rieseguito senza generare errori (ON CONFLICT DO NOTHING).

-- Inserimento Stazioni (con coordinate GPS reali e numero binari)
INSERT INTO Stazione (id_stazione, nome, tipoCapolineaPartenzaoNormale, latitudine, longitudine, binari) VALUES
('S1', 'Milano Centrale', 'capolinea', 45.4869, 9.2050, 24),
('S2', 'Bologna Centrale', 'normale', 44.5058, 11.3426, 14),
('S3', 'Firenze SMN', 'normale', 43.7764, 11.2481, 19),
('S4', 'Roma Termini', 'capolinea', 41.9010, 12.5011, 32),
('S5', 'Napoli Centrale', 'capolinea', 40.8530, 14.2727, 25)
ON CONFLICT (id_stazione) DO NOTHING;

-- Inserimento Tratte (con tempo di percorrenza nominale in minuti)
INSERT INTO Tratte (id_Tratta, StazionePartenzaFK, StazioneArrivoFK, tempoPercorrenzaMinuti) VALUES
('T1_MI_BO', 'S1', 'S2', 65),
('T2_BO_FI', 'S2', 'S3', 35),
('T3_FI_RM', 'S3', 'S4', 90),
('T4_RM_NA', 'S4', 'S5', 70),
('T5_NA_RM', 'S5', 'S4', 70),
('T6_RM_FI', 'S4', 'S3', 90),
('T7_FI_BO', 'S3', 'S2', 35),
('T8_BO_MI', 'S2', 'S1', 65)
ON CONFLICT (id_Tratta) DO NOTHING;

-- Inserimento Utenti (con password per il login)
INSERT INTO Utenti (id_utente, tipo, nome, cognome, matricola, password) VALUES
('U1', 'admin', 'Mario', 'Rossi', 'MAT001', 'password'),
('U2', 'operatore', 'Luigi', 'Verdi', 'MAT002', 'password'),
('U3', 'tecnico', 'Giovanni', 'Bianchi', 'MAT003', 'password'),
('U4', 'operatore', 'Anna', 'Neri', 'MAT004', 'password')
ON CONFLICT (id_utente) DO NOTHING;

-- Inserimento Itinerari
INSERT INTO Itinerari (id_itinerario) VALUES
('IT1_MI_NA'),
('IT2_NA_MI'),
('IT3_MI_RM')
ON CONFLICT (id_itinerario) DO NOTHING;

-- Inserimento Itinerario_Tratta
INSERT INTO Itinerario_Tratta (id_itinerario, id_Tratta, ordine) VALUES
('IT1_MI_NA', 'T1_MI_BO', 1),
('IT1_MI_NA', 'T2_BO_FI', 2),
('IT1_MI_NA', 'T3_FI_RM', 3),
('IT1_MI_NA', 'T4_RM_NA', 4),

('IT2_NA_MI', 'T5_NA_RM', 1),
('IT2_NA_MI', 'T6_RM_FI', 2),
('IT2_NA_MI', 'T7_FI_BO', 3),
('IT2_NA_MI', 'T8_BO_MI', 4),

('IT3_MI_RM', 'T1_MI_BO', 1),
('IT3_MI_RM', 'T2_BO_FI', 2),
('IT3_MI_RM', 'T3_FI_RM', 3)
ON CONFLICT (id_itinerario, id_Tratta) DO NOTHING;

-- Inserimento Treni
INSERT INTO Treni (id_convoglio, stato, itinerario, PosizioneAttualeTrattaOStazione) VALUES
('TRN001', 'attivo', 'IT1_MI_NA', 'T1_MI_BO'),
('TRN002', 'attivo', 'IT2_NA_MI', 'T5_NA_RM'),
('TRN003', 'fermo', 'IT3_MI_RM', 'T1_MI_BO'),
('TRN004', 'rotto', NULL, 'T3_FI_RM'),
('TRN005', 'in manutenzione', NULL, 'T8_BO_MI')
ON CONFLICT (id_convoglio) DO NOTHING;

-- Inserimento Transiti (storici mock)
INSERT INTO Transiti (id_transito, id_stazione, id_convoglio, id_Tratta, tempoEntrata, tempoUscita) VALUES
('TRN001_S1', 'S1', 'TRN001', 'T1_MI_BO', CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP - INTERVAL '55 minutes'),
('TRN002_S5', 'S5', 'TRN002', 'T5_NA_RM', CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '1 hour 50 minutes')
ON CONFLICT (id_transito) DO NOTHING;

-- Inserimento Guasti (con i nuovi campi descrittivi persistiti)
INSERT INTO Guasti_Pervenuti_da_treni_o_Staz (id_Guasto, Stato_RisoltoONO, OperatoreCheSeNeStaOccupandoFK, tipo, severita, sorgenteTipo, sorgenteId, messaggio, ts_apertura, ts_risoluzione) VALUES
('G1', FALSE, 'U2', 'stazione_guasta', 'CRITICAL', 'STAZIONE', 'S2', 'Guasto impianto di segnalamento', CURRENT_TIMESTAMP - INTERVAL '3 hours', NULL),
('G2', TRUE, 'U3', 'treno_fermo', 'WARNING', 'TRENO', 'TRN004', 'Avaria al sistema frenante', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day'),
('G3', FALSE, 'U4', 'sensore_offline', 'WARNING', 'STAZIONE', 'S3', 'Sensore sens-in-1 non invia keepalive', CURRENT_TIMESTAMP - INTERVAL '30 minutes', NULL)
ON CONFLICT (id_Guasto) DO NOTHING;

-- Inserimento Storici
INSERT INTO Storico_Transiti (id_transito, id_stazione, id_convoglio, id_Tratta, tempoEntrata, tempoUscita) VALUES
('TRN001_S1', 'S1', 'TRN001', 'T1_MI_BO', CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP - INTERVAL '55 minutes')
ON CONFLICT DO NOTHING;

INSERT INTO Storico_Guasti (id_Guasto, Stato_RisoltoONO, OperatoreCheSeNeStaOccupandoFK, ts_apertura, ts_chiusura) VALUES
('G2', TRUE, 'U3', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day')
ON CONFLICT DO NOTHING;

INSERT INTO Storico_Stato_Treni (id_convoglio, stato, itinerario, PosizioneAttualeTrattaOStazione) VALUES
('TRN001', 'fermo', NULL, NULL),
('TRN001', 'attivo', 'IT1_MI_NA', 'T1_MI_BO')
ON CONFLICT DO NOTHING;

INSERT INTO Storico_Stato_Stazioni (id_stazione, nome, tipoCapolineaPartenzaoNormale, funzionanteONo) VALUES
('S1', 'Milano Centrale', 'capolinea', TRUE),
('S2', 'Bologna Centrale', 'normale', TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO Storico_Itinerari (id_itinerario, id_convoglio, ts_assegnazione, ts_completamento) VALUES
('IT1_MI_NA', 'TRN001', CURRENT_TIMESTAMP - INTERVAL '1 hour', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO Storico_Assegnazioni_Guasti (id_Guasto, id_utente, ts_assegnazione, ts_risoluzione) VALUES
('G2', 'U3', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day')
ON CONFLICT DO NOTHING;
