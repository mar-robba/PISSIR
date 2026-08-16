import { create } from 'zustand';
import type {
  Train, Station, Alert, Route, TrackSegment, Transit, DashboardKPI, UiNotification,
} from '../types';
import { apiClient } from '../api/apiClient';

/** Restituisce il dettaglio ricevuto dalla Centrale per mostrarlo all'operatore. */
const dettaglioErrore = (err: unknown): string =>
  err instanceof Error && err.message
    ? `\nMotivo: ${err.message}`
    : '\nMotivo: il server non ha fornito ulteriori dettagli.';

/**
 * Rimette il treno nell'itinerario giusto dentro `routes[].trainIds`: lo toglie da tutti
 * gli altri e lo aggiunge a quello nuovo (con `routeId` vuoto viene solo tolto).
 * Serve perché le assegnazioni sono scritte in due posti — `trains[].routeId` e
 * `routes[].trainIds` — e finora l'amministrazione ne aggiornava soltanto uno.
 */
const routesConAssegnazione = (routes: Route[], trainId: string, routeId?: string): Route[] =>
  routes.map((route) => {
    const dovrebbeEsserci = !!routeId && route.id === routeId;
    const ceGia = route.trainIds.includes(trainId);
    if (dovrebbeEsserci === ceGia) return route;
    return {
      ...route,
      trainIds: dovrebbeEsserci
        ? [...route.trainIds, trainId]
        : route.trainIds.filter((id) => id !== trainId),
    };
  });

/**
 * src/store in React
In questa app, la cartella src/store contiene il codice per lo stato globale dell’app, non i singoli componenti.

Cosa c’è dentro
railwayStore.ts
definisce lo stato globale di treni, stazioni, alert, tratte, transiti, selezioni
espone azioni per modificare lo stato:
 * Interfaccia dello Stato Globale dell'applicazione gestito con Zustand.
 * Comprende gli array immutabili di tutte le entità core (treni, stazioni, ecc)
 * e i metodi/azioni ("actions") per mutarle o aggiornarle in risposta 
 * agli eventi di WebSocket o azioni dell'operatore.
 */

interface RailwayState {
  trains: Train[];
  stations: Station[];
  alerts: Alert[];
  routes: Route[];
  trackSegments: TrackSegment[];
  transits: Transit[];
  /**
   * I KPI così come li calcola la Centrale (GET /api/dashboard e snapshot periodico).
   * La dashboard li legge da qui invece di rifarsi i conti da sola: le due formule non
   * coincidevano e i numeri a schermo discordavano da quelli del server.
   */
  kpi: DashboardKPI | null;
  /** Conferme dei comandi dati dall'operatore: notifiche locali, NON allarmi di rete. */
  notifications: UiNotification[];
  selectedTrainId: string | null;
  selectedStationId: string | null;

  // Azioni di Store Mutation
  setTrains: (trains: Train[]) => void;
  updateTrain: (id: string, update: Partial<Train>) => void;
  setStations: (stations: Station[]) => void;
  updateStation: (id: string, update: Partial<Station>) => void;
  addAlert: (alert: Alert) => void;
  acknowledgeAlert: (id: string) => void;
  setKpi: (kpi: DashboardKPI) => void;
  pushNotification: (notification: Omit<UiNotification, 'id' | 'timestamp'>) => void;
  dismissNotification: (id: string) => void;
  addRoute: (route: Route) => Promise<void>;
  updateRoute: (id: string, update: Partial<Route>) => Promise<void>;
  deleteRoute: (id: string) => Promise<void>;
  createTrackSegment: (segment: TrackSegment) => Promise<void>;
  updateTrackSegment: (id: string, segment: Partial<TrackSegment>) => Promise<void>;
  deleteTrackSegment: (id: string) => Promise<void>;
  addTransit: (transit: Transit) => void;

  // Azioni CRUD amministrative (persistite sul backend via API)
  adminCreateTrain: (train: Train) => Promise<void>;
  adminUpdateTrain: (id: string, update: Partial<Train>) => Promise<void>;
  adminDeleteTrain: (id: string) => Promise<void>;
  adminCreateStation: (station: Station) => Promise<void>;
  adminUpdateStation: (id: string, update: Partial<Station>) => Promise<void>;
  adminDeleteStation: (id: string) => Promise<void>;

