import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { useState } from 'react';
import { Building2, Wrench } from 'lucide-react';

export default function StationsPage() {
  const { stations, trains } = useRailwayStore();
  const [search, setSearch] = useState('');
  const [selectedStationId, setSelectedStationId] = useState<string | null>(null);

  const filteredStations = stations.filter(s => 
    s.name.toLowerCase().includes(search.toLowerCase()) || 
    s.code.toLowerCase().includes(search.toLowerCase())
  );

  const selectedStation = stations.find(s => s.id === selectedStationId);

  // Trova i treni attesi per la stazione selezionata (UC4)
  const getExpectedTrains = (stationId: string) => {
    return trains.filter(t => 
      t.nextStationId === stationId || t.currentStationId === stationId
    ).map(t => ({
      ...t,
      type: t.currentStationId === stationId ? 'fermo/partenza' : 'arrivo'
    }));
  };

  return (
    <div className="flex flex-col gap-6 animate-fade-in h-full">
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 h-full">
        {/* Stations List */}
        <Card title="Elenco Stazioni" className="col-span-1">
          <div className="mb-4">
            <input 
              type="text" 
              placeholder="Cerca stazione per nome o codice..." 
              className="input-field w-full max-w-md"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Codice</th>
                  <th>Nome</th>
                  <th>Stato</th>
                  <th>Ultimo Heartbeat</th>
                  <th>Azioni</th>
                </tr>
              </thead>
              <tbody>
                {filteredStations.map(station => (
                  <tr key={station.id} className={selectedStationId === station.id ? 'bg-primary/10' : ''}>
                    <td className="font-mono text-sm">{station.code}</td>
                    <td className="font-medium">{station.name}</td>
                    <td>
                      <Badge type={
                        station.status === 'operativa' ? 'success' : 
                        station.status === 'manutenzione' ? 'warning' : 'danger'
                      }>
                        {station.status.toUpperCase()}
                      </Badge>
                    </td>
                    <td className="text-sm font-mono">
                      {/* Un trattino, non l'ora corrente: se lastHeartbeat è nullo la
                          stazione non ha MAI battuto e scriverci l'orario di adesso
                          faceva sembrare viva una stazione spenta. */}
                      {station.lastHeartbeat
                        ? new Date(station.lastHeartbeat).toLocaleTimeString()
                        : '—'}
                    </td>
                    <td>
                      <button 
                        className="btn btn-outline text-xs py-1"
                        onClick={() => setSelectedStationId(station.id)}
                      >
                        Dettagli & Treni Attesi
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>

        {/* Station Details & Expected Trains (UC4) */}
        <Card title="Pannello Stazione" className="col-span-1 flex flex-col">
          {!selectedStation ? (
            <div className="text-center text-muted p-8 flex-1 flex flex-col items-center justify-center">
              <Building2 size={48} className="mb-4 opacity-20" />
              <p>Seleziona una stazione per visualizzare i treni in arrivo o in partenza.</p>
            </div>
          ) : (
            <div className="animate-fade-in flex-1 flex flex-col">
              <div className="mb-6">
                <h3 className="text-xl mb-1">{selectedStation.name}</h3>
                <p className="text-sm font-mono text-muted mb-3">Codice: {selectedStation.code}</p>
                <Badge type={selectedStation.status === 'operativa' ? 'success' : 'danger'}>
                  Stato: {selectedStation.status.toUpperCase()}
                </Badge>
                
                {selectedStation.faultDescription && (
                  <div className="mt-3 p-3 bg-danger/10 border border-danger/30 rounded text-danger text-sm flex gap-2 items-start">
                    <Wrench size={16} className="mt-0.5 flex-shrink-0" />
                    <span>{selectedStation.faultDescription}</span>
                  </div>
                )}
              </div>

              <h4 className="text-sm font-semibold text-muted uppercase tracking-wider mb-4 border-b border-border-color pb-2">
                Treni Attesi (UC4)
              </h4>
              
              <div className="flex flex-col gap-3 overflow-y-auto">
                {getExpectedTrains(selectedStation.id).length === 0 ? (
                  <p className="text-sm text-muted italic">Nessun treno atteso al momento.</p>
                ) : (
                  getExpectedTrains(selectedStation.id).map(train => (
                    <div key={train.id} className="p-3 bg-black/20 border border-border-color rounded-md flex flex-col gap-2">
                      <div className="flex justify-between items-center">
                        <span className="font-bold font-mono text-primary">{train.id}</span>
                        <Badge type={train.type === 'arrivo' ? 'info' : 'warning'}>
                          {train.type.toUpperCase()}
                        </Badge>
                      </div>
                      
                      <div className="flex justify-between text-sm">
                        <span className="text-muted">Stato:</span>
                        <span className="capitalize">{train.status.replace('_', ' ')}</span>
                      </div>
                      
                      {train.delayMinutes > 0 && (
                        <div className="flex justify-between text-sm text-danger font-medium">
                          <span>Ritardo:</span>
                          <span>+{train.delayMinutes} min</span>
                        </div>
                      )}
                      
                      {train.arrivalTime && train.type === 'arrivo' && (
                        <div className="flex justify-between text-sm">
                          <span className="text-muted">ETA:</span>
                          <span className="font-mono">{new Date(train.arrivalTime).toLocaleTimeString()}</span>
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
