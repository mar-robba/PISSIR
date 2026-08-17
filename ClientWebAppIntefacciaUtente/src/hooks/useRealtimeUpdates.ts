//   Perché è organizzato così
//hooks indica che lì dentro ci sono funzioni che “agganciano” comportamenti a componenti React

import { useEffect } from 'react';
import { useRailwayStore } from '../store/railwayStore';
import { wsClient } from '../api/websocketClient';
import { mapBackendStatus, mapApiStation, mapApiTrain } from '../api/apiClient';

/**
 * Converte in millisecondi il timestamp ISO che viaggia negli eventi della Centrale.
 * Se manca o non è leggibile si ripiega sull'ora di arrivo, ma il caso normale è che
 * l'ora ci sia: è quella in cui il fatto è successo davvero, e per gli eventi rimandati
 * dal buffer di una stazione dopo un'interruzione (SV02) può essere di parecchi minuti
 * prima. Scriverci Date.now() schiacciava tutti quegli eventi sull'istante del rientro.
 */
const istanteEvento = (timestamp: unknown): number => {
  if (typeof timestamp === 'string') {
    const ms = new Date(timestamp).getTime();
    if (!Number.isNaN(ms)) return ms;
  }
  return Date.now();
};

export function useRealtimeUpdates() {
  // Ogni azione si prende con un selettore: prendere lo store intero significa
  // sottoscriversi a QUALUNQUE cambiamento, e siccome questo hook sta in RailwayApp
  // (il componente che contiene tutte le rotte) ogni frame di telemetria e ogni battito
  // ri-renderizzavano l'intera applicazione, mappa compresa.
  // Le azioni di Zustand sono riferimenti stabili, quindi così non si ridisegna nulla.
  const updateTrain = useRailwayStore((s) => s.updateTrain);
  const updateStation = useRailwayStore((s) => s.updateStation);
  const addAlert = useRailwayStore((s) => s.addAlert);
  const setKpi = useRailwayStore((s) => s.setKpi);
  const setTrains = useRailwayStore((s) => s.setTrains);
  const setStations = useRailwayStore((s) => s.setStations);

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
          // La stazione corrente si azzera davvero quando il treno riparte: il frame
          // manda stringa vuota e quel campo DEVE tornare null, altrimenti il convoglio
          // resta "fermo a Milano Centrale" nel pannello di dettaglio e continua a
          // comparire fra i treni attesi della stazione da cui è appena uscito.
          currentStationId: originStationId,
          // previousStationId invece si tiene: alla mappa serve sapere da dove è partito
          // per disegnarlo sull'arco giusto.
          ...(originStationId ? { previousStationId: originStationId } : {}),
          ...(destinationStationId ? { nextStationId: destinationStationId } : {}),
          // Al capolinea il twin inverte la marcia: senza questa riga il verso della corsa
          // lo portava solo lo snapshot, e per una decina di secondi il pannello annunciava
          // ancora "corsa di andata" mentre il convoglio stava già tornando indietro.
          ...(data.direzione ? { direction: data.direzione === 'ritorno' ? 'ritorno' : 'andata' } : {}),
          lastUpdate: istanteEvento(data.timestamp)
        });
      }
    });

    const unsubHeartbeat = wsClient.subscribe('HEARTBEAT', (data) => {
      if (data.stationId) {
        updateStation(data.stationId, {
          status: data.status ?? 'operativa',
          lastHeartbeat: istanteEvento(data.timestamp)
        });
      }
    });

    // Cambio di stato deciso dalla Centrale (watchdog, presa in carico di un allarme,
    // invio della squadra). Evento distinto dall'HEARTBEAT perché NON è un battito: la
    // stazione dichiarata OFFLINE è per definizione quella che non ne manda più, quindi
    // qui l'ora dell'ultimo heartbeat non si tocca.
    const unsubStationStatus = wsClient.subscribe('STATION_STATUS', (data) => {
      if (data.stationId && data.status) {
        // "status" arriva già nel vocabolario del frontend (operativa/guasta/
        // manutenzione/offline), come nell'evento HEARTBEAT.
        updateStation(data.stationId, {
          status: data.status,
          operatorsDispatched: data.status === 'manutenzione',
        });
      }
    });

    const unsubAlert = wsClient.subscribe('ALERT', (data) => {
      // "risolto" dice se il guasto è aperto o chiuso: senza quel campo la chiusura di un
      // guasto arrivava alla schermata identica a un guasto nuovo, e l'operatore vedeva
      // comparire un allarme rosso proprio quando la condizione era rientrata.
      const risolto = data.risolto === true;
      addAlert({
        id: data.id || `al-${Date.now()}`,
        type: data.type || 'generico',
        severity: data.severity || 'warning',
        message: data.message || 'Allarme dal campo',
        trainId: data.trainId,
        stationId: data.stationId,
        // Terza sorgente possibile: l'arco di rete reso non percorribile da un convoglio
        // guasto in linea (RF02.1.2.2.2).
        trackSegmentId: data.trattaId,
        timestamp: istanteEvento(data.timestamp),
        acknowledged: risolto,
        resolvedAt: data.timestampRisoluzione ? istanteEvento(data.timestampRisoluzione) : undefined,
        // La presa in carico viaggia sullo stesso evento ALERT: così l'altro operatore vede
        // che l'allarme è già di qualcuno senza dover ricaricare la pagina.
        takenBy: data.operatore ?? undefined
      });
    });

    // Fotografia periodica della rete (RF02.6.3): i KPI arrivano già calcolati dalla
    // Centrale — la dashboard non se li rifà più da sola con formule diverse — e gli
    // elenchi di treni e stazioni rimettono a posto lo stato locale se nel frattempo si
    // è perso un evento in tempo reale (la WebSocket, riconnettendosi, non recupera nulla).
    const unsubSnapshot = wsClient.subscribe('SNAPSHOT', (data) => {
      if (data.dashboard) setKpi(data.dashboard);
      if (Array.isArray(data.treni)) setTrains(data.treni.map(mapApiTrain));
      if (Array.isArray(data.stazioni)) setStations(data.stazioni.map(mapApiStation));
    });

    // Cleanup: disiscrizione e chiusura
    return () => {
      unsubTelemetry();
      unsubHeartbeat();
      unsubStationStatus();
      unsubAlert();
      unsubSnapshot();
      wsClient.disconnect();
    };
  }, [updateTrain, updateStation, addAlert, setKpi, setTrains, setStations]);
}
