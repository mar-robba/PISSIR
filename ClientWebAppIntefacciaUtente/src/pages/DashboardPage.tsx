import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import StatoComposto from '../components/ui/StatoComposto';
import { statiDelTreno } from '../api/apiClient';
import { 
  Train, 
  Building2, 
  AlertTriangle, 
  Clock, 
  ArrowRight
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import './DashboardPage.css';

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
    <div className="dashboard-page animate-fade-in">
      {/* KPI Section */}
      <div className="dashboard-kpi-griglia">

        {/* KPI: Treni Attivi. Icona e testo stanno in dashboard-kpi-corpo e non
            direttamente nella Card: il perché è in DashboardPage.css */}
        <Card className="dashboard-kpi">
          <div className="dashboard-kpi-corpo">
            <span className="dashboard-kpi-icona dashboard-kpi-icona-blu">
              <Train size={22} />
            </span>
            <div className="dashboard-kpi-testo">
              <p className="dashboard-kpi-etichetta">Treni Attivi</p>
              <h3 className="dashboard-kpi-valore">{trainsInMotion} <small>/ {kpi?.totalTrains ?? trains.length}</small></h3>
            </div>
          </div>
        </Card>

        {/* KPI: Ritardo Medio */}
        <Card className="dashboard-kpi">
          <div className="dashboard-kpi-corpo">
            <span className="dashboard-kpi-icona dashboard-kpi-icona-rossa">
              <Clock size={22} />
            </span>
            <div className="dashboard-kpi-testo">
              <p className="dashboard-kpi-etichetta">Ritardo Medio</p>
              <h3 className="dashboard-kpi-valore">{avgDelay} <small>min</small></h3>
            </div>
          </div>
        </Card>

        {/* KPI: Stazioni con Anomalie */}
        <Card className="dashboard-kpi">
          <div className="dashboard-kpi-corpo">
            <span className="dashboard-kpi-icona dashboard-kpi-icona-gialla">
              <Building2 size={22} />
            </span>
            <div className="dashboard-kpi-testo">
              <p className="dashboard-kpi-etichetta">Stazioni Anomale</p>
              <h3 className="dashboard-kpi-valore">{faultyStations} <small>/ {stations.length}</small></h3>
            </div>
          </div>
        </Card>

        {/* KPI: Allarmi Critici (Cliccabile) */}
        <Card className="dashboard-kpi dashboard-kpi-cliccabile" onClick={() => navigate('/alerts')}>
          <div className="dashboard-kpi-corpo">
            <span className="dashboard-kpi-icona dashboard-kpi-icona-rossa">
              <AlertTriangle size={22} />
            </span>
            <div className="dashboard-kpi-testo">
              <p className="dashboard-kpi-etichetta">Allarmi Attivi</p>
              <h3 className="dashboard-kpi-valore text-danger">{activeAlerts}</h3>
            </div>
          </div>
        </Card>
      </div>

      <div className="dashboard-colonne">
        {/* Sezione: Log Allarmi Recenti */}
        <Card title="Ultimi Allarmi" className="dashboard-pannello" action={
          <button onClick={() => navigate('/alerts')} className="btn btn-outline dashboard-bottone">
            Vedi tutti
          </button>
        }>
          <div className="dashboard-allarmi">
            {alerts.slice(0, 5).map(alert => (
              <div key={alert.id} className={`dashboard-allarme dashboard-allarme-${alert.severity}`}>
                <div className="dashboard-allarme-testa">
                  <Badge type={alert.severity === 'critical' ? 'danger' : alert.severity === 'warning' ? 'warning' : 'info'}>
                    {alert.type.replace('_', ' ')}
                  </Badge>
                  <span className="dashboard-allarme-ora">
                    {new Date(alert.timestamp).toLocaleTimeString()}
                  </span>
                </div>
                <p>{alert.message}</p>
              </div>
            ))}
            {alerts.length === 0 && <p className="text-center text-muted">Nessun allarme recente</p>}
          </div>
        </Card>

        {/* Sezione: Snapshot Live dei Treni */}
        <Card title="Treni in Viaggio" className="dashboard-pannello" action={
          <button onClick={() => navigate('/map')} className="btn btn-outline dashboard-bottone">
            Mappa Completa
          </button>
        }>
          <div className="table-container dashboard-treni">
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
                        <div className="dashboard-treno-stato">
                          <span className={`status-indicator status-pulse ${train.status === 'in_ritardo' ? 'status-guasta' : 'status-operativa'}`}></span>
                          <StatoComposto stati={statiDelTreno(train)} />
                        </div>
                      </td>
                      <td>
                        <div className="dashboard-treno-tratta">
                          {prevStation?.code || '?'} <ArrowRight size={14} className="text-muted" /> {nextStation?.code || '?'}
                        </div>
                      </td>
                      <td className="dashboard-treno-ritardo">
                        {train.delayMinutes > 0 ? (
                          <span className="text-danger font-medium">+{train.delayMinutes} min</span>
                        ) : (
                          <span className="text-success">In orario</span>
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
