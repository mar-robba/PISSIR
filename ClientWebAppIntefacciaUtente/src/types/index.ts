// ============================================================
// TYPES — Sistema di Monitoraggio Traffico Ferroviario
// ============================================================

export type UserRole = 'tecnico' | 'amministratore';

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
  coordinates: { x: number; y: number }; // posizione nella mappa SVG
  lastHeartbeat: number; // timestamp ms
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
  convoglio: string; // numero convoglio es. "IC 351"
  status: TrainStatus;
  currentStationId: string | null;
  nextStationId: string | null;
  previousStationId: string | null;
  routeId: string;
  direction: 'andata' | 'ritorno';
  arrivalTime: number | null; // timestamp previsto arrivo prossima stazione
  departureTime: number | null;
  delayMinutes: number;
  passengers: number;
  lastUpdate: number;
  progressPercent: number; // 0-100, posizione tra stazione precedente e successiva
}

// --- TRATTE ---
export interface Route {
  id: string;
  name: string;
  code: string; // es. "AL-TO-001"
  stationIds: string[]; // ordinate andata
  trainIds: string[];
  travelTimes: number[]; // minuti tra stazione[i] e stazione[i+1]
  active: boolean;
  createdAt: number;
}

// --- TRANSIT LOG ---
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
  severity: AlertSeverity;
  message: string;
  stationId?: string;
  trainId?: string;
  timestamp: number;
  acknowledged: boolean;
  resolvedAt?: number;
}

// --- OPERATORI ---
export interface OperatorDispatch {
  id: string;
  stationId: string;
  dispatchedAt: number;
  estimatedArrival: number;
  operatorCount: number;
  status: 'in_viaggio' | 'arrivato' | 'completato';
}

// --- KPI ---
export interface DashboardKPI {
  totalTrains: number;
  trainsInMotion: number;
  trainsDelayed: number;
  stationsOperative: number;
  stationsFaulty: number;
  activeAlerts: number;
  avgDelay: number; // minuti
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
