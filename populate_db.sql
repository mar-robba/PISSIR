-- Popolamento del database PostgreSQL della Centrale Operativa.
--
-- Questo file contiene l'anagrafica del sistema (stazioni, tratte, itinerari,
-- treni e operatori) cosi' com'e' nel database centrale: e' stato rigenerato
-- leggendo il contenuto del container railway-postgres, quindi gli ID qui dentro
-- sono gli stessi che si vedono nella web app.
--
-- Le tabelle vengono create da Hibernate al primo avvio della Centrale
-- (quarkus.hibernate-orm.database.generation=update), quindi lo script va
-- eseguito DOPO che la Centrale e' partita almeno una volta.
-- Per lanciarlo c'e' lo script popola_db.sh nella radice del progetto:
--     $ ./popola_db.sh
--
-- Lo script e' idempotente: si puo' rieseguire senza errori (ON CONFLICT DO NOTHING).
--
-- NOTA: transiti, guasti e tabelle Storico_* non vengono popolati qui. Sono dati
-- che il sistema produce da solo mentre gira (i treni transitano, i sensori
-- segnalano guasti), quindi metterli a mano servirebbe solo a sporcare lo storico.

-- Stazioni (con coordinate GPS e numero di binari)
INSERT INTO Stazione (id_stazione, nome, tipoCapolineaPartenzaoNormale, latitudine, longitudine, binari) VALUES
('MI',  'Milano',             'normale', 45.4642, 9.1900,  14),
('TO',  'torino Porta Nuova', 'normale', 45.0703, 7.6869,   5),
('FI',  'Firenze',            'normale', 43.7696, 11.2558, 44),
('BO',  'bologna',            'normale', 44.4949, 11.3426,  1),
('PA',  'Padova',             'normale', 45.4064, 11.8768,  1),
('BE',  'Berlino',            'normale', 52.5200, 13.4050,  1),
('CA',  'Catagna',            'normale', 37.5079, 15.0830,  1),
('PAL', 'Palermo',            'normale', 38.1157, 13.3615,  1)
ON CONFLICT (id_stazione) DO NOTHING;

-- Tratte (tempo di percorrenza nominale in minuti).
-- Attenzione: i nomi delle tratte sono quelli inseriti dalla web app, per cui in
-- un paio di casi (FI_BO, PAL_MI) il nome non coincide con le stazioni collegate.
INSERT INTO Tratte (id_Tratta, StazionePartenzaFK, StazioneArrivoFK, tempoPercorrenzaMinuti) VALUES
('MI_FI',   'MI',  'FI',  30),
('FI_BO',   'BO',  'FI',  15),
('FI_BO_2', 'FI',  'BO',  10),
('FI_TO',   'FI',  'TO',  40),
('TO_MI',   'TO',  'MI',  15),
('TO-PA',   'TO',  'PA',  15),
('TO_BE',   'TO',  'BE', 180),
('BE_TO',   'BE',  'TO', 180),
('BE_PA',   'BE',  'PA', 200),
('PA_TO',   'PA',  'TO',  30),
('PA_BO',   'PA',  'BO',  15),
('BO_PA',   'BO',  'PA',  30),
('CA_FI',   'CA',  'FI', 120),
('PAL_CA',  'PAL', 'CA',  10),
('PAL_MI',  'PAL', 'TO', 145)
ON CONFLICT (id_Tratta) DO NOTHING;

-- Utenti: solo anagrafica degli operatori, NIENTE password.
-- Le credenziali e i ruoli stanno in Keycloak (realm "railway", vedi
-- Keycloak/realm-railway.json); qui restano nome, cognome e matricola perche' i
-- guasti puntano all'operatore che se ne occupa. La matricola e' cio' che lega la
-- riga all'utente Keycloak corrispondente (mat001 -> MAT001).
INSERT INTO Utenti (id_utente, tipo, nome, cognome, matricola) VALUES
('U1', 'admin',     'Mario',    'Rossi',   'MAT001'),
('U2', 'operatore', 'Luigi',    'Verdi',   'MAT002'),
('U3', 'tecnico',   'Giovanni', 'Bianchi', 'MAT003'),
('U4', 'operatore', 'Anna',     'Neri',    'MAT004')
ON CONFLICT (id_utente) DO NOTHING;

-- Itinerari (gli ID sono quelli generati dalla web app quando li si crea)
INSERT INTO Itinerari (id_itinerario) VALUES
('IT-3911bae4'),
('IT-4b192614'),
('IT-5eaf60b5'),
('IT-8df8659a'),
('IT-cc2cd4aa')
ON CONFLICT (id_itinerario) DO NOTHING;

-- Composizione degli itinerari: quali tratte e in che ordine
INSERT INTO Itinerario_Tratta (id_itinerario, id_Tratta, ordine) VALUES
('IT-3911bae4', 'PAL_CA',  1),
('IT-3911bae4', 'CA_FI',   2),

('IT-4b192614', 'MI_FI',   1),
('IT-4b192614', 'FI_BO_2', 2),
('IT-4b192614', 'BO_PA',   3),
('IT-4b192614', 'PA_TO',   4),
('IT-4b192614', 'TO_BE',   5),

('IT-5eaf60b5', 'BE_TO',   1),
('IT-5eaf60b5', 'TO_MI',   2),
('IT-5eaf60b5', 'MI_FI',   3),

('IT-8df8659a', 'TO_MI',   1),
('IT-8df8659a', 'MI_FI',   2),

('IT-cc2cd4aa', 'PA_BO',   1),
('IT-cc2cd4aa', 'FI_BO',   2),
('IT-cc2cd4aa', 'FI_TO',   3)
ON CONFLICT (id_itinerario, id_Tratta) DO NOTHING;

-- Treni: id_convoglio E' il nome del convoglio (il campo "Convoglio" del form di
-- amministrazione), non c'e' una colonna "nome" separata. Questi sono gli ID da
-- passare a -Dtreno.id quando si avvia un processo Treno.
INSERT INTO Treni (id_convoglio, stato, itinerario, PosizioneAttualeTrattaOStazione) VALUES
('Jaz',      'attivo', 'IT-3911bae4', 'CA_FI'),
('LUNGO',    'attivo', 'IT-4b192614', 'PA_TO'),
('Smeraldo', 'attivo', 'IT-5eaf60b5', 'TO_MI'),
('Rosso',    'attivo', 'IT-8df8659a', 'MI_FI'),
('Pietra',   'fermo',  'IT-cc2cd4aa', 'FI_TO')
ON CONFLICT (id_convoglio) DO NOTHING;
