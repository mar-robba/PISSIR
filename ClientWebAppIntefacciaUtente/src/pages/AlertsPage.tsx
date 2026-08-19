import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { ShieldAlert, CheckCircle2, Wrench, Clock, Info, X } from 'lucide-react';
import { useState } from 'react';
import { useAuthStore } from '../store/authStore';

export default function AlertsPage() {
  const alerts = useRailwayStore((s) => s.alerts);
  const stations = useRailwayStore((s) => s.stations);
  const trains = useRailwayStore((s) => s.trains);
  const acknowledgeAlert = useRailwayStore((s) => s.acknowledgeAlert);
  const dispatchOperators = useRailwayStore((s) => s.dispatchOperators);
  // Le conferme dei comandi stanno in un elenco a parte: sono notifiche di questo browser,
  // non guasti della rete (vedi UiNotification).
  const notifications = useRailwayStore((s) => s.notifications);
  const dismissNotification = useRailwayStore((s) => s.dismissNotification);
  const [filter, setFilter] = useState<'all' | 'active' | 'resolved'>('active');
  const user = useAuthStore((s) => s.user);

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

      {/* Notifiche d'interfaccia: esiti dei comandi dati da questo browser. Sono tenute
          fuori dall'elenco degli allarmi perché sulla Centrale non esiste nessun guasto
          corrispondente — prima venivano infilate lì dentro con id inventati, gonfiavano
          il contatore degli allarmi attivi e la "Presa Visione" finiva in un 404. */}
      {notifications.length > 0 && (
        <Card title="Notifiche operative" className="flex flex-col gap-2">
          <p className="text-xs text-muted m-0 mb-2">
            Esiti dei comandi dati da questa postazione: non sono guasti della rete e
            restano solo in questa sessione.
          </p>
          {notifications.map(notification => (
            <div
              key={notification.id}
              className="flex items-center justify-between gap-3 p-3 border border-border-color rounded-md bg-black/20"
            >
              <div className="flex items-center gap-3">
                <Info size={16} className={notification.severity === 'critical' ? 'text-danger' : 'text-info'} />
                <span className="text-sm">{notification.message}</span>
                <span className="text-xs text-muted">
                  {new Date(notification.timestamp).toLocaleTimeString()}
                </span>
              </div>
              <button
                title="Togli la notifica"
                className="p-1 hover:bg-white/10 rounded-full transition-colors"
                onClick={() => dismissNotification(notification.id)}
              >
                <X size={16} />
              </button>
            </div>
          ))}
        </Card>
      )}

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
                      <Badge type={alert.origin === 'dedotto_centrale' ? 'info' : 'warning'}>
                        {alert.origin === 'dedotto_centrale' ? 'DEDOTTO DALLA CENTRALE' : 'SEGNALATO DAL CAMPO'}
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
                        <span>Treno: <strong className="text-main">{train.id}</strong></span>
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
                  
                  {/* UC5: Invia operatori per riparare stazione guasta.
                      La condizione è "l'allarme riguarda una stazione", non "è di tipo
                      stazione_guasta": un guasto ai sensori o un heartbeat perso richiedono
                      la squadra esattamente come gli altri, ma essendo classificati
                      sensore_offline restavano senza pulsante — e non c'è nessun altro
                      punto dell'interfaccia da cui mandare gli operatori (RF01.4.1). */}
                  {alert.stationId && !station?.operatorsDispatched && !alert.acknowledged && user?.role === 'tecnico' && (
                    <button
                      onClick={() => handleDispatch(alert.stationId!, alert.id)}
                      className="btn btn-primary text-sm shadow-[0_0_15px_rgba(59,130,246,0.5)] animate-pulse"
                    >
                      <Wrench size={16} /> Invia Operatori (UC5)
                    </button>
                  )}

                  {station?.operatorsDispatched && alert.stationId && (
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
