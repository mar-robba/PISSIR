import { Train, Station, Route, TrackSegment, Alert, DashboardKPI, Transit, TrainStatus, User, UserRole } from '../types';
import type {
  ApiAlertPayload,
  ApiDashboardPayload,
  ApiLoginResponse,
  ApiRoutePayload,
  ApiTrackSegmentPayload,
  ApiStationPayload,
  ApiTransitPayload,
  ApiTrainPayload
} from './apiTypes';

/**
 * L'URL di base del server backend Java Quarkus (RestApiGateway).
 * In un ambiente di produzione dovrebbe essere letto da file di ambiente (.env).?? 
 */
// Usa la porta del backend (configurata in ServeCentraleOperativa: quarkus.http.port=8781)
const API_BASE_URL = 'http://localhost:8781/api';

/**
 * Estrae il dettaglio di un errore restituito dalla Centrale. Le API usano
 * normalmente `{ "errore": "..." }`, ma sono gestiti anche gli altri formati
 * JSON e le risposte testuali, così l'interfaccia può spiegare il rifiuto.
 */
const createApiError = async (res: Response): Promise<Error> => {
  let dettaglio = '';

  try {
    const contentType = res.headers.get('content-type') ?? '';
    if (contentType.includes('application/json')) {
      const body = await res.json() as Record<string, unknown>;
      const value = body.errore ?? body.message ?? body.detail;
      if (typeof value === 'string') dettaglio = value;
    } else {
      dettaglio = (await res.text()).trim();
    }
  } catch {
    // Una risposta di errore senza body rimane comunque gestibile dal chiamante.
  }

  return new Error(dettaglio || `Il server ha risposto con HTTP ${res.status}.`);
};

/** Converte il ruolo restituito dal backend nel vocabolario del frontend. */
const mapBackendRole = (role: string | undefined): UserRole => {
  const lower = (role ?? '').toLowerCase();
  if (lower === 'admin' || lower === 'amministratore') return 'amministratore';
  return 'tecnico';
};

/**
 * Normalizza la risposta login: supporta il formato nuovo `{ token, user }`
 * e quello legacy con i campi utente in cima al JSON.
 */
const normalizeLoginResponse = (data: Record<string, unknown>): ApiLoginResponse => {
  const rawUser = (data.user ?? data) as Record<string, unknown>;
  const user: User = {
    id: String(rawUser.id ?? ''),
    username: String(rawUser.username ?? ''),
    role: mapBackendRole(String(rawUser.role ?? '')),
    displayName: String(rawUser.displayName ?? rawUser.username ?? ''),
    avatarInitials: String(rawUser.avatarInitials ?? ''),
  };
  return {
    token: String(data.token ?? ''),
    user,
  };
};

/** Converte il DTO tratta del backend nella shape Route usata dal frontend. */
const mapApiRoute = (r: ApiRoutePayload): Route => ({
  id: r.id,
  name: r.nome || r.id,
  code: r.id,
  stationIds: r.stazioni || [],
  trainIds: r.treniIds || [],
  travelTimes: r.travelTimes || [],
  active: r.attivo || false,
  createdAt: Date.now()
});

const mapApiTrackSegment = (t: ApiTrackSegmentPayload): TrackSegment => ({
  id: t.id,
  departureStationId: t.stazionePartenzaId,
  arrivalStationId: t.stazioneArrivoId,
  travelTimeMinutes: t.tempoPercorrenzaMinuti ?? 15,
});

export const mapBackendStatus = (s: string): TrainStatus => {
  const lower = (s || '').toLowerCase();
  if (lower === 'attivo') return 'in_viaggio';
  if (lower === 'fermo') return 'in_stazione';
  if (lower === 'rotto') return 'guasto';
  if (lower === 'in manutenzione') return 'soppresso';
  return (lower || 'in_attesa') as TrainStatus;
};

/**
 * Mappa inversa: converte lo stato del frontend nei valori canonici del DB centrale
 * (minuscoli: il CHECK sulla colonna Treni.stato rifiuta le varianti maiuscole).
 */
