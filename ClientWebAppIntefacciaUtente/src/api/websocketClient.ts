// ================ web soket ottimo per applicazioni in tepo reale =========== 

// IMPORTANTE 
//  Con WebSocket: Se la connessione si interrompe anche solo per un istante, 
// il canale si chiude. Lo sviluppatore deve scrivere una quantità notevole di7
//  codice complesso per gestire la riconnessione automatica, sincronizzare i dati
//  persi nel frattempo e verificare l'identità dell'utente.
// per i telefono tali perdite della connessione sono normalissime se uno, per esempio usa un whifi debole






// differenza tra web socket e http 
//Vantaggi: Molto semplice da implementare, facilmente memorizzabile in cache (caching), ideale per caricare pagine web standard o API tradizionali.
//Svantaggio in tempo reale: Se hai bisogno di dati aggiornati (es. i risultati di una partita), il client deve continuare a bussare alla porta del server ogni pochi secondi (Polling), sprecando molta banda ed energia.
/**1. Il Caching (Il vero superpotere di HTTP)

Le risposte HTTP possono essere salvate in memoria (cache) dal browser, da server intermedi o dalle CDN (come Cloudflare).

    Con HTTP REST: Se 10.000 utenti visitano la stessa pagina di un e-commerce, il server non deve lavorare 10.000 volte. La pagina viene salvata in cache e servita istantaneamente.

    Con WebSocket: Non esiste il caching nativo. Ogni singola richiesta deve viaggiare fino al server centrale, che deve elaborarla e rispondere individualmente a ogni utente, sprecando una quantità enorme di potenza di calcolo. */

/**Con WebSocket: Se la connessione si interrompe anche solo per un istante, il canale si chiude. Lo sviluppatore deve scrivere una quantità notevole di codice complesso per gestire la riconnessione automatica, sincronizzare i dati persi nel frattempo e verificare l'identità dell'utente.
 * 
 * 
 * 
 */

/** 
 * In sintesi: HTTP REST è basato su un modello a "domanda e risposta", mentre WebSocket crea un "canale sempre aperto" per uno scambio continuo.
 * 
 * tipizzazione di typescript 
 * Tipo di funzione callback utilizzata per reagire a specifici eventi WebSocket
 * provenienti dal server.
 */
type MessageHandler = (data: any) => void;

/**
 * Classe wrapper per gestire una singola connessione WebSocket robusta,
 * dotata di funzionalità di reconnect automatica e meccanismo Pub/Sub
 * per la sottoscrizione ai topic/eventi in arrivo.
 */
class WebSocketClient {
  private ws: WebSocket | null = null;
  /**
   * Mappa di EventType -> Array di funzioni listener.
   * Consente a diversi componenti (Dashboard, Mappa, Allarmi) di reagire indipendentemente.
   */
  private handlers: Map<string, MessageHandler[]> = new Map();
  private reconnectTimer: any = null;
  private url: string;

  /**
   * Costruisce il client. Non apre immediatamente la connessione.
   * @param url L'endpoint ws:// verso il quale connettersi.
   */
  constructor(url: string) {
    this.url = url;
  }

  /**
   * Apre la connessione e assegna i gestori degli eventi WebSocket nativi.
   */
  connect() {
    if (this.ws?.readyState === WebSocket.OPEN) return;

    this.ws = new WebSocket(this.url);

    this.ws.onopen = () => {
      console.log('🔌 WebSocket connected to Centrale Operativa');
      // Cancella timer di riconnessione se eravamo in retry loop
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }
    };

    /**
     * Intercetta ogni messaggio testuale inviato dal backend (RealtimeWebSocket.java),
     * parsa il JSON e lo instrada in base al campo 'eventType'.
     */
    this.ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data); // deserializza il json
        const eventType = data.eventType || 'UNKNOWN';
        
        // Risveglia tutti i listener registrati per questa stringa (es. 'TELEMETRY')
        const typeHandlers = this.handlers.get(eventType) || [];
        typeHandlers.forEach(handler => handler(data));
      } catch (error) {
        console.error('Error parsing WebSocket message:', error);
      }
    };

    /**
     * Logica di Reconnect automatico con Exponential Backoff semplificato (fisso a 5s).
     */
    this.ws.onclose = () => {
      console.log('🔌 WebSocket disconnected. Reconnecting in 5s...');
      this.reconnectTimer = setTimeout(() => this.connect(), 5000);
    };

    this.ws.onerror = (err) => {
      console.error('WebSocket error:', err);
      this.ws?.close();
    };
  }

  /**
   * Sottoscrive una funzione di callback all'arrivo di determinati `eventType`.
   * @param eventType Es. "TELEMETRY", "HEARTBEAT", "ALERT".
   * @param handler La logica che verrà eseguita.
   * @returns Una funzione di cleanup da chiamare per de-registrare il listener.
   */
  subscribe(eventType: string, handler: MessageHandler) {
    const current = this.handlers.get(eventType) || [];
    this.handlers.set(eventType, [...current, handler]);
    
    // Funzione ritornata per agevolare l'uso con useEffect in React
    return () => {
      const handlers = this.handlers.get(eventType) || [];
      this.handlers.set(eventType, handlers.filter(h => h !== handler));
    };
  }

  /**
   * Disconnette volontariamente l'applicazione dal Realtime Server e spegne il retry-loop.
   */
  disconnect() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}

// Esporta un'istanza singoletto (Singleton pattern) per essere riusata nell'intera Web App.
// Allinea l'URL WebSocket alla porta del backend (8781)
export const wsClient = new WebSocketClient('ws://localhost:8781/ws/realtime');
