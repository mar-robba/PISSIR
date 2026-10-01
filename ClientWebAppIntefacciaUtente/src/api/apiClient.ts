import { Train, Station, Route, TrackSegment, Alert, DashboardKPI, Transit, TrainStatus, User, UserRole, ExpectedTrain, StatoAutoma } from '../types';
import type {
  ApiAlertPayload,
  ApiCoppiaMancante,
  ApiDashboardPayload,
  ApiErrorPayload,
  ApiProfiloResponse,
  ApiRoutePayload,
  ApiTrackSegmentPayload,
  ApiStationPayload,
  ApiTransitPayload,
  ApiTrainPayload,
  ApiExpectedTrainPayload
} from './apiTypes';

/**
 * L'URL di base delle API della Centrale (RestApiGateway).
 * Si legge dalla variabile d'ambiente di Vite VITE_API_BASE_URL (file .env), così
 * puntare a un'altra porta non richiede di ricompilare nulla.
 * Default: HTTPS sulla 8444, cioè la Centrale avviata con -Dprofilo=tls.
 * Non si usa la 8781 in chiaro perché la pagina è servita in https: il browser
 * bloccherebbe come "mixed content" ogni chiamata verso http://.
 */
const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'https://localhost:8444/api';

/**
 * Access token OAuth2 rilasciato da Keycloak: la Centrale lo pretende nell'header
 * Authorization su tutte le API (lo verifica SicurezzaKeycloak e ne legge i ruoli
 * FiltroAutorizzazione), altrimenti risponde 401. E' un JWT firmato dall'Identity
 * Provider; lo imposta l'authStore
 * dopo lo scambio del codice di autorizzazione e a ogni rinnovo.
 */
let authToken: string | null = null;

/** Registra (o cancella) il token usato dalle chiamate successive. */
export const setAuthToken = (token: string | null): void => {
  authToken = token;
};

/**
 * Header comuni a tutte le chiamate protette: il token di sessione e, quando la
 * richiesta ha un corpo JSON, il Content-Type.
 */
const authHeaders = (conBody = false): Record<string, string> => {
  const headers: Record<string, string> = {};
  if (conBody) headers['Content-Type'] = 'application/json';
  if (authToken) headers['Authorization'] = `Bearer ${authToken}`;
  return headers;
};

/**
 * Errore di una chiamata REST rifiutata dalla Centrale. Oltre al messaggio porta,
 * quando c'è, l'elenco delle coppie di stazioni senza tratta in rete: serve al form
 * degli itinerari per dire all'operatore quali collegamenti deve ancora registrare.
 */
export class ErroreApi extends Error {
  readonly coppieMancanti: ApiCoppiaMancante[];

  constructor(messaggio: string, coppieMancanti: ApiCoppiaMancante[] = []) {
    super(messaggio);
    this.name = 'ErroreApi';
    this.coppieMancanti = coppieMancanti;
  }
}

/**
 * Estrae il dettaglio di un errore restituito dalla Centrale. Le API usano
 * normalmente `{ "errore": "..." }`, ma sono gestiti anche gli altri formati
 * JSON e le risposte testuali, così l'interfaccia può spiegare il rifiuto.
 */
const createApiError = async (res: Response): Promise<ErroreApi> => {
  let dettaglio = '';
  let coppieMancanti: ApiCoppiaMancante[] = [];

  try {
    const contentType = res.headers.get('content-type') ?? '';
    if (contentType.includes('application/json')) {
      const body = await res.json() as ApiErrorPayload;
      const value = body.errore ?? body.message ?? body.detail;
      if (typeof value === 'string') dettaglio = value;
      if (Array.isArray(body.coppieMancanti)) coppieMancanti = body.coppieMancanti;
    } else {
      dettaglio = (await res.text()).trim();
    }
  } catch {
    // Una risposta di errore senza body rimane comunque gestibile dal chiamante.
  }

  return new ErroreApi(dettaglio || `Il server ha risposto con HTTP ${res.status}.`, coppieMancanti);
};

/** Converte il ruolo restituito dal backend nel vocabolario del frontend. */
const mapBackendRole = (role: string | undefined): UserRole => {
  const lower = (role ?? '').toLowerCase();
  if (lower === 'admin' || lower === 'amministratore') return 'amministratore';
  return 'tecnico';
};

