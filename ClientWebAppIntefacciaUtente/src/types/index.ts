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
  lastHeartbeat: number; // timestamp ms (Unix Epoch)
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
  id: string;
  convoglio: string; // numero identificativo o matricola es. "IC 351"
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
  type: 'ingresso' | 'uscita';
  timestamp: number;
  delayed: boolean;
  delayMinutes: number;
}

// --- ALERT ---
export type AlertSeverity = 'info' | 'warning' | 'critical';
export type AlertType =
  | 'treno_fermo'
  | 'stazione_guasta'
  | 'sensore_offline'
  | 'ritardo'
  | 'treno_soppresso'
  | 'operatori_inviati'
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
