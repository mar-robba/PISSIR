import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { useMemo, useState } from 'react';
import { Route, Station, TrackSegment, Train as TrainType } from '../types';

type DiagramPoint = { x: number; y: number };
type GraphEdge = { fromId: string; toId: string; key: string; doppioSenso: boolean };

const MAP_WIDTH = 600;
const MAP_HEIGHT = 800;
const MAP_PADDING = 70;
// Quanto la linea si ferma prima del centro della stazione: la punta della freccia
// deve restare fuori dal cerchio (r 12, 16 se selezionata), se no ci finisce sotto.
const STATION_EDGE_GAP = 22;

/**
 * Restituisce tutti gli archi della rete, senza duplicati e senza dipendere
 * dalle coordinate GPS. Le tratte fisiche hanno priorità; gli itinerari attivi
 * completano il grafo quando non esiste ancora una tratta fisica corrispondente.
 *
 * <p>Le tratte sono ORIENTATE (il server rifiuta un itinerario che usa A-&gt;B se
 * in tabella c'è solo B-&gt;A), quindi ogni arco tiene il verso in cui si può
 * percorrere. Le due righe A-&gt;B e B-&gt;A restano un solo segmento sulla mappa,
 * marcato come doppio senso: due linee sovrapposte sarebbero indistinguibili.
 */
function buildGraphEdges(
  stations: Station[],
  routes: Route[],
  trackSegments: TrackSegment[],
): GraphEdge[] {
  const stationIds = new Set(stations.map(({ id }) => id));
  const edges = new Map<string, GraphEdge>();

  const addEdge = (fromId: string, toId: string) => {
    if (fromId === toId || !stationIds.has(fromId) || !stationIds.has(toId)) return;

    // La chiave ignora il verso, così i due sensi di marcia finiscono sulla stessa
    // riga; il verso vero e proprio resta in fromId/toId e in doppioSenso.
    const key = [fromId, toId].sort().join('::');
    const esistente = edges.get(key);
    if (!esistente) {
      edges.set(key, { fromId, toId, key, doppioSenso: false });
      return;
    }
    // Stesso arco già visto ma partendo dall'altra stazione: si percorre in entrambi i sensi.
    if (esistente.fromId !== fromId) esistente.doppioSenso = true;
  };

  trackSegments.forEach(({ departureStationId, arrivalStationId }) => {
    addEdge(departureStationId, arrivalStationId);
  });

  routes.filter(({ active }) => active).forEach(({ stationIds }) => {
    for (let index = 0; index < stationIds.length - 1; index += 1) {
      addEdge(stationIds[index], stationIds[index + 1]);
    }
  });

  return [...edges.values()];
}

/**
 * Layout force-directed deterministico: nodi collegati si attraggono, tutti i
 * nodi si respingono. Di conseguenza una stazione nuova compare sempre e viene
 * posizionata in funzione degli archi della rete, non di latitudine/longitudine.
 */
