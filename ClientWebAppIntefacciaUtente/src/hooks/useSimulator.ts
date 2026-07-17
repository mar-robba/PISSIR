import { useEffect } from 'react';
import { useRailwayStore } from '../store/railwayStore';
import type { Transit } from '../types';

const STATION_IDS = [
  'st-TO', 'st-AL', 'st-AT', 'st-MI', 'st-GE', 'st-NO', 'st-VC', 'st-CN', 'st-PV', 'st-PC',
];

/**
 * Hook che simula la ricezione di dati in tempo reale (MQTT / heartbeat).
 * In produzione, questo sarà sostituito da WebSocket o SSE collegati alla REST API.
 */
export function useSimulator() {
  const { trains, updateTrain, updateStation, addAlert, addTransit } =
    useRailwayStore();

  // ---- Simulazione movimento treni ----
  useEffect(() => {
    const interval = setInterval(() => {
      const store = useRailwayStore.getState();
      const activeTrains = store.trains.filter(
        (t) => t.status === 'in_viaggio' || t.status === 'in_ritardo'
      );

      activeTrains.forEach((train) => {
        // Avanza progresso
        const newProgress = Math.min(train.progressPercent + Math.random() * 3, 100);

        if (newProgress >= 100 && train.nextStationId) {
          // Il treno arriva in stazione
          const arrivalTransit: Transit = {
            id: `tl-${Date.now()}-${train.id}`,
            trainId: train.id,
            stationId: train.nextStationId,
            type: 'ingresso',
            timestamp: Date.now(),
            delayed: train.delayMinutes > 0,
            delayMinutes: train.delayMinutes,
          };
          addTransit(arrivalTransit);

          updateTrain(train.id, {
            status: 'in_stazione',
            currentStationId: train.nextStationId,
            previousStationId: train.nextStationId,
            progressPercent: 0,
            lastUpdate: Date.now(),
          });

          // Aggiorna heartbeat della stazione
          const stId = train.nextStationId;
          updateStation(stId, { lastHeartbeat: Date.now() });
        } else if (train.status === 'in_stazione') {
          // Il treno parte dopo un po'
          const currentIdx = store.routes
            .find((r) => r.id === train.routeId)
            ?.stationIds.indexOf(train.currentStationId ?? '') ?? -1;

          const route = store.routes.find((r) => r.id === train.routeId);
          if (route && currentIdx >= 0 && currentIdx < route.stationIds.length - 1) {
            const nextSt = route.stationIds[currentIdx + 1];
            const travelTime = (route.travelTimes[currentIdx] ?? 30) * 60000;
            const departTransit: Transit = {
              id: `tl-dep-${Date.now()}-${train.id}`,
              trainId: train.id,
              stationId: train.currentStationId ?? '',
              type: 'uscita',
              timestamp: Date.now(),
              delayed: train.delayMinutes > 0,
              delayMinutes: train.delayMinutes,
            };
            addTransit(departTransit);

            updateTrain(train.id, {
              status: 'in_viaggio',
              previousStationId: train.currentStationId,
              currentStationId: null,
              nextStationId: nextSt,
              arrivalTime: Date.now() + travelTime,
              progressPercent: 0,
              lastUpdate: Date.now(),
            });
          }
        } else {
          updateTrain(train.id, {
            progressPercent: newProgress,
            lastUpdate: Date.now(),
          });
        }
      });
    }, 4000); // tick ogni 4 secondi

    return () => clearInterval(interval);
  }, [updateTrain, updateStation, addAlert, addTransit]);

  // ---- Simulazione heartbeat stazioni ----
  useEffect(() => {
    const interval = setInterval(() => {
      const store = useRailwayStore.getState();
      store.stations.forEach((station) => {
        if (station.status === 'operativa' || station.status === 'manutenzione') {
          // Stazioni operative mandano heartbeat regolare
          updateStation(station.id, { lastHeartbeat: Date.now() });
        }
        // Stazioni offline/guaste non aggiornano heartbeat → fault manager rileva
      });

      // Fault detection: stazioni senza heartbeat da >8 minuti
      store.stations.forEach((station) => {
        if (station.status === 'operativa') {
          const elapsed = Date.now() - station.lastHeartbeat;
          if (elapsed > 480000) {
            updateStation(station.id, {
              status: 'offline',
              faultDescription: 'Heartbeat mancante — stazione probabilmente offline',
              faultSince: Date.now(),
            });
            addAlert({
              id: `al-hb-${station.id}-${Date.now()}`,
              type: 'heartbeat_mancante',
              severity: 'critical',
              message: `Stazione ${station.code} (${station.name}) — Heartbeat mancante. Possibile guasto.`,
              stationId: station.id,
              timestamp: Date.now(),
              acknowledged: false,
            });
          }
        }
      });
    }, 8000);

    return () => clearInterval(interval);
  }, [updateStation, addAlert]);

  // ---- Alert automatici ritardi ----
  useEffect(() => {
    const interval = setInterval(() => {
      const store = useRailwayStore.getState();
      store.trains.forEach((train) => {
        if (train.status === 'in_stazione' && train.delayMinutes > 15) {
          // Check se già esiste alert attivo per questo treno
          const existingAlert = store.alerts.find(
            (a) => a.trainId === train.id && a.type === 'treno_fermo' && !a.acknowledged
          );
          if (!existingAlert) {
            addAlert({
              id: `al-fermo-${train.id}-${Date.now()}`,
              type: 'treno_fermo',
              severity: 'warning',
              message: `Treno ${train.convoglio} fermo in stazione con ritardo di ${train.delayMinutes} minuti.`,
              trainId: train.id,
              stationId: train.currentStationId ?? undefined,
              timestamp: Date.now(),
              acknowledged: false,
            });
          }
        }
      });
    }, 15000);

    return () => clearInterval(interval);
  }, [addAlert]);

  return { stationCount: STATION_IDS.length, trainCount: trains.length };
}
