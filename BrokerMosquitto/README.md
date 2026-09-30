# Broker MQTT (Mosquitto)

```bash
cd tls && ./gen-certs.sh      # una volta, prima del docker-compose
cd .. && cd .. && docker-compose up -d mosquitto
```

Porte: 1883 in chiaro, 8883 TLS (`-Dprofilo=tls`), 9001 WebSocket (web app).
Dettagli su topic e payload in `doc/alberoDeiTopic.md`.