  // UX State
  selectTrain: (id: string | null) => void;
  selectStation: (id: string | null) => void;

  // Azioni di Business Logic
  suppressTrain: (trainId: string) => void;
  dispatchOperators: (stationId: string) => void;
  initialize: () => void;
}

/**
 * Store Zustand per la rete ferroviaria.
 * Permette a componenti distanti nell'albero DOM di leggere
 * e scrivere lo stato senza prop-drilling (Global State Management).
 */
export const useRailwayStore = create<RailwayState>((set, get) => ({
  trains: [],
  stations: [],
  alerts: [],
  routes: [],
  trackSegments: [],
  transits: [],
  kpi: null,
  notifications: [],
  selectedTrainId: null,
  selectedStationId: null,

  /**
   * Carica i dati reali dal backend.
   * I KPI si scaricano insieme al resto: lo snapshot dalla WebSocket arriva ogni dieci
   * secondi e senza questa chiamata la dashboard resterebbe vuota fino al primo.
   */
  initialize: async () => {
    try {
      const [trains, stations, alerts, routes, trackSegments, transits, kpi] = await Promise.all([
        apiClient.getTrains(),
        apiClient.getStations(),
        apiClient.getAlerts(),
        apiClient.getRoutes(),
        apiClient.getTrackSegments(),
        apiClient.getTransits(),
        apiClient.getDashboard(),
      ]);
      set({ trains, stations, alerts, routes, trackSegments, transits, kpi });
    } catch (err) {
      console.error('Failed to initialize railway store from API', err);
    }
  },

  /** Sovrascrive completamente l'array treni */
  setTrains: (trains) => set({ trains }),
  
  /** 
   * Aggiorna selettivamente i campi di un singolo treno. 
   * Ideale per l'elaborazione dei pacchetti telemetrici (es. posizione/velocità).
   */
  updateTrain: (id, update) =>
    set((s) => ({
      trains: s.trains.map((t) => (t.id === id ? { ...t, ...update } : t)),
    })),

  /** Sovrascrive completamente l'array stazioni */
  setStations: (stations) => set({ stations }),
  
  /** 
   * Aggiorna selettivamente una stazione. 
   * Usato alla ricezione di heartbeat per aggiornare i timestamp e lo stato (ONLINE/OFFLINE).
   */
  updateStation: (id, update) =>
    set((s) => ({
      stations: s.stations.map((st) =>
        st.id === id ? { ...st, ...update } : st
      ),
    })),

  /**
   * Inserisce un guasto in testa all'array (LIFO) SE è nuovo, altrimenti aggiorna quello
   * che c'è già.
   *
   * La Centrale manda un evento ALERT con lo stesso id anche quando un guasto si chiude
   * (fail-stop rientrato) o quando un allarme già aperto si ripete: aggiungendo sempre una
   * riga nuova, la chiusura di un guasto faceva comparire un secondo allarme rosso —
   * l'esatto contrario di quello che era appena successo — e ogni ripetizione ne aggiungeva
   * un altro. Due elementi con la stessa key sono per giunta un errore di rendering React.
   */
  addAlert: (alert) =>
    set((s) => {
      const esistente = s.alerts.findIndex((a) => a.id === alert.id);
      if (esistente < 0) return { alerts: [alert, ...s.alerts] };
      const alerts = [...s.alerts];
      alerts[esistente] = { ...alerts[esistente], ...alert };
      return { alerts };
    }),

  /**
   * Setta a "risolto" (acknowledged) l'allarme senza cancellarlo dallo storico.
   * Lo stato locale cambia SOLO se la Centrale ha davvero chiuso il guasto: prima si
   * segnava comunque come risolto e l'errore finiva in console, quindi un allarme che sul
   * server era ancora aperto spariva dall'elenco dell'operatore.
   */
  acknowledgeAlert: async (id) => {
    try {
      await apiClient.acknowledgeAlert(id);
    } catch (err) {
      console.error('Failed to acknowledge alert on server', err);
      get().pushNotification({
        kind: 'errore_comando',
        severity: 'warning',
        message: `La Centrale non ha registrato la presa in carico dell'allarme ${id}: l'allarme resta aperto.`,
      });
      return;
    }
    set((s) => ({
      alerts: s.alerts.map((a) =>
        a.id === id ? { ...a, acknowledged: true, resolvedAt: Date.now() } : a
      ),
    }));
  },

  /** Sostituisce i KPI con quelli calcolati dalla Centrale. */
  setKpi: (kpi) => set({ kpi }),

  /** Aggiunge una notifica d'interfaccia (esito di un comando), tenendo le ultime dieci. */
  pushNotification: (notification) =>
    set((s) => ({
      notifications: [
        { ...notification, id: `notif-${Date.now()}-${s.notifications.length}`, timestamp: Date.now() },
        ...s.notifications,
      ].slice(0, 10),
    })),

  dismissNotification: (id) =>
    set((s) => ({ notifications: s.notifications.filter((n) => n.id !== id) })),

  /**
   * Crea una nuova tratta sul backend e la aggiunge allo stato locale (UC6).
   * A differenza delle altre azioni l'errore NON viene fermato qui: lo deve vedere
   * il form, che in caso di rifiuto azzera l'editing e mostra quali tratte mancano.
   */
  addRoute: async (route) => {
    const creata = await apiClient.createRoute(route);
    set((s) => ({ routes: [...s.routes, creata] }));
  },

  /** Aggiorna una tratta sul backend e nello stato locale (UC7). Errori: vedi addRoute. */
  updateRoute: async (id, update) => {
    const aggiornata = await apiClient.updateRoute(id, update);
    set((s) => ({
      routes: s.routes.map((r) => (r.id === id ? aggiornata : r)),
    }));
  },

  /** Elimina una tratta sul backend e dallo stato locale (UC7). */
  deleteRoute: async (id) => {
    try {
      await apiClient.deleteRoute(id);
      set((s) => ({ routes: s.routes.filter((r) => r.id !== id) }));
    } catch (err) {
      console.error('Failed to delete route', err);
      alert(`Errore: impossibile eliminare la tratta sul server.${dettaglioErrore(err)}`);
    }
  },

  createTrackSegment: async (segment) => {
    try {
      const created = await apiClient.createTrackSegment(segment);
      set((s) => ({ trackSegments: [...s.trackSegments, created] }));
    } catch (err) { alert(`Errore: impossibile creare la tratta fisica.${dettaglioErrore(err)}`); }
  },

  updateTrackSegment: async (id, segment) => {
    try {
      const updated = await apiClient.updateTrackSegment(id, segment);
      set((s) => ({ trackSegments: s.trackSegments.map((t) => t.id === id ? updated : t) }));
    } catch (err) { alert(`Errore: impossibile aggiornare la tratta fisica.${dettaglioErrore(err)}`); }
  },

  deleteTrackSegment: async (id) => {
    try {
      await apiClient.deleteTrackSegment(id);
      set((s) => ({ trackSegments: s.trackSegments.filter((t) => t.id !== id) }));
    } catch (err) { alert(`Errore: impossibile eliminare la tratta fisica.${dettaglioErrore(err)}`); }
  },

  /** Crea un nuovo treno sul backend e lo aggiunge allo stato locale. */
  adminCreateTrain: async (train) => {
    try {
      await apiClient.createTrain(train);
      set((s) => ({
        trains: [...s.trains, train],
        routes: routesConAssegnazione(s.routes, train.id, train.routeId),
      }));
    } catch (err) {
      console.error('Failed to create train', err);
      alert(`Errore: impossibile creare il treno sul server.${dettaglioErrore(err)}`);
    }
  },

  /** Aggiorna i dati di un treno sul backend e nello stato locale (UC8). */
  adminUpdateTrain: async (id, update) => {
    try {
      await apiClient.updateTrain(id, update);
      set((s) => ({
        trains: s.trains.map((t) => (t.id === id ? { ...t, ...update } : t)),
        // Anche gli itinerari vanno riallineati: routes[].trainIds veniva scaricato una
        // volta sola all'avvio e restava fermo lì, quindi mostrava conteggi vecchi e —
        // finché la PUT dell'editor lo rimandava indietro — sganciava i treni assegnati.
        routes: update.routeId !== undefined
          ? routesConAssegnazione(s.routes, id, update.routeId)
          : s.routes,
      }));
    } catch (err) {
      console.error('Failed to update train', err);
      alert(`Errore: impossibile aggiornare il treno sul server.${dettaglioErrore(err)}`);
    }
  },

  /** Elimina un treno sul backend e dallo stato locale. */
  adminDeleteTrain: async (id) => {
    try {
      await apiClient.deleteTrain(id);
      set((s) => ({
        trains: s.trains.filter((t) => t.id !== id),
        routes: routesConAssegnazione(s.routes, id, ''),
      }));
    } catch (err) {
      console.error('Failed to delete train', err);
      alert(`Errore: impossibile eliminare il treno sul server.${dettaglioErrore(err)}`);
    }
  },

  /** Crea una nuova stazione sul backend e la aggiunge allo stato locale. */
  adminCreateStation: async (station) => {
    try {
      await apiClient.createStation(station);
      set((s) => ({ stations: [...s.stations, station] }));
    } catch (err) {
      console.error('Failed to create station', err);
      alert(`Errore: impossibile creare la stazione sul server.${dettaglioErrore(err)}`);
    }
  },

  /** Aggiorna i dati di una stazione sul backend e nello stato locale. */
  adminUpdateStation: async (id, update) => {
    try {
      await apiClient.updateStation(id, update);
      set((s) => ({
        stations: s.stations.map((st) => (st.id === id ? { ...st, ...update } : st)),
      }));
    } catch (err) {
      console.error('Failed to update station', err);
      alert(`Errore: impossibile aggiornare la stazione sul server.${dettaglioErrore(err)}`);
    }
  },

  /** Elimina una stazione sul backend e dallo stato locale. */
  adminDeleteStation: async (id) => {
    try {
      await apiClient.deleteStation(id);
      set((s) => ({ stations: s.stations.filter((st) => st.id !== id) }));
    } catch (err) {
      console.error('Failed to delete station', err);
      alert(`Errore: impossibile eliminare la stazione sul server.${dettaglioErrore(err)}`);
    }
  },

  addTransit: (transit) =>
    set((s) => ({ transits: [transit, ...s.transits] })),

  selectTrain: (id) => set({ selectedTrainId: id }),
  selectStation: (id) => set({ selectedStationId: id }),

  /**
   * Logica di business frontend: chiama l'API per sopprimere il treno e aggiorna lo stato
   * locale. L'esito è una notifica d'interfaccia, non un allarme: sulla Centrale nessun
   * guasto è stato aperto, quindi metterlo fra gli allarmi era raccontare una cosa falsa.
   */
  suppressTrain: async (trainId) => {
    try {
      await apiClient.suppressTrain(trainId);
      const { updateTrain, pushNotification } = get();
      updateTrain(trainId, { status: 'soppresso', arrivalTime: null, departureTime: null });
      pushNotification({
        kind: 'treno_soppresso',
        severity: 'warning',
        message: `Treno ${trainId} soppresso dall'amministratore.`,
      });
    } catch (err) {
      console.error('Failed to suppress train', err);
      get().pushNotification({
        kind: 'errore_comando',
        severity: 'critical',
        message: `Soppressione del treno ${trainId} non riuscita: la Centrale ha rifiutato il comando.`,
      });
    }
  },

  /**
   * Registra lo stato "operatori dispacciati" per una stazione guasta via API.
   */
  dispatchOperators: async (stationId) => {
    const station = get().stations.find((s) => s.id === stationId);
    try {
      await apiClient.dispatchOperators(stationId);
      const { updateStation, pushNotification } = get();
      updateStation(stationId, { operatorsDispatched: true });
      pushNotification({
        kind: 'operatori_inviati',
        severity: 'info',
        message: `Operatori inviati alla stazione ${station?.code ?? stationId}`
          + `${station ? ` (${station.name})` : ''} per riparazione.`,
      });
    } catch (err) {
      console.error('Failed to dispatch operators', err);
      get().pushNotification({
        kind: 'errore_comando',
        severity: 'critical',
        message: `Invio degli operatori alla stazione ${station?.name ?? stationId} non riuscito.`,
      });
    }
  },
}));
