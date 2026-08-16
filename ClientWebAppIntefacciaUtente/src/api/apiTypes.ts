export interface ApiDashboardPayload {
  totalTrains: number;
  trainsInMotion: number;
  trainsDelayed: number;
  stationsOperative: number;
  stationsFaulty: number;
  activeAlerts: number;
  avgDelay: number;
}

export interface ApiTrainPayload {
  /** Nome del convoglio: è la chiave primaria del treno sulla Centrale. */
  id: string;
  stato?: string;
  posizioneAttualeTratta?: {
    stazioneArrivo?: { id: string };
    stazionePartenza?: { id: string };
  };
  itinerario?: { id: string };
  ritardo?: number;
  passeggeri?: number;
  progresso?: number;
  ultimoAggiornamento?: string;
  stazioneCorrente?: string | null;
  prossimaStazione?: string | null;
  direzione?: string;
}

export interface ApiStationPayload {
  id: string;
  nome?: string;
  stato?: string;
  latitudine?: number;
  longitudine?: number;
  binari?: number;
  ultimoHeartbeat?: string;
}

export interface ApiRoutePayload {
  id: string;
  nome?: string;
  stazioni?: string[];
  travelTimes?: number[];
  attivo?: boolean;
  treniIds?: string[];
}

export interface ApiTrackSegmentPayload {
  id: string;
  stazionePartenzaId: string;
  stazioneArrivoId: string;
  tempoPercorrenzaMinuti?: number;
}

export interface ApiAlertPayload {
  id: string;
  tipo?: string;
  severita?: string;
  messaggio?: string;
  sorgenteId?: string;
  sorgenteTipo?: string;
  timestamp?: string;
  risolto?: boolean;
  timestampRisoluzione?: string;
}

export interface ApiTransitPayload {
  id: string;
  trainId?: string;
  trenoId?: string;
  stationId?: string;
  stazioneId?: string;
  /** Tratta percorsa: la Centrale la ricava dalla posizione del convoglio. */
  trattaId?: string | null;
  tipo?: string;
  type?: string;
  timestamp?: string;
  delayed?: boolean;
  inRitardo?: boolean;
  delayMinutes?: number;
  ritardo?: number;
}

/**
 * Profilo restituito da GET /api/auth/me. Non contiene piu' nessun token: quello
 * lo rilascia Keycloak alla web app, la Centrale si limita a dire chi e' l'utente
 * che ha presentato il token e con quale ruolo.
 */
export interface ApiProfiloResponse {
  id?: string;
  username?: string;
  role?: string;
  displayName?: string;
  avatarInitials?: string;
}

/**
 * Una coppia di stazioni consecutive di un itinerario per cui in rete non esiste
 * nessuna tratta. La Centrale la elenca quando rifiuta una POST/PUT su /api/tratte:
 * l'arco è ORIENTATO, quindi partenza e arrivo non sono intercambiabili.
 */
export interface ApiCoppiaMancante {
  partenzaId: string;
  arrivoId: string;
  partenzaNome: string;
  arrivoNome: string;
}

/**
 * Corpo di una risposta di errore della Centrale. Il messaggio sta in "errore"
 * (gli altri due nomi arrivano dalle eccezioni gestite da Quarkus), mentre
 * coppieMancanti c'è solo sul rifiuto di un itinerario.
 */
export interface ApiErrorPayload {
  errore?: string;
  message?: string;
  detail?: string;
  coppieMancanti?: ApiCoppiaMancante[];
}
