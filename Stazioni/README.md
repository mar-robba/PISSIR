# Stazioni module

Questo modulo rappresenta il nodo Stazione del progetto.

Come avviare in sviluppo (Hot reload):

```bash
cd Stazioni
./mvnw quarkus:dev -Dstazione.id=alessandria -Dquarkus.http.port=8081
```

Come buildare ed eseguire come JAR:

```bash
cd Stazioni
./mvnw clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar TORINO
```

Il server REST sarà disponibile su `http://localhost:8081/stazione`.

Test placeholder:

```bash
cd Stazioni
./mvnw test
```
