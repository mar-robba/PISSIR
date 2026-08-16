import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { 
  Train, 
  Building2, 
  AlertTriangle, 
  Clock, 
  ArrowRight
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';

/**
 * Componente principale che renderizza la Dashboard riassuntiva.
 * Fornisce KPI di altissimo livello (treni attivi, ritardo medio, stazioni guaste, allarmi attivi)
 * e mostra una sintesi degli ultimi allarmi e dei treni attualmente in transito.
 */
export default function DashboardPage() {
  // Preleva lo stato globale sincronizzato tramite Zustand
  const trains = useRailwayStore((s) => s.trains);
  const stations = useRailwayStore((s) => s.stations);
  const alerts = useRailwayStore((s) => s.alerts);
  // I KPI arrivano dalla Centrale: GET /api/dashboard all'apertura, poi lo snapshot ogni
  // dieci secondi. Prima questa pagina se li ricalcolava da sola e le formule NON erano le
  // stesse — treni in movimento, ritardo medio e stazioni guaste davano tre numeri diversi
  // da quelli del server, e gli allarmi attivi erano gonfiati dai doppioni.
  const kpi = useRailwayStore((s) => s.kpi);
  const navigate = useNavigate();

  // I treni in viaggio da elencare qui sotto restano una lettura locale: è l'elenco delle
  // righe da disegnare, non un indicatore.
  const activeTrains = trains.filter(t => t.status === 'in_viaggio' || t.status === 'in_ritardo');

  // Finché la prima risposta della Centrale non è arrivata si mostrano dei trattini
  // invece di numeri inventati in locale.
  const numero = (valore: number | undefined) =>
    valore === undefined ? '—' : Math.round(valore);
  const trainsInMotion = numero(kpi?.trainsInMotion);
  const avgDelay = numero(kpi?.avgDelay);
  const faultyStations = numero(kpi?.stationsFaulty);
  const activeAlerts = numero(kpi?.activeAlerts);

  return (
    <div className="flex flex-col gap-6 animate-fade-in">
      {/* KPI Section */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        
        {/* KPI: Treni Attivi */}
        <Card className="flex items-center gap-4">
          <div className="p-3 bg-primary/20 rounded-full text-primary" style={{ background: 'rgba(59, 130, 246, 0.2)' }}>
            <Train size={24} color="#3b82f6" />
          </div>
          <div>
            <p className="text-sm text-muted m-0">Treni Attivi</p>
            <h3 className="text-2xl font-bold m-0">{trainsInMotion} <span className="text-sm font-normal text-muted">/ {kpi?.totalTrains ?? trains.length}</span></h3>
          </div>
        </Card>

        {/* KPI: Ritardo Medio */}
        <Card className="flex items-center gap-4">
          <div className="p-3 rounded-full" style={{ background: 'rgba(239, 68, 68, 0.2)' }}>
            <Clock size={24} color="#ef4444" />
          </div>
          <div>
            <p className="text-sm text-muted m-0">Ritardo Medio</p>
            <h3 className="text-2xl font-bold m-0">{avgDelay} <span className="text-sm font-normal text-muted">min</span></h3>
          </div>
        </Card>

        {/* KPI: Stazioni con Anomalie */}
        <Card className="flex items-center gap-4">
          <div className="p-3 rounded-full" style={{ background: 'rgba(245, 158, 11, 0.2)' }}>
            <Building2 size={24} color="#f59e0b" />
          </div>
          <div>
            <p className="text-sm text-muted m-0">Stazioni Anomale</p>
            <h3 className="text-2xl font-bold m-0">{faultyStations} <span className="text-sm font-normal text-muted">/ {stations.length}</span></h3>
          </div>
        </Card>

        {/* KPI: Allarmi Critici (Cliccabile) */}
        <Card className="flex items-center gap-4 cursor-pointer hover:border-red-500/50 transition-colors" onClick={() => navigate('/alerts')}>
          <div className="p-3 rounded-full" style={{ background: 'rgba(239, 68, 68, 0.2)' }}>
            <AlertTriangle size={24} color="#ef4444" />
          </div>
          <div>
            <p className="text-sm text-muted m-0">Allarmi Attivi</p>
            <h3 className="text-2xl font-bold m-0 text-danger">{activeAlerts}</h3>
          </div>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 mt-4">
        {/* Sezione: Log Allarmi Recenti */}
        <Card title="Ultimi Allarmi" className="col-span-1" action={
          <button onClick={() => navigate('/alerts')} className="btn btn-outline text-xs py-1 px-2">
            Vedi tutti
          </button>
        }>
          <div className="flex flex-col gap-3">
            {alerts.slice(0, 5).map(alert => (
              <div key={alert.id} className="p-3 border border-border-color rounded-md bg-black/20">
                <div className="flex justify-between items-start mb-2">
                  <Badge type={alert.severity === 'critical' ? 'danger' : alert.severity === 'warning' ? 'warning' : 'info'}>
                    {alert.type.replace('_', ' ')}
                  </Badge>
                  <span className="text-xs text-muted">
                    {new Date(alert.timestamp).toLocaleTimeString()}
                  </span>
                </div>
                <p className="text-sm m-0">{alert.message}</p>
              </div>
            ))}
            {alerts.length === 0 && <p className="text-center text-muted">Nessun allarme recente</p>}
          </div>
        </Card>

        {/* Sezione: Snapshot Live dei Treni */}
        <Card title="Treni in Viaggio" className="col-span-2" action={
          <button onClick={() => navigate('/map')} className="btn btn-outline text-xs py-1 px-2">
            Mappa Completa
          </button>
        }>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Convoglio</th>
                  <th>Stato</th>
                  <th>Posizione</th>
                  <th>Ritardo</th>
                </tr>
              </thead>
              <tbody>
                {activeTrains.slice(0, 6).map(train => {
                  const nextStation = stations.find(s => s.id === train.nextStationId);
                  const prevStation = stations.find(s => s.id === train.previousStationId);
                  
                  return (
                    <tr key={train.id}>
                      <td className="font-medium font-mono">{train.id}</td>
                      <td>
                        <div className="flex items-center gap-2">
                          <span className={`status-indicator status-pulse ${train.status === 'in_ritardo' ? 'status-guasta' : 'status-operativa'}`}></span>
                          <span className="text-sm capitalize">{train.status.replace('_', ' ')}</span>
                        </div>
                      </td>
                      <td>
                        <div className="flex items-center gap-2 text-sm">
                          {prevStation?.code || '?'} <ArrowRight size={14} className="text-muted" /> {nextStation?.code || '?'}
                        </div>
                      </td>
                      <td>
                        {train.delayMinutes > 0 ? (
                          <span className="text-danger font-medium">+{train.delayMinutes} min</span>
                        ) : (
                          <span className="text-success text-sm">In orario</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
                {activeTrains.length === 0 && (
                  <tr>
                    <td colSpan={4} className="text-center text-muted p-4">Nessun treno in viaggio</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </div>
  );
}
