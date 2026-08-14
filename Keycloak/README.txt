Keycloak - Identity Provider della Centrale Operativa
=====================================================

Da qui passa tutta l'autenticazione degli utenti web (tecnico e amministratore).
Prima gli utenti stavano nella tabella Utenti con la password in chiaro e la
Centrale si generava un token UUID: adesso le credenziali le verifica Keycloak e
la Centrale si limita a validare il JWT firmato che il browser le presenta.

1. Avvio
--------
Keycloak parte insieme al database, dal docker-compose nella radice del progetto:

$ docker-compose up -d

Console di amministrazione: http://localhost:8180  (admin / admin)
Realm importato automaticamente all'avvio: "railway"

La porta e' la 8180 e non la 8080 di default apposta: 8080 e' la porta che
Quarkus userebbe se si lanciasse Stazioni o Treni dimenticando
-Dquarkus.http.port, e i due processi finirebbero per litigare.

2. Cosa contiene il realm (Keycloak/realm-railway.json)
-------------------------------------------------------
Ruoli di realm:
  amministratore -> CRUD di stazioni, treni, tratte, itinerari + tutte le letture
  tecnico        -> letture + comandi operativi (invio operatori, soppressione
                    corsa, presa in carico allarme)

Client:
  railway-webapp   client pubblico usato dalla SPA React. Flusso Authorization
                   Code + PKCE (S256 obbligatorio lato server), redirect URI
                   http://localhost:5173/*. Il grant "password" e' disabilitato
                   apposta: la password non deve mai passare dalla web app.
  railway-centrale client bearer-only: e' l'identita' delle API REST Quarkus,
                   non fa login, verifica soltanto i token in arrivo.

Utenti di prova (password "password" per tutti, come nel vecchio import.sql):
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
Il file viene importato SOLO se il realm non esiste ancora. Per ripartire da zero
dopo aver modificato il JSON:

$ docker-compose rm -sf keycloak && docker volume rm <nome_volume_keycloak>
$ docker-compose up -d keycloak

oppure, piu' semplicemente, si modifica a mano dalla console di amministrazione.

4. Prova rapida da terminale
----------------------------
Il flusso Authorization Code non si fa comodamente con curl (serve il browser per
la pagina di login). Per una prova veloce delle API si puo' abilitare
temporaneamente "Direct access grants" sul client railway-webapp dalla console e
poi:

$ TOKEN=$(curl -s -X POST \
    http://localhost:8180/realms/railway/protocol/openid-connect/token \
    -d grant_type=password -d client_id=railway-webapp \
    -d username=mat001 -d password=password | jq -r .access_token)
$ curl -s http://localhost:8781/api/treni -H "Authorization: Bearer $TOKEN"

Ricordarsi di rimetterlo a "off" dopo la prova: in produzione quel grant e'
deprecato ed e' esattamente cio' da cui la migrazione a OAuth2 doveva scappare.
