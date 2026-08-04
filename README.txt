

Runnare il sistema 

1. Avviare i servizi infrastrutturali (Database centrale e Broker MQTT)
1.1  Database Centrale
$ docker-compose up -d

1.2 Broker Mosquitto* 
$ cd BrokerMosquitto
$ docker-compose up -d


2. Avviare il Codice  

2.1 Interfaccia Grafica 
$ cd ClientWebAppIntefacciaUtente
$ npm install
$ npm run dev

2.2 Server centrale 
$ cd ServeCentraleOperativa
$ ./mvnw quarkus:dev

2.3 Stazione
$ cd Stazioni
$ ./mvnw quarkus:dev -Dstazione.id=TORINO -Dquarkus.http.port=8081

2.4 Treno
$ cd Treni
$ ./mvnw quarkus:dev -Dtreno.id=ALFA-100 -Dquarkus.http.port=8082
