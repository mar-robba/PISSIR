# Treni

Richiede broker e Centrale accesi.

```bash
./mvnw package -DskipTests                                   # compila -> target/treno.jar
java -Dprofilo=tls -Dhttp.port=9400 -jar target/treno.jar Jaz    # un treno
./avvioTreni.sh                                              # tutti quelli in treni.conf (porte da 9080)
./mvnw test                                                  # test
```

Da lanciare dentro questa cartella. L'ID deve esistere nel DB centrale
(`Jaz, LUNGO, Smeraldo, Rosso, Pietra`).