/** Converte il profilo restituito da /api/auth/me nella shape User del frontend. */
const mapProfilo = (dati: ApiProfiloResponse): User => ({
  id: String(dati.id ?? ''),
  username: String(dati.username ?? ''),
  role: mapBackendRole(dati.role),
  displayName: String(dati.displayName ?? dati.username ?? ''),
  avatarInitials: String(dati.avatarInitials ?? ''),
});

/**
 * Traduce per l'operatore il motivo per cui la Centrale non garantisce la posizione di
 * un convoglio (le costanti le definisce PosizioneConvoglio, lato Centrale).
 *
 * Sta qui, insieme agli altri traduttori del vocabolario del backend, perché la usano
 * due schermate diverse — la mappa nell'elenco dei convogli da ritrovare e la pagina
 * Treni nel pannello di dettaglio — e devono dire la stessa cosa. Il codice grezzo non
 * si mostra mai: all'operatore serve capire di chi è il silenzio, perché è quello che gli
 * dice se deve preoccuparsi del convoglio o riaccendere una stazione.
 */
export const descriviIncertezzaPosizione = (motivo: string | null): string => {
  switch (motivo) {
    case 'NESSUNA_TELEMETRIA':
      return 'il convoglio non trasmette';
    case 'PARTENZA_SCONOSCIUTA':
      return 'nessun sensore conferma da dove è uscito';
    case 'ARRIVO_SCONOSCIUTO':
      return 'non dichiara verso quale fermata sta andando';
    case 'DIREZIONE_SCONOSCIUTA':
      return 'verso di marcia non noto';
    case 'STAZIONE_NON_DISEGNABILE':
      return 'la sua fermata non è sulla mappa';
    default:
      return 'posizione non confermata';
  }
};

/**
 * Le fasi del viaggio in cui il convoglio sta percorrendo l'itinerario in un verso: è lì
 * che è acceso il suo automa T7 (andata o ritorno). Senza itinerario non c'è nessun verso,
 * e al capolinea la corsa è chiusa: T7 ne esce proprio arrivando lì.
 */
const FASI_CON_UN_VERSO = ['IN_STAZIONE', 'IN_VIAGGIO', 'BLOCCATO_IN_STAZIONE', 'BLOCCATO_IN_TRATTA'];

/** Colore della condizione del convoglio (T6). */
const tonoCondizione = (condizione: string): StatoAutoma['tono'] => {
  if (condizione === 'REGOLARE') return 'success';
  if (condizione === 'EMERGENZA' || condizione === 'SOPPRESSO') return 'danger';
  return 'neutral';
};

/** Colore della fase del viaggio (T5): in giallo i due blocchi, in blu la marcia. */
const tonoFase = (fase: string): StatoAutoma['tono'] => {
  if (fase === 'IN_VIAGGIO') return 'info';
  if (fase === 'BLOCCATO_IN_STAZIONE' || fase === 'BLOCCATO_IN_TRATTA') return 'warning';
  return 'neutral';
};

/**
 * Lo stato composto di un convoglio: un valore per ognuno dei suoi automi, nell'ordine in
 * cui li descrive la documentazione del treno.
 *
 * - T6 condizione: come sta (REGOLARE, EMERGENZA, SOPPRESSO);
 * - T5 fase del viaggio: dove si trova e se può muoversi (IN_STAZIONE, BLOCCATO_IN_TRATTA...);
 * - T7 verso di marcia (ANDATA, RITORNO), solo nelle fasi in cui il treno ne ha uno.
 *
 * Sono tre valori e non uno perché il treno sta in tutti e tre insieme: un convoglio in
 * EMERGENZA resta nella fase in cui era, uno trattenuto da una stazione guasta è REGOLARE e
 * BLOCCATO_IN_TRATTA. Il riassunto della Centrale da solo direbbe "fermo" sia per il treno
 * in sosta sia per quello fermo a metà tratta.
 *
 * Se il convoglio non ha ancora trasmesso dopo l'avvio della Centrale (o non è mai stato
 * acceso) dei suoi automi non si sa niente: resta lo stato che gli dà la Centrale (M13),
 * scritto con le sue parole.
 *
 * Sta qui, con gli altri traduttori del vocabolario del backend, perché la usano tutte le
 * schermate che mostrano un treno, e devono dire la stessa cosa.
 */
