import { useMemo, useState } from 'react';
import {
  ArrowRight,
  CircleDot,
  MapPin,
  Route as RouteIcon,
  Search,
  Train,
} from 'lucide-react';
import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import './RoutesPage.css';

export default function RoutesPage() {
  const { routes, stations, trains } = useRailwayStore();
  const [selectedRouteId, setSelectedRouteId] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  const filteredRoutes = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    if (!query) return routes;

    return routes.filter((route) =>
      `${route.code} ${route.name}`.toLocaleLowerCase().includes(query),
    );
  }, [routes, search]);

  const selectedRoute = routes.find((route) => route.id === selectedRouteId);
  const activeRoutes = routes.filter((route) => route.active).length;
  const activeTrains = trains.filter((train) => train.status !== 'soppresso').length;
  const totalTravelTime = selectedRoute?.travelTimes.reduce((total, time) => total + time, 0) ?? 0;

  const getStation = (stationId: string) => stations.find((station) => station.id === stationId);

  return (
    <div className="routes-page animate-fade-in">
      <div className="routes-page-heading">
        <div>
          <span className="routes-eyebrow">Rete ferroviaria</span>
          <h2>Gestione tratte</h2>
          <p>Consulta lo stato delle linee e il relativo itinerario operativo.</p>
        </div>
        <div className="routes-summary" aria-label="Riepilogo tratte">
          <div><strong>{activeRoutes}</strong><span>tratte attive</span></div>
          <div><strong>{activeTrains}</strong><span>treni in rete</span></div>
        </div>
      </div>

      <div className="routes-workspace">
        <Card className="routes-list-card">
          <div className="routes-card-heading">
            <div>
              <h3>Tratte ferroviarie</h3>
              <p>{filteredRoutes.length} linee disponibili</p>
            </div>
            <label className="routes-search">
              <Search size={17} aria-hidden="true" />
              <span className="sr-only">Cerca una tratta</span>
              <input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Cerca per nome o codice"
              />
            </label>
          </div>

          <div className="routes-list" role="list">
            {filteredRoutes.map((route) => {
              const firstStation = getStation(route.stationIds[0]);
              const lastStation = getStation(route.stationIds[route.stationIds.length - 1]);
              const selected = selectedRouteId === route.id;

              return (
                <button
                  key={route.id}
                  type="button"
                  className={`route-row ${selected ? 'route-row-selected' : ''}`}
                  onClick={() => setSelectedRouteId(route.id)}
                  aria-pressed={selected}
                >
                  <div className="route-row-icon"><RouteIcon size={20} /></div>
                  <div className="route-row-main">
                    <div className="route-row-title">
                      <strong>{route.name}</strong>
                      <Badge type={route.active ? 'success' : 'neutral'}>
                        {route.active ? 'Attiva' : 'Sospesa'}
                      </Badge>
                    </div>
                    <span className="route-code">{route.code}</span>
                    <span className="route-endpoints">
                      {firstStation?.name ?? 'Stazione non disponibile'}
                      <ArrowRight size={14} aria-hidden="true" />
                      {lastStation?.name ?? 'Stazione non disponibile'}
                    </span>
                  </div>
                  <div className="route-row-metrics">
                    <span><Train size={15} aria-hidden="true" /> {route.trainIds.length}</span>
                    <small>treni assegnati</small>
                  </div>
                  <ArrowRight className="route-row-arrow" size={18} aria-hidden="true" />
                </button>
              );
            })}
            {filteredRoutes.length === 0 && (
              <div className="routes-no-results">
                <Search size={24} aria-hidden="true" />
                <p>Nessuna tratta corrisponde alla ricerca.</p>
              </div>
            )}
          </div>
        </Card>

        <Card className="route-detail-card">
          {!selectedRoute ? (
            <div className="route-empty-state">
              <div className="route-empty-icon"><RouteIcon size={34} /></div>
              <h3>Seleziona una tratta</h3>
              <p>Scegli una linea dall’elenco per vedere stazioni, percorrenza e treni assegnati.</p>
            </div>
          ) : (
            <div className="route-detail-content animate-fade-in">
              <div className="route-detail-header">
                <div>
                  <span className="routes-eyebrow">Dettaglio tratta</span>
                  <h3>{selectedRoute.name}</h3>
                  <span className="route-code">{selectedRoute.code}</span>
                </div>
                <Badge type={selectedRoute.active ? 'success' : 'neutral'}>
                  {selectedRoute.active ? 'In esercizio' : 'Sospesa'}
                </Badge>
              </div>

              <div className="route-stat-grid">
                <div><Train size={17} /><strong>{selectedRoute.trainIds.length}</strong><span>treni assegnati</span></div>
                <div><MapPin size={17} /><strong>{selectedRoute.stationIds.length}</strong><span>stazioni</span></div>
                <div><CircleDot size={17} /><strong>{totalTravelTime}</strong><span>min. stimati</span></div>
              </div>

              <div className="route-itinerary">
                <div className="route-itinerary-title">
                  <h4>Itinerario</h4>
                  <span>{selectedRoute.stationIds.length} fermate</span>
                </div>
                <ol>
                  {selectedRoute.stationIds.map((stationId, index) => {
                    const station = getStation(stationId);
                    if (!station) return null;
                    const isOperational = station.status === 'operativa';

                    return (
                      <li key={stationId}>
                        <span className={`station-marker ${isOperational ? 'station-marker-ok' : 'station-marker-warning'}`} />
                        <div className="station-stop">
                          <strong>{station.name}</strong>
                          <span>{station.code} · {station.city}</span>
                        </div>
                        {index < selectedRoute.stationIds.length - 1 && (
                          <span className="station-time">{selectedRoute.travelTimes[index] ?? '—'} min</span>
                        )}
                      </li>
                    );
                  })}
                </ol>
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
