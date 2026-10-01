Keycloak - Identity Provider della Centrale Operativa
=====================================================

Da qui passa tutta l'autenticazione degli utenti web (tecnico e amministratore).
Le credenziali le verifica Keycloak; la Centrale si limita a validare il JWT
firmato che il browser le presenta.

1. Avvio
--------
Keycloak parte insieme al database, dal docker-compose nella radice del progetto:

$ docker-compose up -d

Console di amministrazione: https://localhost:8543  (admin / admin)
Realm importato automaticamente all'avvio: "railway"

L'indirizzo e' in https e non in http: qui dentro passano le password degli
utenti, e RF04.3 chiede che i canali siano cifrati. Il listener in chiaro e'
spento del tutto (KC_HTTP_ENABLED=false nel docker-compose). Per lo stesso motivo
il container gira in "start" e non in "start-dev": in modalita' di sviluppo
Keycloak tiene aperto l'HTTP comunque, qualunque cosa si scriva nella
configurazione.

Il certificato e' quello firmato dalla CA privata del progetto
(BrokerMosquitto/tls/gen-certs.sh, file server-keycloak.crt/.key): la prima volta
il browser va fatto fidare di ca.crt, altrimenti mostra l'avviso di connessione
non sicura.

Sull'host la porta e' la 8543 (nel container e' la 8443 di default).

2. Cosa contiene il realm (Keycloak/realm-railway.json)
-------------------------------------------------------
Ruoli di realm:
  amministratore -> CRUD di stazioni, treni, tratte, itinerari + tutte le letture
  tecnico        -> letture + comandi operativi (invio operatori, soppressione
                    corsa, presa in carico allarme)

Client:
  railway-webapp   client pubblico usato dalla SPA React. Flusso Authorization
                   Code + PKCE (S256 obbligatorio lato server), redirect URI
                   https://localhost:5173/*. Il grant "password" e' disabilitato
                   apposta: la password non deve mai passare dalla web app.
  railway-centrale client bearer-only: e' l'identita' delle API REST della Centrale,
                   non fa login, verifica soltanto i token in arrivo.

Utenti di prova (password "password" per tutti):
  mat001 -> amministratore (Mario Rossi)
  mat002 -> tecnico        (Luigi Verdi)
  mat003 -> tecnico        (Giovanni Bianchi)
  mat004 -> tecnico        (Anna Neri)

Attenzione: Keycloak salva gli username sempre in minuscolo, quindi si entra con
"mat001" e non "MAT001". La matricola vera (MAT001) viaggia comunque nel token
come claim "matricola", generata dal protocol mapper omonimo: e' quella che la
Centrale usa per ritrovare la riga della tabella Utenti (serve per le chiavi
esterne dei guasti, dove l'operatore assegnato e' un Utente del database).

3. Rigenerare/aggiornare il realm
---------------------------------
Il file viene importato SOLO se il realm non esiste ancora. Il database di
Keycloak e' un H2 su file (KC_DB=dev-file) che vive dentro il container e non ha
un volume, quindi per ripartire da zero dopo aver modificato il JSON basta
buttare via il container:

$ docker-compose rm -sf keycloak
$ docker-compose up -d keycloak

oppure, piu' semplicemente, si modifica a mano dalla console di amministrazione.

4. Prova rapida da terminale
----------------------------
Il flusso Authorization Code non si fa comodamente con curl (serve il browser per
la pagina di login). Per una prova veloce delle API si puo' abilitare
temporaneamente "Direct access grants" sul client railway-webapp dalla console e
poi:

$ CA=../BrokerMosquitto/tls/certs/ca.crt
$ TOKEN=$(curl -s --cacert $CA -X POST \
    https://localhost:8543/realms/railway/protocol/openid-connect/token \
    -d grant_type=password -d client_id=railway-webapp \
    -d username=mat001 -d password=password | jq -r .access_token)
$ curl -s --cacert $CA https://localhost:8444/api/treni -H "Authorization: Bearer $TOKEN"

Il --cacert serve perche' la CA del progetto non sta fra quelle di sistema.

Ricordarsi di rimetterlo a "off" dopo la prova: quel grant fa passare la password
dal client, che e' proprio quello che il flusso Authorization Code evita.
