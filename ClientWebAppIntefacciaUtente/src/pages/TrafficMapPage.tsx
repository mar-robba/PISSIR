import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { useState } from 'react';
import { Train as TrainType, Station } from '../types';

export default function TrafficMapPage() {
  const { stations, trains, routes } = useRailwayStore();
  const [selectedTrain, setSelectedTrain] = useState<TrainType | null>(null);
  const [selectedStation, setSelectedStation] = useState<Station | null>(null);

  const viewBox = "0 0 600 800";

  const getCoordinates = (stationId: string) => {
    switch (stationId) {
      case 'S1': return { x: 150, y: 150 }; // Milano
      case 'S2': return { x: 250, y: 250 }; // Bologna
      case 'S3': return { x: 230, y: 350 }; // Firenze
      case 'S4': return { x: 300, y: 500 }; // Roma
      case 'S5': return { x: 380, y: 650 }; // Napoli
      default: return { x: 300, y: 400 };
    }
  };

  // Render stations
  const renderStations = () => {
    return stations.map(station => {
      const isFaulty = station.status === 'guasta' || station.status === 'offline';
      const isSelected = selectedStation?.id === station.id;
      const coords = getCoordinates(station.id);
      
      return (
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

  // Render connections between stations
  const renderConnections = () => {
    const drawnLines = new Set<string>();
    const lines: React.ReactNode[] = [];
    
    routes.forEach(route => {
      if (!route.active || !route.stationIds || route.stationIds.length < 2) return;
      
      for (let i = 0; i < route.stationIds.length - 1; i++) {
        const fromId = route.stationIds[i];
        const toId = route.stationIds[i + 1];
        const key = [fromId, toId].sort().join('-');
        
        if (!drawnLines.has(key)) {
          drawnLines.add(key);
          const pos1 = getCoordinates(fromId);
          const pos2 = getCoordinates(toId);
          lines.push(
            <line
              key={key}
              x1={pos1.x}
              y1={pos1.y}
              x2={pos2.x}
              y2={pos2.y}
              stroke="#cbd5e1"
              strokeWidth="4"
              strokeLinecap="round"
            />
          );
        }
      }
    });
    return lines;
  };

  // Render trains
  const renderTrains = () => {
    return trains.filter(t => t.status === 'in_viaggio' || t.status === 'in_ritardo').map(train => {
      const prevStation = stations.find(s => s.id === train.previousStationId);
      const nextStation = stations.find(s => s.id === train.nextStationId);
      
      if (!prevStation || !nextStation) return null;
      
      // Calculate position based on progressPercent (0 to 100)
      const pct = train.progressPercent / 100;
      const prevCoords = getCoordinates(prevStation.id);
      const nextCoords = getCoordinates(nextStation.id);
      const x = prevCoords.x + (nextCoords.x - prevCoords.x) * pct;
      const y = prevCoords.y + (nextCoords.y - prevCoords.y) * pct;
      
      const isDelayed = train.delayMinutes > 0;
      const isSelected = selectedTrain?.id === train.id;

      return (
        <g 
          key={train.id}
          transform={`translate(${x}, ${y})`}
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
            {train.convoglio}
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
            </defs>
            <rect x="0" y="0" width="2000" height="2000" fill="url(#grid)" />
            
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
              <h3 className="text-xl mb-1">{selectedTrain.convoglio}</h3>
              
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
