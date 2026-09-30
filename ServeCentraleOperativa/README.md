# Centrale Operativa

Richiede i container della radice accesi (`docker-compose up -d`).

```bash
./mvnw package -DskipTests                       # compila -> target/centrale.jar
java -Dprofilo=tls -jar target/centrale.jar      # REST 8781, HTTPS 8444, MQTT su TLS
java -jar target/centrale.jar                    # variante senza TLS (MQTT 1883)
./mvnw test                                      # test
```

Da lanciare dentro questa cartella (i percorsi dei certificati sono relativi).