function calculateGraphLayout(stations: Station[], edges: GraphEdge[]): Map<string, DiagramPoint> {
  const orderedStations = [...stations].sort((a, b) => a.id.localeCompare(b.id));
  const positions = new Map<string, DiagramPoint>();
  const count = orderedStations.length;

  if (count === 0) return positions;

  const centerX = MAP_WIDTH / 2;
  const centerY = MAP_HEIGHT / 2;
  const initialRadius = Math.min(230, 70 + count * 15);

  // Posizione iniziale stabile basata sull'ID, poi raffinata dalle forze del grafo.
  orderedStations.forEach((station, index) => {
    const angle = (2 * Math.PI * index) / count - Math.PI / 2;
    positions.set(station.id, {
      x: centerX + initialRadius * Math.cos(angle),
      y: centerY + initialRadius * Math.sin(angle),
    });
  });

  const desiredEdgeLength = 145;
  const repulsion = 15000;

  for (let iteration = 0; iteration < 140; iteration += 1) {
    const displacement = new Map<string, DiagramPoint>(
      orderedStations.map(({ id }) => [id, { x: 0, y: 0 }]),
    );

    // Repulsione tra ciascuna coppia: evita sovrapposizioni anche per nodi isolati.
    for (let first = 0; first < count; first += 1) {
      for (let second = first + 1; second < count; second += 1) {
        const firstId = orderedStations[first].id;
        const secondId = orderedStations[second].id;
        const firstPoint = positions.get(firstId)!;
        const secondPoint = positions.get(secondId)!;
        let dx = firstPoint.x - secondPoint.x;
        let dy = firstPoint.y - secondPoint.y;
        let distanceSquared = dx * dx + dy * dy;

        // Direzione deterministica se i due punti coincidono esattamente.
        if (distanceSquared < 0.01) {
          dx = first % 2 === 0 ? 1 : -1;
          dy = second % 2 === 0 ? 1 : -1;
          distanceSquared = 2;
        }

        const distance = Math.sqrt(distanceSquared);
        const force = repulsion / distanceSquared;
        const firstMove = displacement.get(firstId)!;
        const secondMove = displacement.get(secondId)!;
        firstMove.x += (dx / distance) * force;
        firstMove.y += (dy / distance) * force;
        secondMove.x -= (dx / distance) * force;
        secondMove.y -= (dy / distance) * force;
      }
    }

    // Attrazione sugli archi: mantiene vicine le stazioni realmente collegate.
    edges.forEach(({ fromId, toId }) => {
      const from = positions.get(fromId)!;
      const to = positions.get(toId)!;
      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const distance = Math.max(1, Math.hypot(dx, dy));
      const force = (distance - desiredEdgeLength) * 0.075;
      const fromMove = displacement.get(fromId)!;
      const toMove = displacement.get(toId)!;
      fromMove.x += (dx / distance) * force;
      fromMove.y += (dy / distance) * force;
      toMove.x -= (dx / distance) * force;
      toMove.y -= (dy / distance) * force;
    });

    // Raffreddamento progressivo: il layout converge invece di oscillare.
    const cooling = 0.22 * (1 - iteration / 140) + 0.03;
    orderedStations.forEach(({ id }) => {
      const point = positions.get(id)!;
      const move = displacement.get(id)!;
      positions.set(id, {
        x: Math.max(MAP_PADDING, Math.min(MAP_WIDTH - MAP_PADDING, point.x + move.x * cooling)),
        y: Math.max(MAP_PADDING, Math.min(MAP_HEIGHT - MAP_PADDING, point.y + move.y * cooling)),
      });
    });
  }

  return positions;
}

