# Keycloak

Identity provider del sistema: qui stanno utenti, password e ruoli. La Centrale
non verifica piu' le credenziali, controlla solo la firma dei token.

Parte col `docker-compose up -d` della radice e importa da solo il realm
`railway` da `realm-railway.json`.

- Console: `https://localhost:8543` (admin / admin), solo HTTPS
- Ruoli: `amministratore` (CRUD anagrafica) e `tecnico` (letture + comandi)
- Client: `railway-webapp` (pubblico, Authorization Code + PKCE) e
  `railway-centrale` (bearer-only, le API della Centrale)
- Utenti di prova: `mat001` amministratore, `mat002`/`mat003`/`mat004` tecnici,
  password `password`, **username in minuscolo**

La versione lunga — perche' `start` e non `start-dev`, come rigenerare il realm,
come prendere un token da terminale — sta in `README.txt`.
