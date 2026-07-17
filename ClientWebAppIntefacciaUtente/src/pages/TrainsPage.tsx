import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { useState } from 'react';
import { Train as TrainIcon, Clock, Users, MapPin } from 'lucide-react';

export default function TrainsPage() {
  const { trains, stations, routes } = useRailwayStore();
  const [search, setSearch] = useState('');
  const [selectedTrainId, setSelectedTrainId] = useState<string | null>(null);

  const filteredTrains = trains.filter(t => 
    t.convoglio.toLowerCase().includes(search.toLowerCase())
  );

  const selectedTrain = trains.find(t => t.id === selectedTrainId);
  const selectedTrainRoute = selectedTrain ? routes.find(r => r.id === selectedTrain.routeId) : null;

  const getStationName = (id: string | null) => {
    if (!id) return '-';
    const station = stations.find(s => s.id === id);
    return station ? station.name : id;
  };

  const getStatusBadgeType = (status: string) => {
    switch(status) {
      case 'in_viaggio': return 'success';
      case 'in_stazione': return 'info';
      case 'in_ritardo': return 'warning';
      case 'guasto': 
      case 'soppresso': return 'danger';
      default: return 'info';
    }
  };

  return (
    <div className="flex flex-col gap-6 animate-fade-in h-full">
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 h-full">
        {/* Trains List */}
        <Card title="Elenco Treni" className="col-span-2">
          <div className="mb-4">
            <input 
              type="text" 
              placeholder="Cerca treno per convoglio (es. IC 351)..." 
              className="input-field w-full max-w-md"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Convoglio</th>
                  <th>Stato</th>
                  <th>Direzione</th>
                  <th>Posizione Attuale</th>
                  <th>Ritardo</th>
                  <th>Azioni</th>
                </tr>
              </thead>
              <tbody>
                {filteredTrains.map(train => (
                  <tr key={train.id} className={selectedTrainId === train.id ? 'bg-primary/10' : ''}>
                    <td className="font-mono text-sm font-bold text-primary">
                      <div className="flex items-center gap-2">
                        <TrainIcon size={16} />
                        {train.convoglio}
                      </div>
                    </td>
                    <td>
                      <Badge type={getStatusBadgeType(train.status)}>
                        {train.status.replace('_', ' ').toUpperCase()}
                      </Badge>
                    </td>
                    <td className="capitalize">{train.direction}</td>
                    <td className="text-sm">
                      {train.status === 'in_viaggio' 
                        ? `Tra ${getStationName(train.previousStationId)} e ${getStationName(train.nextStationId)}`
                        : getStationName(train.currentStationId)
                      }
                    </td>
                    <td>
                      {train.delayMinutes > 0 ? (
                        <span className="text-danger font-bold">+{train.delayMinutes}'</span>
                      ) : (
                        <span className="text-success">In orario</span>
                      )}
                    </td>
                    <td>
                      <button 
                        className="btn btn-outline text-xs py-1"
                        onClick={() => setSelectedTrainId(train.id)}
                      >
                        Dettagli
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>

        {/* Train Details */}
        <Card title="Dettagli Treno" className="col-span-1 flex flex-col">
          {!selectedTrain ? (
            <div className="text-center text-muted p-8 flex-1 flex flex-col items-center justify-center">
              <TrainIcon size={48} className="mb-4 opacity-20" />
              <p>Seleziona un treno per visualizzarne i dettagli in tempo reale.</p>
            </div>
          ) : (
            <div className="animate-fade-in flex-1 flex flex-col">
              <div className="mb-6 flex justify-between items-start">
                <div>
                  <h3 className="text-2xl font-mono text-primary mb-1 flex items-center gap-2">
                    <TrainIcon size={24} />
                    {selectedTrain.convoglio}
                  </h3>
                  <Badge type={getStatusBadgeType(selectedTrain.status)} className="mt-2">
                    {selectedTrain.status.replace('_', ' ').toUpperCase()}
                  </Badge>
                </div>
                <div className="text-right">
                  <span className="text-xs uppercase tracking-widest text-muted block mb-1">Passeggeri</span>
                  <div className="flex items-center gap-1 justify-end text-lg">
                    <Users size={18} />
                    {selectedTrain.passengers}
                  </div>
                </div>
              </div>

              <div className="space-y-4">
                <div className="p-3 bg-black/20 border border-border-color rounded-md">
                  <h4 className="text-xs text-muted uppercase tracking-wider mb-2 flex items-center gap-1">
                    <MapPin size={14} /> Posizione
                  </h4>
                  {selectedTrain.status === 'in_viaggio' ? (
                    <div>
                      <div className="flex justify-between text-sm mb-1">
                        <span>{getStationName(selectedTrain.previousStationId)}</span>
                        <span>{getStationName(selectedTrain.nextStationId)}</span>
                      </div>
                      <div className="w-full bg-black/50 h-2 rounded-full overflow-hidden mt-2 border border-border-color">
                        <div 
                          className="bg-primary h-full transition-all duration-1000 ease-linear" 
                          style={{ width: `${selectedTrain.progressPercent}%` }}
                        />
                      </div>
                      <p className="text-center text-xs mt-2 text-muted">
                        In viaggio verso {getStationName(selectedTrain.nextStationId)}
                      </p>
                    </div>
                  ) : (
                    <p className="font-medium text-sm">
                      Fermo in stazione: <span className="text-primary">{getStationName(selectedTrain.currentStationId)}</span>
                    </p>
                  )}
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div className="p-3 bg-black/20 border border-border-color rounded-md">
                    <h4 className="text-xs text-muted uppercase tracking-wider mb-1 flex items-center gap-1">
                      <Clock size={14} /> Partenza prev.
                    </h4>
                    <p className="font-mono text-lg">
                      {selectedTrain.departureTime 
                        ? new Date(selectedTrain.departureTime).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})
                        : '--:--'}
                    </p>
                  </div>
                  <div className="p-3 bg-black/20 border border-border-color rounded-md">
                    <h4 className="text-xs text-muted uppercase tracking-wider mb-1 flex items-center gap-1">
                      <Clock size={14} /> Arrivo prev.
                    </h4>
                    <p className="font-mono text-lg">
                      {selectedTrain.arrivalTime 
                        ? new Date(selectedTrain.arrivalTime).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})
                        : '--:--'}
                    </p>
                  </div>
                </div>

                {selectedTrain.delayMinutes > 0 && (
                  <div className="p-3 bg-warning/10 border border-warning/30 rounded-md text-warning">
                    <p className="font-bold flex items-center gap-2">
                      <Clock size={18} />
                      Ritardo accumulato: {selectedTrain.delayMinutes} minuti
                    </p>
                  </div>
                )}
                
                {selectedTrain.status === 'soppresso' && (
                  <div className="p-3 bg-danger/10 border border-danger/30 rounded-md text-danger">
                    <p className="font-bold uppercase tracking-wider">Treno Soppresso</p>
                    <p className="text-sm opacity-80 mt-1">Questo convoglio è stato cancellato dall'amministrazione e non proseguirà la sua corsa.</p>
                  </div>
                )}

                {/* Itinerario */}
                {selectedTrainRoute && (
                  <div className="mt-6 pt-4 border-t border-border-color">
                    <h4 className="text-sm font-semibold text-muted uppercase tracking-wider mb-4">
                      Itinerario Previsto
                    </h4>
                    <div className="relative border-l-2 border-border-color ml-3 pl-6 space-y-4">
                      {selectedTrainRoute.stationIds.map((stationId, index) => {
                        const station = stations.find(s => s.id === stationId);
                        if (!station) return null;
                        
                        // Determine if train has passed this station
                        let isPast = false;
                        let isCurrent = false;
                        
                        if (selectedTrain.status === 'in_viaggio') {
                          const currentIndex = selectedTrainRoute.stationIds.indexOf(selectedTrain.previousStationId || '');
                          if (index <= currentIndex) isPast = true;
                        } else if (selectedTrain.status === 'in_stazione') {
                          const currentIndex = selectedTrainRoute.stationIds.indexOf(selectedTrain.currentStationId || '');
                          if (index < currentIndex) isPast = true;
                          if (index === currentIndex) isCurrent = true;
                        }

                        return (
                          <div key={stationId} className={`relative transition-all ${isPast ? 'opacity-50' : ''}`}>
                            <div className={`absolute -left-[31px] w-4 h-4 rounded-full border-2 border-bg-panel ${
                              isCurrent ? 'bg-primary scale-125' : 
                              isPast ? 'bg-muted' : 'bg-bg-panel border-muted'
                            }`}></div>
                            <div className="flex flex-col">
                              <span className={`font-semibold text-sm ${isCurrent ? 'text-primary' : ''}`}>
                                {station.name}
                              </span>
                              <span className="text-xs text-muted font-mono">{station.code}</span>
                              {index < selectedTrainRoute.stationIds.length - 1 && !isPast && !isCurrent && (
                                <span className="text-xs text-muted mt-1 opacity-70">
                                  + {selectedTrainRoute.travelTimes[index]} min stimati
                                </span>
                              )}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
