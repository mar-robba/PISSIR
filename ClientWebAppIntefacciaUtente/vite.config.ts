import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'node:fs'

// ============================================================
// Dev server in HTTPS (RF04.3)
//
// Da questa pagina parte il redirect verso il login di Keycloak e qui tornano
// il codice di autorizzazione e i token: servirla in chiaro vanificherebbe la
// cifratura di tutto il resto della catena. Il certificato e' quello firmato
// dalla CA privata del progetto (BrokerMosquitto/tls/gen-certs.sh), la stessa
// del broker e della Centrale; la prima volta il browser va fatto fidare di
// ca.crt, altrimenti mostra la pagina di avviso "connessione non sicura".
//
// Se i certificati non sono ancora stati generati si riparte in HTTP invece di
// morire all'avvio, ma in quel caso il login non funziona: il client
// railway-webapp accetta solo redirect verso https://localhost:5173.
// ============================================================
const CARTELLA_CERTIFICATI = '../BrokerMosquitto/tls/certs'
const certificato = `${CARTELLA_CERTIFICATI}/server-webapp.crt`
const chiave = `${CARTELLA_CERTIFICATI}/server-webapp.key`

const certificatiPresenti = fs.existsSync(certificato) && fs.existsSync(chiave)
if (!certificatiPresenti) {
  console.warn(
    '[vite] certificati non trovati in ' + CARTELLA_CERTIFICATI +
    ': avvio in HTTP e login non funzionante. Lanciare BrokerMosquitto/tls/gen-certs.sh',
  )
}

export default defineConfig({
  plugins: [react()],
  server: {
    https: certificatiPresenti
      ? { cert: fs.readFileSync(certificato), key: fs.readFileSync(chiave) }
      : undefined,
  },
})
