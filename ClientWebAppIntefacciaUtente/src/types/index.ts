// ============================================================
// TYPES — Sistema di Monitoraggio Traffico Ferroviario
// 
// File centrale contenente tutte le interfacce e i tipi TypeScript 
// strutturati in modo coerente con le API della Centrale.
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
  /**
   * Lo stato che il nodo dichiara di sé nel battito (ONLINE o GUASTA, il suo automa S10),
   * null se non ha ancora battuto. status invece è lo stato che gli dà la Centrale (M12):
   * di solito sono uguali, in MANUTENZIONE no, e lì la stazione si mostra in tutti e due.
   */
  declaredStatus: string | null;
  platforms: number;
  faultDescription?: string;
  faultSince?: number;
  operatorsDispatched?: boolean;
}

// --- TRENI ---
/**
 * Lo stato del convoglio come lo tiene la Centrale (colonna Treni.stato, automa M13),
 * tradotto nelle parole della web app. È un riassunto: per sapere come sta davvero il
 * treno si guardano condition e journeyPhase, cioè lo stato dei suoi due automi.
 */
export type TrainStatus =
  | 'in_viaggio'
  /**
   * "fermo" della Centrale: il convoglio non sta andando, ma NON vuol dire che sia in
   * una stazione. Vale anche per il treno trattenuto a metà tratta da una stazione
   * guasta davanti a lui: prima qui c'era 'in_stazione', e un treno fermo in linea
   * compariva "IN STAZIONE". Se è in stazione lo dice currentStationId.
   */
  | 'fermo'
  | 'in_ritardo'
  | 'guasto'
  | 'soppresso'
  | 'in_attesa'
  /**
   * Corsa finita: il convoglio è arrivato al capolinea marcato sulla tratta e non
   * riparte da solo. Non è un guasto e non è una soppressione — il treno sta bene, è
   * il servizio a essere finito — e se ne esce solo assegnandogli un'altra tratta.
   */
  | 'capolinea';

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
  /** null quando la Centrale non è in grado di garantire il verso di marcia. */
  direction: 'andata' | 'ritorno' | null;
  /**
   * Condizione del convoglio come la dichiara lui nella telemetria: REGOLARE, EMERGENZA
   * o SOPPRESSO, lo stato del suo automa T6 (TrainDB.condizione). null finché la Centrale
   * non ha ricevuto un frame dal convoglio.
   */
  condition: string | null;
  /**
   * Fase del viaggio dichiarata dal convoglio: SENZA_ITINERARIO, IN_STAZIONE, IN_VIAGGIO,
   * BLOCCATO_IN_STAZIONE, BLOCCATO_IN_TRATTA o FERMO_CAPOLINEA, lo stato del suo automa T5
   * (TrainDB.faseViaggio). null come sopra.
   */
  journeyPhase: string | null;
  /**
   * Il verso che il convoglio dichiara (automa T7), anche quando la Centrale non lo
   * garantisce: direction è il giudizio della Centrale sulla posizione, questo è lo stato
   * dell'automa. null se il convoglio non l'ha mai mandato.
   */
  declaredDirection: 'andata' | 'ritorno' | null;
  /**
   * true quando la Centrale sa dire dove sta il convoglio: fermo in una stazione,
   * oppure in linea con TUTTI E DUE gli estremi della tratta noti.
   *
   * È la condizione per disegnarlo sulla mappa. Quando è false il convoglio non si
   * piazza da nessuna parte — nemmeno partendo dall'ultima stazione che gli si conosce,
   * che è il modo in cui prima comparivano convogli in viaggio su tratte inesistenti —
   * ma finisce nell'elenco di quelli a posizione incerta. Torna sulla mappa da solo
   * appena la Centrale rimette insieme partenza e arrivo.
   */
  positionKnown: boolean;
  /**
   * Perché la posizione è incerta, così l'elenco non dice solo "non lo so" ma anche da
   * dove viene il buco. null quando la posizione è nota.
   */
  positionDoubt: string | null;
  /**
   * Stima dell'orario di arrivo nella prossima stazione, in millisecondi Unix: la
   * calcola la Centrale componendo i tempi di percorrenza dell'itinerario con il
   * progresso sulla tratta. È null quando la previsione non si può fare (convoglio
   * senza itinerario o corsa soppressa).
   */
  arrivalTime: number | null;
  /**
   * Lo stesso dato nei minuti dell'orario di servizio ("arriva fra 12 minuti"): sono
   * l'unità in cui è espresso anche delayMinutes, mentre arrivalTime è un istante
   * dell'orologio reale (la simulazione corre accelerata, i due numeri non coincidono).
   */
  minutesToArrival: number | null;
  departureTime: number | null;
  delayMinutes: number; // ritardo positivo o negativo (anticipo)
  passengers: number;
  lastUpdate: number; // ultimo dato telemetrico GPS
  progressPercent: number; // 0-100, posizione interpolata tra stazione precedente e successiva per visualizzatori lineari
}

