// ============================================================
// TYPES — Sistema di Monitoraggio Traffico Ferroviario
// 
// File centrale contenente tutte le interfacce e i tipi TypeScript 
// strutturati in modo coerente col backend Java Panache.
// Garantisce la Type Safety nell'intera Web App React.
// ============================================================

/** Identifica i privilegi dell'utente loggato. */
export type UserRole = 'tecnico' | 'amministratore';

/** Oggetto di sessione per l'autenticazione JWT-based. */
export interface User {
  id: string;
  username: string;
  role: UserRole;
  displayName: string;
  avatarInitials: string;
}

// --- STAZIONI ---
export type StationStatus = 'operativa' | 'guasta' | 'manutenzione' | 'offline';

export interface Station {
  id: string;
  code: string; // es. "TO", "AL", "AT"
  name: string;
  city: string;
  status: StationStatus;
  coordinates: { x: number; y: number }; // posizione proporzionale per rendering su mappa SVG o Canvas
  /**
   * Timestamp ms (Unix Epoch) dell'ultimo heartbeat, oppure null se la stazione non ha
   * MAI battuto. La Centrale tiene apposta il campo vuoto in quel caso: metterci l'ora
   * corrente faceva vedere "ultimo heartbeat: adesso" per una stazione spenta.
   */
  lastHeartbeat: number | null;
  platforms: number;
  faultDescription?: string;
  faultSince?: number;
  operatorsDispatched?: boolean;
}

// --- TRENI ---
export type TrainStatus =
  | 'in_viaggio'
  | 'in_stazione'
  | 'in_ritardo'
  | 'guasto'
  | 'soppresso'
  | 'in_attesa';

export interface Train {
  /**
   * Nome del convoglio (es. "IC 351"): è l'identificativo del treno in tutto il
   * sistema (chiave primaria Treni.id_convoglio sulla Centrale, topic MQTT del
   * digital twin). Non esiste un campo "convoglio" separato: era un doppione.
   */
  id: string;
  status: TrainStatus;
  currentStationId: string | null;
  nextStationId: string | null;
  previousStationId: string | null;
  routeId: string;
  direction: 'andata' | 'ritorno';
  arrivalTime: number | null; // stima timestamp d'arrivo prossima stazione
  departureTime: number | null;
  delayMinutes: number; // ritardo positivo o negativo (anticipo)
  passengers: number;
  lastUpdate: number; // ultimo dato telemetrico GPS
  progressPercent: number; // 0-100, posizione interpolata tra stazione precedente e successiva per visualizzatori lineari
}

// --- TRATTE ---
export interface Route {
  id: string;
  name: string;
  code: string; // codice univoco es. "AL-TO-001"
  stationIds: string[]; // sequenza ordinata di nodi stazioni attraversate (andata)
  trainIds: string[]; // pool di treni assegnati alla linea
  travelTimes: number[]; // storico o delta previsti: minuti necessari tra stazione[i] e stazione[i+1]
  active: boolean; // se la linea ferroviaria è attualmente aperta al traffico
  createdAt: number;
}

/** Collegamento fisico diretto fra due stazioni; può appartenere a più itinerari. */
export interface TrackSegment {
  id: string;
  departureStationId: string;
  arrivalStationId: string;
  travelTimeMinutes: number;
}

// --- TRANSIT LOG ---
/** Definisce storicamente quando un convoglio ha varcato il perimetro di una specifica stazione. */
export interface Transit {
  id: string;
  trainId: string;
  stationId: string;
  /** Tratta su cui il convoglio si trovava: RF01.5 la elenca fra i dati del transito. */
  trackSegmentId: string | null;
  type: 'ingresso' | 'uscita';
  timestamp: number;
  delayed: boolean;
  delayMinutes: number;
}

// --- ALERT ---
export type AlertSeverity = 'info' | 'warning' | 'critical';
/**
 * Tipi di guasto che esistono davvero sulla Centrale. Non ci sono più
 * "treno_soppresso" e "operatori_inviati": erano notifiche costruite dal browser e
 * mescolate agli allarmi veri, vedi UiNotification.
 */
export type AlertType =
  | 'treno_fermo'
  | 'stazione_guasta'
  | 'sensore_offline'
  | 'ritardo'
  | 'heartbeat_mancante';

export interface Alert {
  id: string;
  type: AlertType;
  severity: AlertSeverity; // info (blu), warning (giallo), critical (rosso) nella UI
  message: string;
  stationId?: string; // associato opzionalmente ad una stazione
  trainId?: string;   // associato opzionalmente ad un treno
  timestamp: number;
  acknowledged: boolean; // false se da gestire, true se risolto/soppresso in dashboard
  resolvedAt?: number;
}

/**
 * Notifica d'interfaccia: l'esito di un comando dato dall'operatore (soppressione di un
 * convoglio, invio della squadra di manutenzione), che vive solo in questo browser.
 *
 * Prima queste righe venivano infilate nell'elenco degli allarmi con id inventati
 * (`al-supp-...`, `al-ops-...`) e tipi che sulla Centrale non esistono: sparivano al primo
 * F5, nessun altro operatore le vedeva, gonfiavano il contatore degli allarmi attivi e
 * premendo "Presa Visione" partiva una POST che rispondeva 404. Tenerle separate dice la
 * verità: sono conferme di comando, non guasti della rete.
 */
export interface UiNotification {
  id: string;
  kind: 'treno_soppresso' | 'operatori_inviati' | 'errore_comando';
  severity: AlertSeverity;
  message: string;
  timestamp: number;
}

// --- OPERATORI ---
/** Monitoraggio delle squadre di intervento field edge per ripristino guasti. */
export interface OperatorDispatch {
  id: string;
  stationId: string;
  dispatchedAt: number;
  estimatedArrival: number;
  operatorCount: number;
  status: 'in_viaggio' | 'arrivato' | 'completato';
}

// --- KPI ---
/** Modello dati per riempire in un colpo solo il riepilogo "a colpo d'occhio". */
export interface DashboardKPI {
  totalTrains: number;
  trainsInMotion: number;
  trainsDelayed: number;
  stationsOperative: number;
  stationsFaulty: number;
  activeAlerts: number;
  avgDelay: number; // media matematica di tutti i ritardi
}

// --- EXPECTED TRAINS ---
export interface ExpectedTrain {
  trainId: string;
  convoglio: string;
  type: 'ingresso' | 'uscita';
  expectedTime: number;
  delayMinutes: number;
  status: TrainStatus;
  fromStationId?: string;
  toStationId?: string;
}
