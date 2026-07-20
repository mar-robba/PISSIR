//   Perché è organizzato così
//hooks indica che lì dentro ci sono funzioni che “agganciano” comportamenti a componenti React

import { useEffect } from 'react';
import { useRailwayStore } from '../store/railwayStore';
import { wsClient } from '../api/websocketClient';
import { mapBackendStatus } from '../api/apiClient';

export function useRealtimeUpdates() {
  //costanti definite da un costruttore chiamato nelle import li sopra
  const { updateTrain, updateStation, addAlert, addTransit, acknowledgeAlert } = useRailwayStore();

  useEffect(() => {
    // Connetti il WebSocket
    wsClient.connect();

    // Sottoscrizione ai vari topic/eventi
    const unsubTelemetry = wsClient.subscribe('TELEMETRY', (data) => {
      // Ogni frame contiene anche la stazione da cui il treno è partito e quella
      // verso cui è diretto: servono alla mappa per scegliere l'arco corretto.
      if (data.trainId) {
        const originStationId = typeof data.stazioneCorrente === 'string' && data.stazioneCorrente
          ? data.stazioneCorrente
          : null;
        const destinationStationId = typeof data.prossimaStazione === 'string' && data.prossimaStazione
          ? data.prossimaStazione
          : null;

        updateTrain(data.trainId, {
          progressPercent: data.progressPercent ?? undefined,
          status: mapBackendStatus(data.stato || data.status) as any,
          delayMinutes: data.delayMinutes ?? undefined,
          // Non sovrascriviamo gli estremi già noti con stringhe vuote.
          ...(originStationId ? {
            currentStationId: originStationId,
            previousStationId: originStationId,
          } : {}),
          ...(destinationStationId ? { nextStationId: destinationStationId } : {}),
          lastUpdate: Date.now()
        });
      }
    });

    const unsubHeartbeat = wsClient.subscribe('HEARTBEAT', (data) => {
      if (data.stationId) {
        updateStation(data.stationId, {
          status: data.status ?? 'operativa',
          lastHeartbeat: Date.now()
        });
      }
    });

    const unsubAlert = wsClient.subscribe('ALERT', (data) => {
      addAlert({
        id: data.id || `al-${Date.now()}`,
        type: data.type || 'generico',
        severity: data.severity || 'warning',
        message: data.message || 'Allarme dal campo',
        trainId: data.trainId,
        stationId: data.stationId,
        timestamp: Date.now(),
        acknowledged: false
      });
    });

    const unsubTransit = wsClient.subscribe('TRANSIT', (data) => {
      addTransit({
        id: data.id || `tr-${Date.now()}`,
        trainId: data.trainId,
        stationId: data.stationId,
        type: data.type === 'uscita' ? 'uscita' : 'ingresso',
        timestamp: Date.now(),
        delayed: data.delayMinutes > 0,
        delayMinutes: data.delayMinutes
      });
    });

    // Cleanup: disiscrizione e chiusura
    return () => {
      unsubTelemetry();
      unsubHeartbeat();
      unsubAlert();
      unsubTransit();
      wsClient.disconnect();
    };
  }, [updateTrain, updateStation, addAlert, addTransit, acknowledgeAlert]);
}
