import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { useState } from 'react';
import { Train as TrainType, Station } from '../types';

export default function TrafficMapPage() {
  const { stations, trains } = useRailwayStore();
  const [selectedTrain, setSelectedTrain] = useState<TrainType | null>(null);
  const [selectedStation, setSelectedStation] = useState<Station | null>(null);

  // Adjusted viewBox dimensions for zoom
  const viewBox = "80 120 540 360";

  // Render stations
  const renderStations = () => {
    return stations.map(station => {
      const isFaulty = station.status === 'guasta' || station.status === 'offline';
      const isSelected = selectedStation?.id === station.id;
      
      return (
        <g 
          key={station.id} 
          transform={`translate(${station.coordinates.x}, ${station.coordinates.y})`}
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
    // A simple MST or hardcoded connections based on routes could be here.
    // For demo, we just connect based on coordinates loosely or draw some main lines.
    const lines = [
      { from: 'st-TO', to: 'st-VC' },
      { from: 'st-VC', to: 'st-NO' },
      { from: 'st-NO', to: 'st-MI' },
      { from: 'st-MI', to: 'st-PC' },
      { from: 'st-TO', to: 'st-AT' },
      { from: 'st-AT', to: 'st-AL' },
      { from: 'st-AL', to: 'st-GE' },
      { from: 'st-AL', to: 'st-PV' },
      { from: 'st-PV', to: 'st-MI' },
      { from: 'st-TO', to: 'st-CN' }
    ];

    return lines.map((line, idx) => {
      const st1 = stations.find(s => s.id === line.from);
      const st2 = stations.find(s => s.id === line.to);
      
      if (!st1 || !st2) return null;
      
      return (
        <line
          key={`line-${idx}`}
          x1={st1.coordinates.x}
          y1={st1.coordinates.y}
          x2={st2.coordinates.x}
          y2={st2.coordinates.y}
          stroke="#cbd5e1"
          strokeWidth="4"
          strokeLinecap="round"
        />
      );
    });
  };

  // Render trains
  const renderTrains = () => {
    return trains.filter(t => t.status === 'in_viaggio' || t.status === 'in_ritardo').map(train => {
      const prevStation = stations.find(s => s.id === train.previousStationId);
      const nextStation = stations.find(s => s.id === train.nextStationId);
      
      if (!prevStation || !nextStation) return null;
      
      // Calculate position based on progressPercent (0 to 100)
      const pct = train.progressPercent / 100;
      const x = prevStation.coordinates.x + (nextStation.coordinates.x - prevStation.coordinates.x) * pct;
      const y = prevStation.coordinates.y + (nextStation.coordinates.y - prevStation.coordinates.y) * pct;
      
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
