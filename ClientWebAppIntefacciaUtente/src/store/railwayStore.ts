import { create } from 'zustand';
import type { Train, Station, Alert, Route, Transit } from '../types';
import {
  MOCK_TRAINS,
  MOCK_STATIONS,
  MOCK_ALERTS,
  MOCK_ROUTES,
  MOCK_TRANSITS,
} from '../api/mockData';

interface RailwayState {
  trains: Train[];
  stations: Station[];
  alerts: Alert[];
  routes: Route[];
  transits: Transit[];
  selectedTrainId: string | null;
  selectedStationId: string | null;

  // Actions
  setTrains: (trains: Train[]) => void;
  updateTrain: (id: string, update: Partial<Train>) => void;
  setStations: (stations: Station[]) => void;
  updateStation: (id: string, update: Partial<Station>) => void;
  addAlert: (alert: Alert) => void;
  acknowledgeAlert: (id: string) => void;
  addRoute: (route: Route) => void;
  updateRoute: (id: string, update: Partial<Route>) => void;
  deleteRoute: (id: string) => void;
  addTransit: (transit: Transit) => void;
  selectTrain: (id: string | null) => void;
  selectStation: (id: string | null) => void;
  suppressTrain: (trainId: string) => void;
  dispatchOperators: (stationId: string) => void;
  initialize: () => void;
}

export const useRailwayStore = create<RailwayState>((set, get) => ({
  trains: [],
  stations: [],
  alerts: [],
  routes: [],
  transits: [],
  selectedTrainId: null,
  selectedStationId: null,

  initialize: () => {
    set({
      trains: MOCK_TRAINS.map((t) => ({ ...t })),
      stations: MOCK_STATIONS.map((s) => ({ ...s })),
      alerts: MOCK_ALERTS.map((a) => ({ ...a })),
      routes: MOCK_ROUTES.map((r) => ({ ...r })),
      transits: MOCK_TRANSITS.map((t) => ({ ...t })),
    });
  },

  setTrains: (trains) => set({ trains }),
  updateTrain: (id, update) =>
    set((s) => ({
      trains: s.trains.map((t) => (t.id === id ? { ...t, ...update } : t)),
    })),

  setStations: (stations) => set({ stations }),
  updateStation: (id, update) =>
    set((s) => ({
      stations: s.stations.map((st) =>
        st.id === id ? { ...st, ...update } : st
      ),
    })),

  addAlert: (alert) =>
    set((s) => ({ alerts: [alert, ...s.alerts] })),

  acknowledgeAlert: (id) =>
    set((s) => ({
      alerts: s.alerts.map((a) =>
        a.id === id ? { ...a, acknowledged: true } : a
      ),
    })),

  addRoute: (route) =>
    set((s) => ({ routes: [...s.routes, route] })),

  updateRoute: (id, update) =>
    set((s) => ({
      routes: s.routes.map((r) => (r.id === id ? { ...r, ...update } : r)),
    })),

  deleteRoute: (id) =>
    set((s) => ({ routes: s.routes.filter((r) => r.id !== id) })),

  addTransit: (transit) =>
    set((s) => ({ transits: [transit, ...s.transits] })),

  selectTrain: (id) => set({ selectedTrainId: id }),
  selectStation: (id) => set({ selectedStationId: id }),

  suppressTrain: (trainId) => {
    const { updateTrain, addAlert } = get();
    updateTrain(trainId, { status: 'soppresso', arrivalTime: null, departureTime: null });
    addAlert({
      id: `al-supp-${Date.now()}`,
      type: 'treno_soppresso',
      severity: 'warning',
      message: `Treno ${get().trains.find((t) => t.id === trainId)?.convoglio ?? trainId} soppresso dall'amministratore.`,
      trainId,
      timestamp: Date.now(),
      acknowledged: false,
    });
  },

  dispatchOperators: (stationId) => {
    const { updateStation, addAlert } = get();
    const station = get().stations.find((s) => s.id === stationId);
    if (!station) return;
    updateStation(stationId, { operatorsDispatched: true });
    addAlert({
      id: `al-ops-${Date.now()}`,
      type: 'operatori_inviati',
      severity: 'info',
      message: `Operatori inviati alla stazione ${station.code} (${station.name}) per riparazione.`,
      stationId,
      timestamp: Date.now(),
      acknowledged: false,
    });
  },
}));