export const statiDelTreno = (
  train: Pick<Train, 'status' | 'condition' | 'journeyPhase' | 'declaredDirection'>
): StatoAutoma[] => {
  if (!train.condition || !train.journeyPhase) {
    const statoCentrale = mapFrontendStatus(train.status);
    return [{
      automa: 'M13 stato del treno (Centrale): il convoglio non ha ancora trasmesso',
      valore: statoCentrale.toUpperCase(),
      tono: statoCentrale === 'attivo' ? 'info'
        : (statoCentrale === 'rotto' || statoCentrale === 'in manutenzione') ? 'danger' : 'neutral',
    }];
  }
  const stati: StatoAutoma[] = [
    { automa: 'T6 condizione del convoglio', valore: train.condition, tono: tonoCondizione(train.condition) },
    { automa: 'T5 fase del viaggio', valore: train.journeyPhase, tono: tonoFase(train.journeyPhase) },
  ];
  if (train.declaredDirection && FASI_CON_UN_VERSO.includes(train.journeyPhase)) {
    stati.push({
      automa: 'T7 direzione di marcia',
      valore: train.declaredDirection.toUpperCase(),
      tono: 'neutral',
    });
  }
  return stati;
};

/** Lo stato della Centrale (M12) con il nome che gli dà l'automa. */
const STATO_STAZIONE_CENTRALE: Record<Station['status'], string> = {
  operativa: 'ONLINE',
  guasta: 'GUASTA',
  manutenzione: 'MANUTENZIONE',
  offline: 'OFFLINE',
};

/**
 * Lo stato composto di una stazione: quello che le dà la Centrale (M12) e, quando è un
 * altro, quello che il nodo dichiara di sé nel battito (S10).
 *
 * Il secondo si mostra solo in MANUTENZIONE. In tutti gli altri stati la Centrale prende
 * per buono a ogni battito quello che il nodo dichiara (AutomaStazione.accettaDalCampo), e
 * i due automi sono nello stesso stato; in MANUTENZIONE invece la Centrale non lo accetta,
 * e la stazione è in due stati insieme: la squadra è sul posto e il nodo si dichiara ancora
 * GUASTA, oppure già ONLINE. Da OFFLINE il nodo non batte, e un suo stato vecchio non è lo
 * stato di adesso.
 */
export const statiDellaStazione = (station: Pick<Station, 'status' | 'declaredStatus'>): StatoAutoma[] => {
  const stati: StatoAutoma[] = [{
    automa: 'M12 stato della stazione (Centrale)',
    valore: STATO_STAZIONE_CENTRALE[station.status] ?? station.status.toUpperCase(),
    tono: station.status === 'operativa' ? 'success' : (station.status === 'manutenzione' ? 'warning' : 'danger'),
  }];
  if (station.status === 'manutenzione' && station.declaredStatus) {
    const dichiarato = station.declaredStatus.toUpperCase();
    stati.push({
      automa: 'S10 stato dichiarato dal nodo',
      valore: dichiarato,
      tono: dichiarato === 'ONLINE' ? 'success' : 'danger',
    });
  }
  return stati;
};

/**
 * Converte il DTO treno della Centrale nella shape Train del frontend.
 * Esportata perché la usano in due: la GET /api/treni e lo SNAPSHOT che arriva ogni dieci
 * secondi dalla WebSocket, che porta gli stessi identici oggetti. Un mappatore solo per
 * tutte e due le strade, così la pagina appena aperta e quella aggiornata dal vivo non
 * possono discordare.
 */