export const mapFrontendStatus = (s: TrainStatus): string => {
  switch (s) {
    case 'in_viaggio': return 'attivo';
    case 'in_stazione': return 'fermo';
    case 'guasto': return 'rotto';
    case 'soppresso': return 'in manutenzione';
    default: return 'fermo';
  }
};

/**
 * Converte lo stato runtime della stazione (vocabolario del backend: ONLINE/GUASTA/
 * MANUTENZIONE/OFFLINE) nello StationStatus del frontend.
 */
export const mapBackendStationStatus = (s: string): 'operativa' | 'guasta' | 'manutenzione' | 'offline' => {
  switch ((s || '').toUpperCase()) {
    case 'ONLINE': return 'operativa';
    case 'GUASTA': return 'guasta';
    case 'MANUTENZIONE': return 'manutenzione';
    case 'OFFLINE': return 'offline';
    default: return 'operativa';
  }
};

/** Mappa inversa: dallo StationStatus del frontend al vocabolario runtime del backend. */
const mapFrontendStationStatus = (s: string): string => {
  switch (s) {
    case 'operativa': return 'ONLINE';
    case 'guasta': return 'GUASTA';
    case 'manutenzione': return 'MANUTENZIONE';
    case 'offline': return 'OFFLINE';
    default: return 'ONLINE';
  }
};

/**
 * apiClient raccoglie tutte le chiamate REST (HTTP) asincrone verso il backend.
 * Funge da Data Access Layer per i componenti frontend.
 */
