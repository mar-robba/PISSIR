| Porta | Protocollo / Tipo | A cosa serve? |
| --- | --- | --- |
| **1883** | **MQTT Standard** (in chiaro) | È la porta di default. La usano la maggior parte dei sensori e dispositivi smart nella tua rete locale per inviare dati senza crittografia pesante. |
| **8883** | **MQTT Sicuro** (TLS/SSL) | Fa la stessa cosa della 1883, ma **criptata**. Si usa se devi far comunicare dispositivi da fuori casa (via Internet) in sicurezza. |
| **9001** | **MQTT via WebSockets** | Serve per far comunicare Mosquitto direttamente con le **interfacce web** o dashboard aperte nel browser (che non possono usare il protocollo MQTT nativo). |