export const mapApiTrain = (t: ApiTrainPayload): Train => {
  // Gli estremi della tratta NON si ricavano da posizioneAttualeTratta.
  //
  // Quel campo lo scrive solo il passaggio rilevato dai sensori di terra, che arriva
  // alla Centrale passando dal nodo Stazione: se una stazione è spenta i suoi passaggi
  // non li registra nessuno e la tratta resta ferma sull'ultima confermata, mentre il
  // convoglio è andato avanti. Leggerla vorrebbe dire comporre una partenza vecchia con
  // un arrivo attuale e ottenere tratte che in rete non esistono (un convoglio in
  // viaggio da Milano a Berlino).
  //
  // Il giudizio lo dà la Centrale, che le due fonti le incrocia lei
  // (PosizioneConvoglio): qui si prende quello che garantisce, e quando garantisce null
  // il null si tiene. È il punto di domanda che le schermate mostrano e il motivo per
  // cui il convoglio finisce nell'elenco di quelli a posizione incerta.
  const direzione = t.direzioneCorrente ?? null;
  // Il verso dichiarato dal convoglio, che è lo stato del suo automa T7: è un'altra cosa
  // rispetto a direzioneCorrente, che è quanto la Centrale si sente di garantire.
  const versoDichiarato = t.direzione ?? null;

  return {
    // L'id È il nome del convoglio (chiave primaria Treni.id_convoglio): la Centrale
    // non espone più un campo "nome" separato.
    id: t.id,
    status: mapBackendStatus(t.stato ?? ''),
    currentStationId: t.stazioneCorrente || null,
    nextStationId: t.arrivoCorrente ?? null,
    previousStationId: t.partenzaCorrente ?? null,
    routeId: t.itinerario?.id || '',
    direction: direzione === 'ritorno' ? 'ritorno' : (direzione ? 'andata' : null),
    // Lo stato dei tre automi del convoglio, come lui lo dichiara. "stato" qui sopra è
    // solo il riassunto che ne fa la Centrale, e non basta a dire come sta il treno.
    condition: t.condizione || null,
    journeyPhase: t.faseViaggio || null,
    declaredDirection: versoDichiarato === 'ritorno' ? 'ritorno' : (versoDichiarato ? 'andata' : null),
    // Il default è false apposta: una Centrale che non manda il campo è una Centrale
    // che non si è espressa, e nel dubbio il convoglio va in elenco, non sulla mappa.
    positionKnown: t.posizioneNota === true,
    positionDoubt: t.incertezzaPosizione ?? null,
    // La previsione di arrivo arriva calcolata dalla Centrale: qui c'era una costante
    // null, e siccome i riquadri "ETA" e "Arrivo previsto" sono dentro una condizione
    // su questo campo non comparivano mai.
    arrivalTime: t.arrivoPrevisto ? new Date(t.arrivoPrevisto).getTime() : null,
    minutesToArrival: t.minutiAllArrivo ?? null,
    departureTime: null,
    delayMinutes: t.ritardo || 0,
    passengers: t.passeggeri || 0,
    progressPercent: t.progresso || 0,
    lastUpdate: t.ultimoAggiornamento ? new Date(t.ultimoAggiornamento).getTime() : Date.now()
  };
};

/** Converte il DTO stazione della Centrale nella shape Station del frontend. */
export const mapApiStation = (s: ApiStationPayload): Station => ({
  id: s.id,
  code: s.id,
  name: s.nome || s.id,
  city: s.nome || '',
  status: mapBackendStationStatus(s.stato || ''),
  coordinates: { x: s.latitudine || 0, y: s.longitudine || 0 },
  platforms: s.binari || 1,
  // null = non ha mai battuto. La Centrale lascia il campo vuoto apposta per le
  // stazioni spente: sostituirlo con Date.now() faceva scrivere l'ora corrente nella
  // colonna "Ultimo Heartbeat" di una stazione che non ha mai dato segni di vita.
  lastHeartbeat: s.ultimoHeartbeat ? new Date(s.ultimoHeartbeat).getTime() : null,
  declaredStatus: s.statoDichiarato || null,
  operatorsDispatched: s.stato === 'MANUTENZIONE'
});

/** Converte il DTO tratta del backend nella shape Route usata dal frontend. */
const mapApiRoute = (r: ApiRoutePayload): Route => ({
  id: r.id,
  name: r.nome || r.id,
  code: r.id,
  stationIds: r.stazioni || [],
  trainIds: r.treniIds || [],
  travelTimes: r.travelTimes || [],
  terminusStationId: r.capolinea ?? null,
  active: r.attivo || false,
  createdAt: Date.now()
});

