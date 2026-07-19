# Processo Treno
## compilazione 
mvn clean package
## Esecuzione:
java -jar target/quarkus-app/quarkus-run.jar ALFA-10

## o con il wrapper maven
./mvnw clean package -DskipTests
./mvnw quarkus:dev -Dtreno.id=ALFA-100

# Sever Rest per la ricezione dei messaggi dai sensori
** si attiva con l'avvio dell'applicazione precedente** 

Sto verificando quale server REST viene usato nel modulo Treni e come si avvia nel progetto.

Server REST del modulo Treni
Il modulo Treni usa un server HTTP Quarkus con RESTEasy/Jackson.

Framework: Quarkus
Endpoint base: @Path("/treno")
Porta configurata: quarkus.http.port=8082
Classe REST: it.uni.reti2.TrainIngestion
Cosa espone
Alcuni endpoint disponibili:

GET /treno/info
GET /treno/stato
GET /treno/tratta
POST /treno/tratta
POST /treno/sensore/guasto
POST /treno/sensore/passaggio
Come avviarlo
Con Maven in sviluppo
Dal modulo Treni:



