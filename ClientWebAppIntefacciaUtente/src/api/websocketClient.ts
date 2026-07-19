/** Boh non so che serva e che dati gestisce questa parte 
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
        const data = JSON.parse(event.data);
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