export const apiClient = {
  /**
   * Recupera i Key Performance Indicators per popolare i widget della Dashboard.
   * @returns Un oggetto contente statistiche (treni in movimento, stazioni offline, etc.)
   */
  getDashboard: async (): Promise<DashboardKPI> => {
    const res = await fetch(`${API_BASE_URL}/dashboard`);
    if (!res.ok) throw new Error('Failed to fetch dashboard KPI');
    const data = await res.json() as ApiDashboardPayload;
    return data;
  },

  /**
   * Esegue il fetch della lista di tutti i treni e formatta/mappa
   * il payload JSON del server nelle interfacce interne di TypeScript.
   * @returns Promise<Train[]> Un array di oggetti Train front-end ready.
   */
  getTrains: async (): Promise<Train[]> => {
    const res = await fetch(`${API_BASE_URL}/treni`);
    if (!res.ok) throw new Error('Failed to fetch trains');
    const data = await res.json() as ApiTrainPayload[];
    return data.map((t) => ({
      id: t.id,
      convoglio: t.nome || t.id,
      status: mapBackendStatus(t.stato ?? ''),
      currentStationId: t.stazioneCorrente || null,
      nextStationId: t.prossimaStazione || t.posizioneAttualeTratta?.stazioneArrivo?.id || null,
      // Al primo avvio la tratta persistita può non essere ancora disponibile;
      // la stazione corrente della telemetria è comunque l'estremo di partenza.
      previousStationId: t.posizioneAttualeTratta?.stazionePartenza?.id || t.stazioneCorrente || null,
      routeId: t.itinerario?.id || '',
      direction: t.direzione === 'ritorno' ? 'ritorno' : 'andata',
      arrivalTime: null,
      departureTime: null,
      delayMinutes: t.ritardo || 0,
      passengers: t.passeggeri || 0,
      progressPercent: t.progresso || 0,
      lastUpdate: t.ultimoAggiornamento ? new Date(t.ultimoAggiornamento).getTime() : Date.now()
    }));
  },

  /**
   * Richiede e mappa l'elenco delle stazioni e il relativo stato operativo.
   */
  getStations: async (): Promise<Station[]> => {
    const res = await fetch(`${API_BASE_URL}/stazioni`);
    if (!res.ok) throw new Error('Failed to fetch stations');
    const data = await res.json() as ApiStationPayload[];
    return data.map((s) => ({
      id: s.id,
      code: s.id,
      name: s.nome || s.id,
      city: s.nome || '',
      status: mapBackendStationStatus(s.stato || ''),
      coordinates: { x: s.latitudine || 0, y: s.longitudine || 0 },
      platforms: s.binari || 1,
      lastHeartbeat: s.ultimoHeartbeat ? new Date(s.ultimoHeartbeat).getTime() : Date.now(),
      operatorsDispatched: s.stato === 'MANUTENZIONE'
    }));
  },

  /**
   * Ottiene le configurazioni delle tratte/itinerari.
   */
  getRoutes: async (): Promise<Route[]> => {
    const res = await fetch(`${API_BASE_URL}/tratte`);
    if (!res.ok) throw new Error('Failed to fetch routes');
    const data = await res.json() as ApiRoutePayload[];
    return data.map(mapApiRoute);
  },

  getTrackSegments: async (): Promise<TrackSegment[]> => {
    const res = await fetch(`${API_BASE_URL}/tratte-elementari`);
    if (!res.ok) throw new Error('Failed to fetch track segments');
    return (await res.json() as ApiTrackSegmentPayload[]).map(mapApiTrackSegment);
  },

  createTrackSegment: async (segment: TrackSegment): Promise<TrackSegment> => {
    const res = await fetch(`${API_BASE_URL}/tratte-elementari`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: segment.id, stazionePartenzaId: segment.departureStationId, stazioneArrivoId: segment.arrivalStationId, tempoPercorrenzaMinuti: segment.travelTimeMinutes })
    });
    if (!res.ok) throw await createApiError(res);
    return mapApiTrackSegment(await res.json() as ApiTrackSegmentPayload);
  },

  updateTrackSegment: async (id: string, segment: Partial<TrackSegment>): Promise<TrackSegment> => {
    const res = await fetch(`${API_BASE_URL}/tratte-elementari/${id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ stazionePartenzaId: segment.departureStationId, stazioneArrivoId: segment.arrivalStationId, tempoPercorrenzaMinuti: segment.travelTimeMinutes })
    });
    if (!res.ok) throw await createApiError(res);
    return mapApiTrackSegment(await res.json() as ApiTrackSegmentPayload);
  },

  deleteTrackSegment: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/tratte-elementari/${id}`, { method: 'DELETE' });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Crea una nuova tratta inviando un payload JSON al backend.
   */
  createRoute: async (route: Route): Promise<Route> => {
    const res = await fetch(`${API_BASE_URL}/tratte`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: route.id,
        nome: route.name,
        stazioni: route.stationIds,
        travelTimes: route.travelTimes,
        attivo: route.active,
        treniIds: route.trainIds
      })
    });
    if (!res.ok) throw await createApiError(res);
    // Il backend risponde col proprio DTO: lo si riporta nella shape Route
    // così id/nome generati lato server finiscono nello stato locale
    return mapApiRoute(await res.json() as ApiRoutePayload);
  },

  /**
   * Aggiorna parzialmente o totalmente una tratta esistente.
   */
  updateRoute: async (id: string, route: Partial<Route>): Promise<Route> => {
    const res = await fetch(`${API_BASE_URL}/tratte/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nome: route.name,
        stazioni: route.stationIds,
        travelTimes: route.travelTimes,
        attivo: route.active,
        treniIds: route.trainIds
      })
    });
    if (!res.ok) throw await createApiError(res);
    return mapApiRoute(await res.json() as ApiRoutePayload);
  },

  /**
   * Elimina una tratta.
   * nota : quindi per una post basta aggiungere il parametro dei dati da inviare e usare sempre fetch:
   */
  deleteRoute: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/tratte/${id}`, { method: 'DELETE' });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Preleva gli allarmi pendenti o passati (storico guasti).
   */
  getAlerts: async (): Promise<Alert[]> => {
    const res = await fetch(`${API_BASE_URL}/allarmi`);
    if (!res.ok) throw new Error('Failed to fetch alarms');
    const data = await res.json() as ApiAlertPayload[];
    return data.map((a) => ({
      id: a.id,
      type: (a.tipo || 'info').toLowerCase() as any,
      severity: (a.severita || 'info').toLowerCase() as any,
      message: a.messaggio || '',
      // sorgenteTipo distingue la natura della sorgente: senza, un guasto treno
      // finirebbe associato anche a una stazione (e viceversa)
      stationId: a.sorgenteTipo === 'STAZIONE' ? a.sorgenteId : undefined,
      trainId: a.sorgenteTipo === 'TRENO' ? a.sorgenteId : undefined,
      timestamp: a.timestamp ? new Date(a.timestamp).getTime() : Date.now(),
      acknowledged: a.risolto || false,
      resolvedAt: a.timestampRisoluzione ? new Date(a.timestampRisoluzione).getTime() : undefined
    }));
  },

  /**
   * Invia un comando al backend per segnalare un allarme come risolto (Acknowledged).
   */
  acknowledgeAlert: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/allarmi/${id}/risolvi`, { method: 'POST' });
    if (!res.ok) throw new Error('Failed to resolve alert');
  },

  /**
   * Comando perentorio per l'emergenza: sopprime/blocca immediatamente la marcia di un treno.
   */
  suppressTrain: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/treni/${id}/sopprimi`, { method: 'POST' });
    if (!res.ok) throw new Error('Failed to suppress train');
  },

  /**
   * Crea un nuovo treno (operazione riservata all'amministratore).
   */
  createTrain: async (train: Train): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/treni`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: train.id,
        nome: train.convoglio,
        stato: mapFrontendStatus(train.status),
        itinerario: train.routeId ? { id: train.routeId } : null,
        passeggeri: train.passengers,
        ritardo: train.delayMinutes
      })
    });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Aggiorna i dati anagrafici/operativi di un treno esistente (amministratore).
   */
  updateTrain: async (id: string, train: Partial<Train>): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/treni/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nome: train.convoglio,
        stato: train.status !== undefined ? mapFrontendStatus(train.status) : undefined,
        itinerario: train.routeId ? { id: train.routeId } : undefined,
        passeggeri: train.passengers,
        ritardo: train.delayMinutes
      })
    });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Elimina definitivamente un treno dalla flotta (amministratore).
   */
  deleteTrain: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/treni/${id}`, { method: 'DELETE' });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Registra una nuova stazione nella rete (amministratore).
   */
  createStation: async (station: Station): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/stazioni`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: station.id,
        nome: station.name,
        stato: mapFrontendStationStatus(station.status || 'operativa'),
        latitudine: station.coordinates.x,
        longitudine: station.coordinates.y,
        binari: station.platforms
      })
    });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Aggiorna i dati di una stazione esistente (amministratore).
   */
  updateStation: async (id: string, station: Partial<Station>): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/stazioni/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nome: station.name,
        stato: station.status !== undefined ? mapFrontendStationStatus(station.status) : undefined,
        latitudine: station.coordinates?.x,
        longitudine: station.coordinates?.y,
        binari: station.platforms
      })
    });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Elimina una stazione dalla rete (amministratore).
   */
  deleteStation: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/stazioni/${id}`, { method: 'DELETE' });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Effettua il login verificando le credenziali.
   */
  login: async (username: string, password: string): Promise<ApiLoginResponse> => {
    const res = await fetch(`${API_BASE_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    if (!res.ok) throw new Error('Invalid credentials');
    const data = await res.json() as Record<string, unknown>;
    return normalizeLoginResponse(data);
  },

  /**
   * Invia gli operatori in manutenzione per una stazione.
   */
  dispatchOperators: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/stazioni/${id}/manutenzione`, { method: 'POST' });
    if (!res.ok) throw new Error('Failed to dispatch operators');
  },

  /**
   * Recupera i transiti.
   */
  getTransits: async (): Promise<Transit[]> => {
    const res = await fetch(`${API_BASE_URL}/transiti`);
    if (!res.ok) throw new Error('Failed to fetch transits');
    const data = await res.json() as ApiTransitPayload[];
    return data.map((t) => ({
      id: t.id,
      trainId: t.trainId || t.trenoId || '',
      stationId: t.stationId || t.stazioneId || '',
      type: (t.tipo || t.type || 'ingresso') as 'ingresso' | 'uscita',
      timestamp: t.timestamp ? new Date(t.timestamp).getTime() : Date.now(),
      delayed: t.delayed ?? t.inRitardo ?? false,
      delayMinutes: t.delayMinutes ?? t.ritardo ?? 0
    }));
  }
};
