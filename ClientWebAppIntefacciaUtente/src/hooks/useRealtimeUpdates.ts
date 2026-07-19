import { useEffect } from 'react';
import { useRailwayStore } from '../store/railwayStore';
import { wsClient } from '../api/websocketClient';
import { mapBackendStatus } from '../api/apiClient';

export function useRealtimeUpdates() {
  const { updateTrain, updateStation, addAlert, addTransit, acknowledgeAlert } = useRailwayStore();

  useEffect(() => {
    // Connetti il WebSocket
    wsClient.connect();

    // Sottoscrizione ai vari topic/eventi
    const unsubTelemetry = wsClient.subscribe('TELEMETRY', (data) => {
      // data.trainId, data.velocita, data.latitudine, data.longitudine, ecc.
      if (data.trainId) {
        updateTrain(data.trainId, {
          progressPercent: data.progressPercent ?? undefined,
          status: mapBackendStatus(data.stato || data.status) as any,
          delayMinutes: data.delayMinutes ?? undefined,
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