/**
 * Lo stato di uno degli automi di un elemento (treno o stazione), pronto da stampare.
 *
 * Un elemento non sta mai in uno stato solo: un treno è in una condizione (T6), in una
 * fase del viaggio (T5) e in un verso di marcia (T7) tutto insieme, e la schermata li deve
 * mostrare tutti. Il suo stato composto è quindi un elenco di questi, uno per automa.
 */
export interface StatoAutoma {
  /** La macchina a stati a cui appartiene il valore, come la chiama la documentazione. */
  automa: string;
  /** Il valore come lo scrive l'automa, per esempio "BLOCCATO_IN_TRATTA". */
  valore: string;
  /** Il colore del badge. */
  tono: 'success' | 'warning' | 'danger' | 'info' | 'neutral';
}

// --- TRATTE ---
export interface Route {
  id: string;
  name: string;
  code: string; // codice univoco es. "AL-TO-001"
  stationIds: string[]; // sequenza ordinata di nodi stazioni attraversate (andata)
  trainIds: string[]; // pool di treni assegnati alla linea
  travelTimes: number[]; // storico o delta previsti: minuti necessari tra stazione[i] e stazione[i+1]
  /**
   * Il nodo su cui la corsa si chiude, o null se la tratta si percorre all'infinito
   * (comportamento normale). È sempre uno dei due estremi di stationIds: marcando
   * l'ultimo il convoglio chiude dopo la sola andata, marcando il primo dopo l'andata
   * e il ritorno. Arrivato lì il treno passa in stato 'capolinea' e resta fermo.
   */
  terminusStationId: string | null;
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
  | 'binario_guasto'
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
  origin: 'segnalato_campo' | 'dedotto_centrale';
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
  /**
   * Come AlertSeverity ma con in più "success": una notifica d'interfaccia può dire
   * che un comando è andato a buon fine, cosa che un guasto non può fare.
   */
  severity: AlertSeverity | 'success';
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
/**
 * Riga del tabellone di una stazione: un convoglio che la Centrale si aspetta in
 * ingresso, oppure quello fermo lì che ne uscirà. Lo calcola la Centrale
 * sull'itinerario dei treni (GET /api/stazioni/{id}/treni-attesi), non il browser
 * sulla loro posizione istantanea.
 */
export interface ExpectedTrain {
  trainId: string;
  /** "in_arrivo" per chi deve ancora passare, "in_partenza" per chi è fermo qui. */
  kind: 'in_arrivo' | 'in_partenza';
  status: TrainStatus;
  delayMinutes: number;
  /** Minuti di servizio che mancano all'arrivo (0 per un treno già in stazione). */
  minutesToArrival: number;
  /** Quante fermate il convoglio farà prima di questa. */
  stopsBefore: number;
  /** Orario previsto in millisecondi Unix, null per chi è già in stazione. */
  arrivalTime: number | null;
  nextStationId: string | null;
  /** null quando la Centrale non garantisce il verso di marcia. */
  direction: 'andata' | 'ritorno' | null;
  /**
   * true quando la posizione del convoglio è confermata. Il convoglio resta sul
   * tabellone anche quando è false — la stazione lo aspetta comunque, il tabellone lo
   * costruisce l'itinerario — ma l'orario qui accanto è calcolato sull'ultima posizione
   * certa, che può essere vecchia: va detto a chi legge.
   */
  positionKnown: boolean;
  /** Perché la posizione è incerta. null quando è nota. */
  positionDoubt: string | null;
}