/** Converte una riga del tabellone dei treni attesi nella shape del frontend. */
const mapApiExpectedTrain = (t: ApiExpectedTrainPayload): ExpectedTrain => ({
  trainId: t.trenoId,
  kind: t.tipo === 'in_partenza' ? 'in_partenza' : 'in_arrivo',
  status: mapBackendStatus(t.stato ?? ''),
  delayMinutes: t.ritardoMinuti ?? 0,
  minutesToArrival: t.minutiAllArrivo ?? 0,
  stopsBefore: t.fermateIntermedie ?? 0,
  arrivalTime: t.arrivoPrevisto ? new Date(t.arrivoPrevisto).getTime() : null,
  nextStationId: t.prossimaStazione || null,
  direction: t.direzione === 'ritorno' ? 'ritorno' : (t.direzione ? 'andata' : null),
  positionKnown: t.posizioneNota === true,
  positionDoubt: t.incertezzaPosizione ?? null,
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
  // "fermo" resta "fermo": non vuol dire "in stazione". La Centrale lo scrive anche per
  // un convoglio trattenuto a metà tratta, e dove il treno si trovi lo dice la stazione
  // corrente, non lo stato.
  if (lower === 'fermo') return 'fermo';
  if (lower === 'rotto') return 'guasto';
  if (lower === 'in manutenzione') return 'soppresso';
  if (lower === 'capolinea') return 'capolinea';
  return (lower || 'in_attesa') as TrainStatus;
};

/**
 * Mappa inversa: riporta lo stato del frontend alla parola con cui lo scrive la Centrale
 * (colonna Treni.stato). La usa statiDelTreno per un convoglio che non ha ancora
 * trasmesso, di cui si mostra lo stato con le parole della Centrale.
 */
export const mapFrontendStatus = (s: TrainStatus): string => {
  switch (s) {
    case 'in_viaggio': return 'attivo';
    case 'fermo': return 'fermo';
    case 'guasto': return 'rotto';
    case 'soppresso': return 'in manutenzione';
    // Lo decide il convoglio arrivando al capolinea: senza questa riga un treno a fine
    // corsa che non ha ancora trasmesso comparirebbe "fermo".
    case 'capolinea': return 'capolinea';
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
    const res = await fetch(`${API_BASE_URL}/dashboard`, { headers: authHeaders() });
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
    const res = await fetch(`${API_BASE_URL}/treni`, { headers: authHeaders() });
    if (!res.ok) throw new Error('Failed to fetch trains');
    const data = await res.json() as ApiTrainPayload[];
    return data.map(mapApiTrain);
  },

  /**
   * Richiede e mappa l'elenco delle stazioni e il relativo stato operativo.
   */
  getStations: async (): Promise<Station[]> => {
    const res = await fetch(`${API_BASE_URL}/stazioni`, { headers: authHeaders() });
    if (!res.ok) throw new Error('Failed to fetch stations');
    const data = await res.json() as ApiStationPayload[];
    return data.map(mapApiStation);
  },

  /**
   * I convogli che la Centrale si aspetta in una stazione, con l'orario previsto.
   *
   * <p>Il calcolo lo fa la Centrale sull'itinerario dei treni, quindi l'elenco comprende
   * anche chi passerà di lì qualche fermata più avanti.</p>
   */
  getExpectedTrains: async (stationId: string): Promise<ExpectedTrain[]> => {
    const res = await fetch(`${API_BASE_URL}/stazioni/${encodeURIComponent(stationId)}/treni-attesi`, {
      headers: authHeaders()
    });
    if (!res.ok) throw await createApiError(res);
    const data = await res.json() as ApiExpectedTrainPayload[];
    return data.map(mapApiExpectedTrain);
  },

  /**
   * Ottiene le configurazioni delle tratte/itinerari.
   */
  getRoutes: async (): Promise<Route[]> => {
    const res = await fetch(`${API_BASE_URL}/tratte`, { headers: authHeaders() });
    if (!res.ok) throw new Error('Failed to fetch routes');
    const data = await res.json() as ApiRoutePayload[];
    return data.map(mapApiRoute);
  },

  getTrackSegments: async (): Promise<TrackSegment[]> => {
    const res = await fetch(`${API_BASE_URL}/tratte-elementari`, { headers: authHeaders() });
    if (!res.ok) throw new Error('Failed to fetch track segments');
    return (await res.json() as ApiTrackSegmentPayload[]).map(mapApiTrackSegment);
  },

  createTrackSegment: async (segment: TrackSegment): Promise<TrackSegment> => {
    const res = await fetch(`${API_BASE_URL}/tratte-elementari`, {
      method: 'POST', headers: authHeaders(true),
      body: JSON.stringify({ id: segment.id, stazionePartenzaId: segment.departureStationId, stazioneArrivoId: segment.arrivalStationId, tempoPercorrenzaMinuti: segment.travelTimeMinutes })
    });
    if (!res.ok) throw await createApiError(res);
    return mapApiTrackSegment(await res.json() as ApiTrackSegmentPayload);
  },

  updateTrackSegment: async (id: string, segment: Partial<TrackSegment>): Promise<TrackSegment> => {
    const res = await fetch(`${API_BASE_URL}/tratte-elementari/${id}`, {
      method: 'PUT', headers: authHeaders(true),
      body: JSON.stringify({ stazionePartenzaId: segment.departureStationId, stazioneArrivoId: segment.arrivalStationId, tempoPercorrenzaMinuti: segment.travelTimeMinutes })
    });
    if (!res.ok) throw await createApiError(res);
    return mapApiTrackSegment(await res.json() as ApiTrackSegmentPayload);
  },

  deleteTrackSegment: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/tratte-elementari/${id}`, { method: 'DELETE', headers: authHeaders() });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Crea una nuova tratta inviando un payload JSON al backend.
   */
  createRoute: async (route: Route): Promise<Route> => {
    const res = await fetch(`${API_BASE_URL}/tratte`, {
      method: 'POST',
      headers: authHeaders(true),
      body: JSON.stringify({
        id: route.id,
        nome: route.name,
        stazioni: route.stationIds,
        travelTimes: route.travelTimes,
        attivo: route.active,
        capolinea: route.terminusStationId ?? null,
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
   *
   * `treniIds` viaggia solo se il chiamante lo ha davvero impostato: sulla Centrale un
   * elenco presente vuol dire "queste sono TUTTE le assegnazioni", quindi i treni non
   * elencati vengono sganciati. L'editor degli itinerari non gestisce le assegnazioni (non
   * ha nessun campo per sceglierle) e rimandava indietro la lista scaricata all'avvio,
   * sganciando i convogli assegnati nel frattempo dalla pagina Amministrazione: adesso
   * omette il campo e la Centrale lascia le assegnazioni dove sono.
   */
  updateRoute: async (id: string, route: Partial<Route>): Promise<Route> => {
    const res = await fetch(`${API_BASE_URL}/tratte/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: authHeaders(true),
      body: JSON.stringify({
        nome: route.name,
        stazioni: route.stationIds,
        travelTimes: route.travelTimes,
        attivo: route.active,
        // Il capolinea viaggia insieme all'elenco delle stazioni: la Centrale lo legge
        // solo quando "stazioni" c'e', cosi' un null vuol dire "nessun capolinea" e non
        // "non lo sto cambiando". Ed e' proprio mandando null che si rimette in servizio
        // un convoglio fermo a fine corsa.
        capolinea: route.terminusStationId ?? null,
        ...(route.trainIds !== undefined ? { treniIds: route.trainIds } : {})
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
    const res = await fetch(`${API_BASE_URL}/tratte/${encodeURIComponent(id)}`, { method: 'DELETE', headers: authHeaders() });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Preleva gli allarmi pendenti o passati (storico guasti).
   */
  getAlerts: async (): Promise<Alert[]> => {
    const res = await fetch(`${API_BASE_URL}/allarmi`, { headers: authHeaders() });
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
      origin: a.origine === 'DEDOTTO_CENTRALE' ? 'dedotto_centrale' : 'segnalato_campo',
      timestamp: a.timestamp ? new Date(a.timestamp).getTime() : Date.now(),
      acknowledged: a.risolto || false,
      resolvedAt: a.timestampRisoluzione ? new Date(a.timestampRisoluzione).getTime() : undefined
    }));
  },

  /**
   * Invia un comando al backend per segnalare un allarme come risolto (Acknowledged).
   */
  acknowledgeAlert: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/allarmi/${id}/risolvi`, { method: 'POST', headers: authHeaders() });
    if (!res.ok) throw new Error('Failed to resolve alert');
  },

  /**
   * Comando perentorio per l'emergenza: sopprime/blocca immediatamente la marcia di un treno.
   */
  suppressTrain: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/treni/${encodeURIComponent(id)}/sopprimi`, { method: 'POST', headers: authHeaders() });
    if (!res.ok) throw new Error('Failed to suppress train');
  },

  /**
   * Crea un nuovo treno (operazione riservata all'amministratore).
   * L'id inviato è il nome del convoglio scritto nel form: lato Centrale diventa la
   * chiave primaria del treno, quindi è l'unico momento in cui si può scegliere.
   * Lo stato non si manda: senza, la Centrale crea il convoglio "fermo".
   */
  createTrain: async (train: Train): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/treni`, {
      method: 'POST',
      headers: authHeaders(true),
      body: JSON.stringify({
        id: train.id,
        itinerario: train.routeId ? { id: train.routeId } : null
      })
    });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Aggiorna la tratta di un treno esistente (amministratore). Il nome del
   * convoglio non è tra i campi inviati: è la chiave primaria del treno e non si
   * modifica (per cambiarlo si elimina il treno e si ricrea). Nemmeno lo stato: la
   * PUT della Centrale lo accetterebbe ancora, ma dall'interfaccia non si sceglie più.
   */
  updateTrain: async (id: string, train: Partial<Train>): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/treni/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: authHeaders(true),
      body: JSON.stringify({
        itinerario: train.routeId ? { id: train.routeId } : undefined
      })
    });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Elimina definitivamente un treno dalla flotta (amministratore).
   */
  deleteTrain: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/treni/${encodeURIComponent(id)}`, { method: 'DELETE', headers: authHeaders() });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Registra una nuova stazione nella rete (amministratore). Stato e binari non si
   * mandano: senza, la Centrale la crea OFFLINE (finché il nodo non batte) e con un
   * binario.
   */
  createStation: async (station: Station): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/stazioni`, {
      method: 'POST',
      headers: authHeaders(true),
      body: JSON.stringify({
        id: station.id,
        nome: station.name,
        latitudine: station.coordinates.x,
        longitudine: station.coordinates.y
      })
    });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Aggiorna nome e coordinate di una stazione esistente (amministratore). Stato e
   * binari non si mandano: la PUT della Centrale li accetterebbe ancora, ma
   * dall'interfaccia non si scelgono più.
   */
  updateStation: async (id: string, station: Partial<Station>): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/stazioni/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: authHeaders(true),
      body: JSON.stringify({
        nome: station.name,
        latitudine: station.coordinates?.x,
        longitudine: station.coordinates?.y
      })
    });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Elimina una stazione dalla rete (amministratore).
   */
  deleteStation: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/stazioni/${encodeURIComponent(id)}`, { method: 'DELETE', headers: authHeaders() });
    if (!res.ok) throw await createApiError(res);
  },

  /**
   * Chiede alla Centrale chi è l'utente che possiede il token corrente.
   *
   * Con Keycloak il login non passa più da qui (username e password li raccoglie
   * l'Identity Provider): la web app arriva già con il token in mano e usa questa
   * chiamata per sapere nome, matricola e ruolo da mostrare in interfaccia. È anche
   * la prova che il token è buono, perché se non lo fosse la Centrale direbbe 401.
   */
  getProfilo: async (): Promise<User> => {
    const res = await fetch(`${API_BASE_URL}/auth/me`, { headers: authHeaders() });
    if (!res.ok) throw await createApiError(res);
    return mapProfilo(await res.json() as ApiProfiloResponse);
  },

  /**
   * Invia gli operatori in manutenzione per una stazione.
   */
  dispatchOperators: async (id: string): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/stazioni/${encodeURIComponent(id)}/manutenzione`, { method: 'POST', headers: authHeaders() });
    if (!res.ok) throw new Error('Failed to dispatch operators');
  },

  /**
   * Recupera i transiti.
   */
  getTransits: async (): Promise<Transit[]> => {
    const res = await fetch(`${API_BASE_URL}/transiti`, { headers: authHeaders() });
    if (!res.ok) throw new Error('Failed to fetch transits');
    const data = await res.json() as ApiTransitPayload[];
    return data.map((t) => ({
      id: t.id,
      trainId: t.trainId || t.trenoId || '',
      stationId: t.stationId || t.stazioneId || '',
      trackSegmentId: t.trattaId ?? null,
      type: (t.tipo || t.type || 'ingresso') as 'ingresso' | 'uscita',
      timestamp: t.timestamp ? new Date(t.timestamp).getTime() : Date.now(),
      delayed: t.delayed ?? t.inRitardo ?? false,
      delayMinutes: t.delayMinutes ?? t.ritardo ?? 0
    }));
  }
};
