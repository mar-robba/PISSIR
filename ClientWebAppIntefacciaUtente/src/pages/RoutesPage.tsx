import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { Route } from 'lucide-react';
import { useState } from 'react';

export default function RoutesPage() {
  const { routes, stations } = useRailwayStore();
  const [selectedRouteId, setSelectedRouteId] = useState<string | null>(null);

  const selectedRoute = routes.find(r => r.id === selectedRouteId);

  return (
    <div className="flex flex-col gap-6 animate-fade-in h-full">
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 h-full">
        {/* Routes List */}
        <Card title="Tratte Ferroviarie" className="col-span-2">
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Codice</th>
                  <th>Nome Tratta</th>
                  <th>Stato</th>
                  <th>Treni Attivi</th>
                  <th>Azioni</th>
                </tr>
              </thead>
              <tbody>
                {routes.map(route => (
                  <tr key={route.id} className={selectedRouteId === route.id ? 'bg-primary/10' : ''}>
                    <td className="font-mono text-sm">{route.code}</td>
                    <td className="font-medium">{route.name}</td>
                    <td>
                      <Badge type={route.active ? 'success' : 'neutral'}>
                        {route.active ? 'Attiva' : 'Disattiva'}
                      </Badge>
                    </td>
                    <td>{route.trainIds.length}</td>
                    <td>
                      <button 
                        className="btn btn-outline text-xs py-1"
                        onClick={() => setSelectedRouteId(route.id)}
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

        {/* Route Details (UC2/UC3) */}
        <Card title="Dettaglio Tratta" className="col-span-1 flex flex-col">
          {!selectedRoute ? (
            <div className="text-center text-muted p-8 flex-1 flex flex-col items-center justify-center">
              <Route size={48} className="mb-4 opacity-20" />
              <p>Seleziona una tratta per visualizzarne i dettagli e le stazioni attraversate.</p>
            </div>
          ) : (
            <div className="animate-fade-in flex-1">
              <h3 className="text-xl mb-2">{selectedRoute.name}</h3>
              <p className="text-sm font-mono text-muted mb-6">Codice: {selectedRoute.code}</p>

              <h4 className="text-sm font-semibold text-muted uppercase tracking-wider mb-4">Stazioni Attraversate</h4>
              <div className="relative border-l-2 border-border-color ml-3 pl-6 space-y-6">
                {selectedRoute.stationIds.map((stationId, index) => {
                  const station = stations.find(s => s.id === stationId);
                  if (!station) return null;
                  
                  return (
                    <div key={stationId} className="relative">
                      <div className={`absolute -left-[31px] w-4 h-4 rounded-full border-2 border-bg-panel ${
                        station.status === 'operativa' ? 'bg-success' : 'bg-danger'
                      }`}></div>
                      <div className="flex flex-col">
                        <span className="font-semibold text-sm">{station.name}</span>
                        <span className="text-xs text-muted font-mono">{station.code}</span>
                        {index < selectedRoute.stationIds.length - 1 && (
                          <span className="text-xs text-primary mt-1">
                            ↓ {selectedRoute.travelTimes[index]} min stimati
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