export default function TrafficMapPage() {
  const { stations, trains, routes, trackSegments } = useRailwayStore();
  const [selectedTrain, setSelectedTrain] = useState<TrainType | null>(null);
  const [selectedStation, setSelectedStation] = useState<Station | null>(null);

  // Spazio interno del diagramma SVG; non ha alcuna semantica geografica.
  const viewBox = `0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`;
  const graphEdges = useMemo(
    () => buildGraphEdges(stations, routes, trackSegments),
    [stations, routes, trackSegments],
  );
  const graphLayout = useMemo(
    () => calculateGraphLayout(stations, graphEdges),
    [stations, graphEdges],
  );
  const getCoordinates = (stationId: string): DiagramPoint =>
    graphLayout.get(stationId) ?? { x: MAP_WIDTH / 2, y: MAP_HEIGHT / 2 };

  // Crea un gruppo SVG per ogni stazione. Il gruppo viene traslato nel punto
  // assegnato e, al click, diventa la stazione mostrata nel pannello laterale.
  const renderStations = () => {
    return stations.map(station => {
      // Rosso per guasta/offline; verde per gli altri stati operativi.
      const isFaulty = station.status === 'guasta' || station.status === 'offline';
      const isSelected = selectedStation?.id === station.id;
      const coords = getCoordinates(station.id);
      
      return (
        // Il gruppo SVG è cliccabile e contiene simbolo e codice della stazione.
        <g 
          key={station.id} 
          transform={`translate(${coords.x}, ${coords.y})`}
          onClick={() => { setSelectedStation(station); setSelectedTrain(null); }}
          className="cursor-pointer"
        >
          <circle 
            r={isSelected ? 16 : 12} 
            fill={isFaulty ? '#ef4444' : '#10b981'} 
            stroke={isSelected ? '#334155' : '#ffffff'}
            strokeWidth="3"
            className="transition-all duration-300"
          />
          {/* Il secondo cerchio pulsante rende immediatamente visibile un guasto. */}
          {isFaulty && (
            <circle r={18} fill="none" stroke="#ef4444" strokeWidth="2" className="animate-ping opacity-75" />
          )}
          <text 
            y={28} 
            textAnchor="middle" 
            fill="#334155" 
            className="text-xs font-semibold select-none"
            style={{ textShadow: '0 1px 2px rgba(255,255,255,0.9)' }}
          >
            {station.code}
          </text>
        </g>
      );
    });
  };

  // Disegna gli archi usati anche dal layout automatico delle stazioni. La freccia
  // indica il verso percorribile: una punta sola per il senso unico, una punta per
  // parte quando la tratta esiste in tabella in entrambi i versi.
  const renderConnections = () => {
    return graphEdges.map(({ fromId, toId, key, doppioSenso }) => {
      const from = getCoordinates(fromId);
      const to = getCoordinates(toId);
      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const distanza = Math.max(1, Math.hypot(dx, dy));
      // Su stazioni molto vicine si accorcia meno, se no la linea si annullerebbe.
      const scarto = Math.min(STATION_EDGE_GAP, distanza / 2 - 1);
      const offsetX = (dx / distanza) * scarto;
      const offsetY = (dy / distanza) * scarto;

      return (
        <line
          key={key}
          x1={from.x + offsetX}
          y1={from.y + offsetY}
          x2={to.x - offsetX}
          y2={to.y - offsetY}
          stroke="#cbd5e1"
          strokeWidth="4"
          strokeLinecap="round"
          markerEnd="url(#punta-tratta)"
          markerStart={doppioSenso ? 'url(#punta-tratta)' : undefined}
        />
      );
    });
  };

  // I treni in viaggio sono interpolati sull'arco; quelli fermi vengono mostrati
  // nel nodo della stazione corrente, così nessun convoglio localizzabile sparisce.
  const renderTrains = () => {
    return trains.filter(train => train.status !== 'soppresso').map(train => {
      const prevStation = stations.find(s => s.id === train.previousStationId);
      const nextStation = stations.find(s => s.id === train.nextStationId);
      const currentStation = stations.find(s => s.id === train.currentStationId);
      const isMoving = train.status === 'in_viaggio' || train.status === 'in_ritardo';

      let position: DiagramPoint | null = null;
      if (isMoving && prevStation && nextStation) {
        // Interpolazione lineare: 0% coincide con la stazione precedente,
        // 100% con la successiva. Il clamp protegge la vista da frame incompleti.
        const pct = Math.max(0, Math.min(100, train.progressPercent)) / 100;
        const prevCoords = getCoordinates(prevStation.id);
        const nextCoords = getCoordinates(nextStation.id);
        position = {
          x: prevCoords.x + (nextCoords.x - prevCoords.x) * pct,
          y: prevCoords.y + (nextCoords.y - prevCoords.y) * pct,
        };
      } else if (currentStation) {
        position = getCoordinates(currentStation.id);
      }

      // Un treno senza stazione corrente né estremi di tratta non è localizzabile.
      if (!position) return null;
      
      const isDelayed = train.delayMinutes > 0 || train.status === 'in_ritardo';
      const isSelected = selectedTrain?.id === train.id;

      return (
        // Il gruppo SVG è cliccabile e contiene simbolo e nome del treno.
        <g 
          key={train.id}
          transform={`translate(${position.x}, ${position.y})`}
          onClick={() => { setSelectedTrain(train); setSelectedStation(null); }}
          className="cursor-pointer transition-all duration-1000"
        >
          <rect 
            x={-10} 
            y={-10} 
            width={20} 
            height={20} 
            rx={4}
            fill={isDelayed ? '#f59e0b' : '#3b82f6'} 
            stroke={isSelected ? '#334155' : '#ffffff'}
            strokeWidth="2"
          />
          <text 
            y={-15} 
            textAnchor="middle" 
            fill={isDelayed ? '#b45309' : '#1d4ed8'} 
            className="text-[10px] font-mono font-bold select-none"
            style={{ textShadow: '0 1px 2px rgba(255,255,255,0.9)' }}
          >
            {train.id}
          </text>
        </g>
      );
    });
  };

  return (
    <div className="flex flex-col h-full gap-4 animate-fade-in">
      <div className="flex gap-4 flex-1 min-h-[700px]">
        {/* Main Map Area */}
        <Card className="flex-1 overflow-hidden p-0 relative flex items-center justify-center bg-slate-50/50" title="">
          <svg width="100%" height="100%" viewBox={viewBox} preserveAspectRatio="xMidYMid meet">
            <defs>
              <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
                <path d="M 40 0 L 0 0 0 40" fill="none" stroke="rgba(0,0,0,0.04)" strokeWidth="1" />
              </pattern>
              {/* Punta di freccia delle tratte. orient="auto-start-reverse" fa sì che
                  lo stesso marker si giri da solo quando è usato come markerStart,
                  così il doppio senso è una linea con due punte e non due linee.
                  markerUnits="userSpaceOnUse" la tiene della stessa misura a
                  prescindere dallo spessore della linea. */}
              <marker
                id="punta-tratta"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="13"
                markerHeight="13"
                markerUnits="userSpaceOnUse"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" />
              </marker>
            </defs>
            <rect x="0" y="0" width="2000" height="2000" fill="url(#grid)" />
            
            {/* Ordine di rendering: linee sotto, poi stazioni e infine treni in primo piano. */}
            {renderConnections()}
            {renderStations()}
            {renderTrains()}
          </svg>
          <div className="absolute top-4 left-4 flex gap-2">
            <Badge type="success">Stazione Operativa</Badge>
            <Badge type="danger">Stazione Guasta</Badge>
            <Badge type="info">Treno Regolare</Badge>
            <Badge type="warning">Treno in Ritardo</Badge>
          </div>
        </Card>

        {/* Side Panel for Details */}
        <Card className="w-80 flex flex-col gap-4 overflow-y-auto" title="Dettagli">
          {!selectedTrain && !selectedStation ? (
            <div className="text-center text-muted p-8">
              <p>Seleziona un treno o una stazione sulla mappa per visualizzare i dettagli.</p>
            </div>
          ) : selectedStation ? (
            <div className="animate-fade-in">
              <h3 className="text-xl mb-1">{selectedStation.name}</h3>
              <p className="text-sm font-mono mb-4 text-muted">Codice: {selectedStation.code}</p>
              
              <div className="mb-4">
                <Badge type={selectedStation.status === 'operativa' ? 'success' : 'danger'}>
                  {selectedStation.status.toUpperCase()}
                </Badge>
              </div>

              {selectedStation.faultDescription && (
                <div className="p-3 bg-danger/10 border border-danger/30 rounded text-danger text-sm mb-4">
                  <strong>Anomalia:</strong> {selectedStation.faultDescription}
                </div>
              )}

              <div className="space-y-2 text-sm">
                <div className="flex justify-between border-b border-border-color py-2">
                  <span className="text-muted">Binari</span>
                  <span className="font-bold">{selectedStation.platforms}</span>
                </div>
                <div className="flex justify-between border-b border-border-color py-2">
                  <span className="text-muted">Ultimo Heartbeat</span>
                  <span className="font-bold">{new Date(selectedStation.lastHeartbeat).toLocaleTimeString()}</span>
                </div>
              </div>
            </div>
          ) : selectedTrain ? (
            <div className="animate-fade-in">
              <h3 className="text-xl mb-1">{selectedTrain.id}</h3>
              
              <div className="mb-4 flex gap-2">
                <Badge type={selectedTrain.status === 'in_viaggio' ? 'info' : selectedTrain.status === 'in_ritardo' ? 'warning' : 'neutral'}>
                  {selectedTrain.status.toUpperCase().replace('_', ' ')}
                </Badge>
                {selectedTrain.delayMinutes > 0 && (
                  <Badge type="danger">+{selectedTrain.delayMinutes} min</Badge>
                )}
              </div>

              <div className="space-y-2 text-sm">
                <div className="flex justify-between border-b border-border-color py-2">
                  <span className="text-muted">Da (Ultima Stazione)</span>
                  <span className="font-bold">
                    {stations.find(s => s.id === selectedTrain.previousStationId)?.name || '-'}
                  </span>
                </div>
                <div className="flex justify-between border-b border-border-color py-2">
                  <span className="text-muted">Verso (Prossima)</span>
                  <span className="font-bold">
                    {stations.find(s => s.id === selectedTrain.nextStationId)?.name || '-'}
                  </span>
                </div>
                <div className="flex justify-between border-b border-border-color py-2">
                  <span className="text-muted">Progresso Tratta</span>
                  <span className="font-bold">{Math.round(selectedTrain.progressPercent)}%</span>
                </div>
                <div className="flex justify-between border-b border-border-color py-2">
                  <span className="text-muted">Passeggeri</span>
                  <span className="font-bold">{selectedTrain.passengers}</span>
                </div>
                {selectedTrain.arrivalTime && (
                  <div className="flex justify-between border-b border-border-color py-2">
                    <span className="text-muted">Arrivo Previsto</span>
                    <span className="font-bold">{new Date(selectedTrain.arrivalTime).toLocaleTimeString()}</span>
                  </div>
                )}
              </div>
            </div>
          ) : null}
        </Card>
      </div>
    </div>
  );
}
