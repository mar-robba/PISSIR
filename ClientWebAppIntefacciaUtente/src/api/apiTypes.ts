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
  /** Condizione dichiarata dal convoglio (automa T6), null prima del primo frame. */
  condizione?: string | null;
  /** Fase del viaggio dichiarata dal convoglio (automa T5), null prima del primo frame. */
  faseViaggio?: string | null;
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
  /** Istante ISO in cui la Centrale prevede l'arrivo nella prossima stazione. */
  arrivoPrevisto?: string | null;
  /** Gli stessi minuti mancanti, nell'orario di servizio (come il ritardo). */
  minutiAllArrivo?: number | null;

  // La posizione che la Centrale GARANTISCE, distinta da stazioneCorrente/
  // prossimaStazione/direzione qui sopra, che sono quello che il convoglio dichiara.
  // La Centrale incrocia le due cose con l'ultimo passaggio rilevato dai sensori di
  // terra: quando non tornano scrive null, ed è il punto di domanda da mostrare.
  /** Stazione da cui il convoglio è uscito, o in cui sosta. null = non nota. */
  partenzaCorrente?: string | null;
  /** Stazione verso cui sta andando. null = non nota. */
  arrivoCorrente?: string | null;
  /** Verso di marcia garantito. null = non noto. */
  direzioneCorrente?: string | null;
  /** true quando il convoglio si può disegnare sulla mappa. */
  posizioneNota?: boolean;
  /** Perché la posizione non è nota (NESSUNA_TELEMETRIA, PARTENZA_SCONOSCIUTA, ...). */
  incertezzaPosizione?: string | null;
}

/** Riga di GET /api/stazioni/{id}/treni-attesi. */
export interface ApiExpectedTrainPayload {
  trenoId: string;
  tipo?: string;
  stato?: string;
  direzione?: string;
  itinerarioId?: string;
  stazioneCorrente?: string | null;
  prossimaStazione?: string | null;
  ritardoMinuti?: number;
  minutiAllArrivo?: number;
  fermateIntermedie?: number;
  arrivoPrevisto?: string | null;
  /** true quando la Centrale garantisce la posizione del convoglio. */
  posizioneNota?: boolean;
  /** Perché non la garantisce, se non la garantisce. */
  incertezzaPosizione?: string | null;
}

export interface ApiStationPayload {
  id: string;
  nome?: string;
  stato?: string;
  latitudine?: number;
  longitudine?: number;
  binari?: number;
  ultimoHeartbeat?: string;
  /** Stato che il nodo ha dichiarato nell'ultimo battito (automa S10), null se non ha battuto. */
  statoDichiarato?: string | null;
}

export interface ApiRoutePayload {
  id: string;
  nome?: string;
  stazioni?: string[];
  travelTimes?: number[];
  attivo?: boolean;
  treniIds?: string[];
  /** Id della stazione che chiude il servizio, null se la tratta non finisce mai. */
  capolinea?: string | null;
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
  origine?: 'SEGNALATO_CAMPO' | 'DEDOTTO_CENTRALE';
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
 * ("message" e "detail" sono solo un ripiego per risposte che non vengono dalla
 * Centrale), mentre
 * coppieMancanti c'è solo sul rifiuto di un itinerario.
 */
export interface ApiErrorPayload {
  errore?: string;
  message?: string;
  detail?: string;
  coppieMancanti?: ApiCoppiaMancante[];
}
