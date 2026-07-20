#!/usr/bin/env bash
# ===================================================================
# Generazione certificati TLS per il progetto (variante 6 del prof)
# ===================================================================
# Crea in ./certs/ :
#   - ca.crt / ca.key                 : CA self-signed del progetto
#   - server.crt / server.key         : certificato del broker Mosquitto
#                                       (CN=localhost, SAN DNS:localhost + IP:127.0.0.1)
#   - server-centrale.crt / .key      : certificato per l'HTTPS della Centrale Operativa
#
# NOTA: le chiavi private NON vanno committate in un progetto reale;
# essendo un progetto didattico con certificati self-signed va bene
# generarle in locale con questo script.
#
# Uso:  ./gen-certs.sh
set -euo pipefail

# Lavoriamo sempre nella cartella dello script, ovunque venga lanciato
cd "$(dirname "$0")"
mkdir -p certs
cd certs

GIORNI=825

echo "[1/3] Genero la CA self-signed del progetto..."
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -sha256 -days ${GIORNI} \
    -subj "/C=IT/O=Reti2 Ferrovia/CN=Reti2 Railway CA" \
    -out ca.crt

echo "[2/3] Genero il certificato del broker Mosquitto (CN=localhost)..."
openssl genrsa -out server.key 2048
openssl req -new -key server.key \
    -subj "/C=IT/O=Reti2 Ferrovia/CN=localhost" \
    -out server.csr
# Estensioni: SAN necessario perche' i client moderni ignorano il solo CN
cat > server.ext <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days ${GIORNI} -sha256 -extfile server.ext -out server.crt

echo "[3/3] Genero il certificato HTTPS della Centrale Operativa..."
openssl genrsa -out server-centrale.key 2048
openssl req -new -key server-centrale.key \
    -subj "/C=IT/O=Reti2 Ferrovia/CN=localhost" \
    -out server-centrale.csr
openssl x509 -req -in server-centrale.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days ${GIORNI} -sha256 -extfile server.ext -out server-centrale.crt

# Pulizia dei file intermedi (richieste di firma ed estensioni)
rm -f server.csr server-centrale.csr server.ext

# Il container di Mosquitto gira con utente non-root: la chiave deve
# essere leggibile (644 va bene per un progetto didattico)
chmod 644 ca.crt server.crt server.key server-centrale.crt server-centrale.key
chmod 600 ca.key

echo
echo "Verifica dei certificati emessi contro la CA:"
openssl verify -CAfile ca.crt server.crt server-centrale.crt

echo
echo "Fatto! Certificati disponibili in $(pwd)"
