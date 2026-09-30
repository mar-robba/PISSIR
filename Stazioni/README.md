# Stazioni

Richiede broker e Centrale accesi.

```bash
./mvnw package -DskipTests                                   # compila -> target/stazione.jar
java -Dprofilo=tls -Dhttp.port=8400 -jar target/stazione.jar MI   # una stazione
./avvioStazioni.sh                                           # tutte quelle in stazioni.conf (porte da 8080)
./mvnw test                                                  # test
```

Da lanciare dentro questa cartella. L'ID deve esistere nel DB centrale
(`MI, TO, FI, BO, PA, BE, CA, PAL`).
