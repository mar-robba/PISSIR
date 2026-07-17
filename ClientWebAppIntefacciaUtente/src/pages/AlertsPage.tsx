import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { ShieldAlert, CheckCircle2, Wrench, Clock } from 'lucide-react';
import { useState } from 'react';

export default function AlertsPage() {
  const { alerts, stations, trains, acknowledgeAlert, dispatchOperators } = useRailwayStore();
  const [filter, setFilter] = useState<'all' | 'active' | 'resolved'>('active');

  const filteredAlerts = alerts.filter(a => {
    if (filter === 'active') return !a.acknowledged;
    if (filter === 'resolved') return a.acknowledged;
    return true;
  });

  const getSeverityColor = (severity: string) => {
    switch(severity) {
      case 'critical': return 'text-danger border-danger/30 bg-danger/10';
      case 'warning': return 'text-warning border-warning/30 bg-warning/10';
      default: return 'text-info border-info/30 bg-info/10';
    }
  };

  const handleDispatch = (stationId: string, alertId: string) => {
    dispatchOperators(stationId);
    acknowledgeAlert(alertId);
  };

  return (
    <div className="flex flex-col gap-6 animate-fade-in h-full">
      <div className="flex justify-between items-center">
        <div>
          <h2 className="text-2xl font-bold mb-1">Centro Gestione Allarmi</h2>
          <p className="text-muted text-sm">Monitoraggio in tempo reale delle anomalie di rete</p>
        </div>
        
        <div className="flex gap-2 p-1 bg-black/20 rounded-md border border-border-color">
          <button 
            className={`px-4 py-1.5 rounded text-sm transition-colors ${filter === 'active' ? 'bg-primary text-white' : 'hover:bg-white/5'}`}
            onClick={() => setFilter('active')}
          >
            Attivi ({alerts.filter(a => !a.acknowledged).length})
          </button>
          <button 
            className={`px-4 py-1.5 rounded text-sm transition-colors ${filter === 'resolved' ? 'bg-primary text-white' : 'hover:bg-white/5'}`}
            onClick={() => setFilter('resolved')}
          >
            Risolti
          </button>
          <button 
            className={`px-4 py-1.5 rounded text-sm transition-colors ${filter === 'all' ? 'bg-primary text-white' : 'hover:bg-white/5'}`}
            onClick={() => setFilter('all')}
          >
            Tutti
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4">
        {filteredAlerts.length === 0 ? (
          <Card className="flex flex-col items-center justify-center p-12 text-muted">
            <CheckCircle2 size={64} className="mb-4 opacity-20 text-success" />
            <p className="text-lg">Nessun allarme da visualizzare per il filtro selezionato.</p>
          </Card>
        ) : (
          filteredAlerts.map(alert => {
            const station = alert.stationId ? stations.find(s => s.id === alert.stationId) : null;
            const train = alert.trainId ? trains.find(t => t.id === alert.trainId) : null;

            return (
              <div 
                key={alert.id} 
                className={`glass-panel p-5 flex flex-col md:flex-row gap-4 justify-between items-start md:items-center transition-all ${
                  !alert.acknowledged ? 'border-l-4 border-l-danger bg-danger/5' : 'opacity-70 grayscale-[30%]'
                }`}
              >
                <div className="flex gap-4 items-start flex-1">
                  <div className={`p-3 rounded-full mt-1 ${getSeverityColor(alert.severity)}`}>
                    <ShieldAlert size={24} />
                  </div>
                  
                  <div className="flex flex-col gap-2">
                    <div className="flex items-center gap-3">
                      <Badge type={alert.severity === 'critical' ? 'danger' : alert.severity === 'warning' ? 'warning' : 'info'}>
                        {alert.type.toUpperCase().replace('_', ' ')}
                      </Badge>
                      <span className="text-xs text-muted flex items-center gap-1">
                        <Clock size={12} />
                        {new Date(alert.timestamp).toLocaleString()}
                      </span>
                    </div>
                    
                    <p className="text-base font-medium m-0">{alert.message}</p>
                    
                    <div className="flex gap-4 mt-1 text-sm text-muted">
                      {station && (
                        <span>Stazione: <strong className="text-main">{station.name} ({station.code})</strong></span>
                      )}
                      {train && (
                        <span>Treno: <strong className="text-main">{train.convoglio}</strong></span>
                      )}
                    </div>
                  </div>
                </div>

                <div className="flex flex-col sm:flex-row gap-2 mt-4 md:mt-0 w-full md:w-auto">
                  {!alert.acknowledged && (
                    <button 
                      onClick={() => acknowledgeAlert(alert.id)}
                      className="btn btn-outline text-sm"
                    >
                      <CheckCircle2 size={16} /> Presa Visione
                    </button>
                  )}
                  
                  {/* UC5: Invia operatori per riparare stazione guasta */}
                  {alert.type === 'stazione_guasta' && alert.stationId && !station?.operatorsDispatched && (
                    <button 
                      onClick={() => handleDispatch(alert.stationId!, alert.id)}
                      className="btn btn-primary text-sm shadow-[0_0_15px_rgba(59,130,246,0.5)] animate-pulse"
                    >
                      <Wrench size={16} /> Invia Operatori (UC5)
                    </button>
                  )}

                  {station?.operatorsDispatched && alert.type === 'stazione_guasta' && (
                    <Badge type="success" className="px-3 py-2">Operatori in loco</Badge>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
