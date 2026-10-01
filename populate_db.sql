-- Dati demo del database PostgreSQL della Centrale Operativa.
--
-- E' l'anagrafica della rete (stazioni, tratte, operatori, itinerari e treni) che
-- chi scarica il progetto trova gia' pronta: la stessa del database su cui e' stato
-- sviluppato il sistema, con i nomi sistemati. Gli ID sono quelli che compaiono in
-- Stazioni/stazioni.conf e Treni/treni.conf, quindi dopo questo script
-- ./avvioStazioni.sh e ./avvioTreni.sh partono senza che la validazione via MQTT
-- rifiuti nessun nodo.
--
-- Non si lancia a mano con psql ma con lo script nella radice del progetto:
--     $ ./popola_db.sh
-- che prima crea le tabelle eseguendo lo stesso schema.sql della Centrale
-- (ServeCentraleOperativa/src/main/resources/schema.sql), poi esegue questo file
-- tutto in una transazione: o entrano tutte le righe o non ne entra nessuna.
-- Serve un database VUOTO: se trova gia' delle stazioni lo script si ferma, perche'
-- mescolare questi itinerari con quelli modificati dalla web app li romperebbe
-- (due tratte diverse con lo stesso numero d'ordine nello stesso itinerario).
--
-- NOTA: transiti, guasti, eventi_stazioni e tabelle Storico_* non vengono popolati
-- qui. Sono dati che il sistema produce da solo mentre gira (i treni transitano, i
-- sensori segnalano guasti), quindi metterli a mano servirebbe solo a sporcare lo
-- storico. Lo stato delle stazioni (ONLINE, GUASTA...) non e' nemmeno una colonna:
-- la Centrale lo ricostruisce dagli heartbeat.

-- Stazioni (coordinate GPS per la mappa e numero di binari)
INSERT INTO Stazione (id_stazione, nome, tipoCapolineaPartenzaoNormale, latitudine, longitudine, binari) VALUES
('AL-67', 'Alessandria',        'normale', 44.8967,  8.6167,   1),
('AS',    'Asti',               'normale',    44.9,  8.2069,   1),
('BE',    'Berlino',            'normale',   52.52,  13.405,   1),
('BO',    'Bologna',            'normale', 44.4949, 11.3426,   1),
('CA',    'Catania',            'normale', 37.5079,  15.083,   1),
('FI',    'Firenze',            'normale', 43.7696, 11.2558,  44),
('MI',    'Milano',             'normale', 45.4642,    9.19,  14),
('PA',    'Padova',             'normale', 45.4064, 11.8768,   1),
('PAL',   'Palermo',            'normale', 38.1157, 13.3615,   1),
('TO',    'Torino Porta Nuova', 'normale', 45.0703,  7.6869,   5);

-- Tratte (tempo di percorrenza nominale in minuti). Il nome e' PARTENZA_ARRIVO
-- (AL sta per AL-67).
INSERT INTO Tratte (id_Tratta, StazionePartenzaFK, StazioneArrivoFK, tempoPercorrenzaMinuti) VALUES
('AL_BO',  'AL-67', 'BO',     110),
('AL_MI',  'AL-67', 'MI',      60),
('AS_AL',  'AS',    'AL-67',   25),
('BE_BO',  'BE',    'BO',     200),
('BE_TO',  'BE',    'TO',     180),
('BO_FI',  'BO',    'FI',      15),
('BO_PA',  'BO',    'PA',      30),
('CA_FI',  'CA',    'FI',     120),
('FI_BO',  'FI',    'BO',      10),
('FI_TO',  'FI',    'TO',      40),
('MI_AL',  'MI',    'AL-67',   60),
('MI_FI',  'MI',    'FI',      30),
('PAL_CA', 'PAL',   'CA',      10),
('PA_BO',  'PA',    'BO',      15),
('PA_TO',  'PA',    'TO',      30),
('TO_AS',  'TO',    'AS',      35),
('TO_BE',  'TO',    'BE',     180),
('TO_MI',  'TO',    'MI',      15),
('TO_PA',  'TO',    'PA',      15),
('TO_PAL', 'TO',    'PAL',     15);

-- Utenti: solo anagrafica degli operatori, NIENTE password.
-- Le credenziali e i ruoli stanno in Keycloak (realm "railway", vedi
-- Keycloak/realm-railway.json); qui restano nome, cognome e matricola perche' i
-- guasti puntano all'operatore che se ne occupa. La matricola e' cio' che lega la
-- riga all'utente Keycloak corrispondente (mat001 -> MAT001). Il ruolo con cui si
-- entra nella web app NON e' la colonna tipo ma il ruolo di Keycloak.
INSERT INTO Utenti (id_utente, tipo, nome, cognome, matricola) VALUES
('U1', 'admin',     'Mario',    'Rossi',   'MAT001'),
('U2', 'operatore', 'Luigi',    'Verdi',   'MAT002'),
('U3', 'tecnico',   'Giovanni', 'Bianchi', 'MAT003'),
('U4', 'operatore', 'Anna',     'Neri',    'MAT004');

-- Itinerari (gli ID sono quelli generati dalla web app quando li si crea).
-- capolinea NULL: il convoglio fa andata e ritorno all'infinito, nessuno chiude
-- il servizio.
INSERT INTO Itinerari (id_itinerario, capolinea) VALUES
('IT-0fa6ff5d', NULL),
('IT-20946e95', NULL),
('IT-3911bae4', NULL),
('IT-4b192614', NULL),
('IT-5512122e', NULL),
('IT-5eaf60b5', NULL),
('IT-6bd15591', NULL),
('IT-7c66f1e3', NULL),
('IT-86680d3e', NULL),
('IT-8df8659a', NULL),
('IT-91670335', NULL),
('IT-98b08317', NULL),
('IT-cc137c5f', NULL),
('IT-cc2cd4aa', NULL),
('IT-fed88863', NULL);

-- Composizione degli itinerari: quali tratte e in che ordine. Sopra ogni blocco
-- le fermate in fila, cosi' si vede che ogni tratta parte da dove arriva la
-- precedente.
INSERT INTO Itinerario_Tratta (id_itinerario, id_Tratta, ordine) VALUES
-- CA - FI - TO - MI - AL-67 - BO - PA
('IT-0fa6ff5d', 'CA_FI',  1),
('IT-0fa6ff5d', 'FI_TO',  2),
('IT-0fa6ff5d', 'TO_MI',  3),
('IT-0fa6ff5d', 'MI_AL',  4),
('IT-0fa6ff5d', 'AL_BO',  5),
('IT-0fa6ff5d', 'BO_PA',  6),

-- AS - AL-67 - BO - FI - TO - PAL - CA
('IT-20946e95', 'AS_AL',  1),
('IT-20946e95', 'AL_BO',  2),
('IT-20946e95', 'BO_FI',  3),
('IT-20946e95', 'FI_TO',  4),
('IT-20946e95', 'TO_PAL', 5),
('IT-20946e95', 'PAL_CA', 6),

-- PAL - CA - FI
('IT-3911bae4', 'PAL_CA', 1),
('IT-3911bae4', 'CA_FI',  2),

-- MI - FI - BO - PA - TO - BE
('IT-4b192614', 'MI_FI',  1),
('IT-4b192614', 'FI_BO',  2),
('IT-4b192614', 'BO_PA',  3),
('IT-4b192614', 'PA_TO',  4),
('IT-4b192614', 'TO_BE',  5),

-- BE - TO - AS - AL-67 - BO - PA
('IT-5512122e', 'BE_TO',  1),
('IT-5512122e', 'TO_AS',  2),
('IT-5512122e', 'AS_AL',  3),
('IT-5512122e', 'AL_BO',  4),
('IT-5512122e', 'BO_PA',  5),

-- BE - TO - MI - FI
('IT-5eaf60b5', 'BE_TO',  1),
('IT-5eaf60b5', 'TO_MI',  2),
('IT-5eaf60b5', 'MI_FI',  3),

-- MI - FI - TO - AS - AL-67 - BO - PA
('IT-6bd15591', 'MI_FI',  1),
('IT-6bd15591', 'FI_TO',  2),
('IT-6bd15591', 'TO_AS',  3),
('IT-6bd15591', 'AS_AL',  4),
('IT-6bd15591', 'AL_BO',  5),
('IT-6bd15591', 'BO_PA',  6),

-- BO - PA - TO - AS - AL-67 - MI - FI
('IT-7c66f1e3', 'BO_PA',  1),
('IT-7c66f1e3', 'PA_TO',  2),
('IT-7c66f1e3', 'TO_AS',  3),
('IT-7c66f1e3', 'AS_AL',  4),
('IT-7c66f1e3', 'AL_MI',  5),
('IT-7c66f1e3', 'MI_FI',  6),

-- TO - AS - AL-67 - MI - FI
('IT-86680d3e', 'TO_AS',  1),
('IT-86680d3e', 'AS_AL',  2),
('IT-86680d3e', 'AL_MI',  3),
('IT-86680d3e', 'MI_FI',  4),

-- TO - MI - FI
('IT-8df8659a', 'TO_MI',  1),
('IT-8df8659a', 'MI_FI',  2),

-- PA - TO - MI - AL-67 - BO - FI
('IT-91670335', 'PA_TO',  1),
('IT-91670335', 'TO_MI',  2),
('IT-91670335', 'MI_AL',  3),
('IT-91670335', 'AL_BO',  4),
('IT-91670335', 'BO_FI',  5),

-- PAL - CA - FI - TO - AS - AL-67 - MI
('IT-98b08317', 'PAL_CA', 1),
('IT-98b08317', 'CA_FI',  2),
('IT-98b08317', 'FI_TO',  3),
('IT-98b08317', 'TO_AS',  4),
('IT-98b08317', 'AS_AL',  5),
('IT-98b08317', 'AL_MI',  6),

-- TO - AS - AL-67 - BO - FI
('IT-cc137c5f', 'TO_AS',  1),
('IT-cc137c5f', 'AS_AL',  2),
('IT-cc137c5f', 'AL_BO',  3),
('IT-cc137c5f', 'BO_FI',  4),

-- PA - BO - FI - TO - BE
('IT-cc2cd4aa', 'PA_BO',  1),
('IT-cc2cd4aa', 'BO_FI',  2),
('IT-cc2cd4aa', 'FI_TO',  3),
('IT-cc2cd4aa', 'TO_BE',  4),

-- AS - AL-67 - MI - FI - BO - PA
('IT-fed88863', 'AS_AL',  1),
('IT-fed88863', 'AL_MI',  2),
('IT-fed88863', 'MI_FI',  3),
('IT-fed88863', 'FI_BO',  4),
('IT-fed88863', 'BO_PA',  5);

-- Treni: id_convoglio E' il nome del convoglio (il campo "Convoglio" del form di
-- amministrazione), non c'e' una colonna "nome" separata. Sono gli ID da passare
-- come argomento al jar del treno (vedi Treni/treni.conf).
-- Partono tutti 'attivo' sulla prima tratta del loro itinerario, che e' da dove il
-- digital twin comincia il viaggio quando si accende: la posizione vera la Centrale
-- la riscrive con la prima telemetria.
INSERT INTO Treni (id_convoglio, stato, itinerario, PosizioneAttualeTrattaOStazione) VALUES
('Arneis',     'attivo', 'IT-5512122e', 'BE_TO'),
('Barbera',    'attivo', 'IT-cc137c5f', 'TO_AS'),
('Brachetto',  'attivo', 'IT-fed88863', 'AS_AL'),
('Cortese',    'attivo', 'IT-7c66f1e3', 'BO_PA'),
('Dolcetto',   'attivo', 'IT-91670335', 'PA_TO'),
('Freisa',     'attivo', 'IT-0fa6ff5d', 'CA_FI'),
('Grignolino', 'attivo', 'IT-6bd15591', 'MI_FI'),
('Jaz',        'attivo', 'IT-3911bae4', 'PAL_CA'),
('LUNGO',      'attivo', 'IT-4b192614', 'MI_FI'),
('Moscato',    'attivo', 'IT-86680d3e', 'TO_AS'),
('Nebbiolo',   'attivo', 'IT-98b08317', 'PAL_CA'),
('Pietra',     'attivo', 'IT-cc2cd4aa', 'PA_BO'),
('Rosso',      'attivo', 'IT-8df8659a', 'TO_MI'),
('Ruche',      'attivo', 'IT-20946e95', 'AS_AL'),
('Smeraldo',   'attivo', 'IT-5eaf60b5', 'BE_TO');
