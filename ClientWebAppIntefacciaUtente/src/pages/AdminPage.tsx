import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { useState } from 'react';
import { ShieldAlert, Plus, Trash2, Edit } from 'lucide-react';
import RouteEditorModal from '../components/admin/RouteEditorModal';

export default function AdminPage() {
  const { routes, trains, suppressTrain, deleteRoute } = useRailwayStore();
  const [activeTab, setActiveTab] = useState<'routes' | 'trains'>('routes');
  const [isRouteModalOpen, setIsRouteModalOpen] = useState(false);
  const [editingRouteId, setEditingRouteId] = useState<string | null>(null);

  const handleSuppress = (trainId: string) => {
    if (confirm("Sei sicuro di voler sopprimere questo treno? L'azione è irreversibile e genererà un allarme (UC9).")) {
      suppressTrain(trainId);
    }
  };

  const handleDeleteRoute = (routeId: string) => {
    if (confirm("Sei sicuro di voler eliminare questa tratta? (UC7)")) {
      deleteRoute(routeId);
    }
  };

  return (
    <div className="flex flex-col gap-6 animate-fade-in h-full">
      <div className="flex items-center gap-4 border-b border-border-color pb-4">
        <ShieldAlert size={32} className="text-warning" />
        <div>
          <h2 className="text-2xl font-bold m-0">Pannello Amministratore</h2>
          <p className="text-sm text-muted m-0">Area riservata alle operazioni critiche di rete</p>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-4">
        <button 
          className={`px-6 py-2 rounded-t-md font-medium border-b-2 transition-all ${
            activeTab === 'routes' 
              ? 'border-primary text-primary bg-primary/10' 
              : 'border-transparent text-muted hover:bg-white/5'
          }`}
          onClick={() => setActiveTab('routes')}
        >
          Gestione Tratte (UC6/UC7)
        </button>
        <button 
          className={`px-6 py-2 rounded-t-md font-medium border-b-2 transition-all ${
            activeTab === 'trains' 
              ? 'border-primary text-primary bg-primary/10' 
              : 'border-transparent text-muted hover:bg-white/5'
          }`}
          onClick={() => setActiveTab('trains')}
        >
          Gestione Treni (UC8/UC9)
        </button>
      </div>

      <div className="flex-1">
        {activeTab === 'routes' ? (
          <Card 
            title="Amministrazione Tratte" 
            action={
              <button 
                className="btn btn-primary text-sm" 
                onClick={() => {
                  setEditingRouteId(null);
                  setIsRouteModalOpen(true);
                }}
              >
                <Plus size={16} /> Nuova Tratta (UC6)
              </button>
            }
          >
            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Codice</th>
                    <th>Nome</th>
                    <th>Stazioni</th>
                    <th>Treni Assegnati</th>
                    <th>Azioni (UC7)</th>
                  </tr>
                </thead>
                <tbody>
                  {routes.map(route => (
                    <tr key={route.id}>
                      <td className="font-mono text-sm">{route.code}</td>
                      <td className="font-medium">{route.name}</td>
                      <td>{route.stationIds.length} stazioni</td>
                      <td>{route.trainIds.length} treni</td>
                      <td>
                        <div className="flex gap-2">
                          <button 
                            className="btn btn-outline text-xs p-1.5 text-warning hover:bg-warning/10 hover:border-warning/50"
                            onClick={() => {
                              setEditingRouteId(route.id);
                              setIsRouteModalOpen(true);
                            }}
                          >
                            <Edit size={14} />
                          </button>
                          <button 
                            className="btn btn-outline text-xs p-1.5 text-danger hover:bg-danger/10 hover:border-danger/50"
                            onClick={() => handleDeleteRoute(route.id)}
                          >
                            <Trash2 size={14} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        ) : (
          <Card title="Amministrazione Operativa Treni">
            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Convoglio</th>
                    <th>Tratta</th>
                    <th>Stato Attuale</th>
                    <th>Azioni Amministrative</th>
                  </tr>
                </thead>
                <tbody>
                  {trains.map(train => {
                    const route = routes.find(r => r.id === train.routeId);
                    return (
                      <tr key={train.id} className={train.status === 'soppresso' ? 'opacity-50' : ''}>
                        <td className="font-mono font-bold text-primary">{train.convoglio}</td>
                        <td className="text-sm">{route?.name || '-'}</td>
                        <td>
                          <Badge type={train.status === 'soppresso' ? 'danger' : 'info'}>
                            {train.status.toUpperCase()}
                          </Badge>
                        </td>
                        <td>
                          <div className="flex gap-2">
                            <button 
                              className="btn btn-outline text-xs py-1 px-2"
                              disabled={train.status === 'soppresso'}
                              onClick={() => alert('Mock: Apertura modale per modificare percorso treno (UC8)')}
                            >
                              Modifica Percorso (UC8)
                            </button>
                            {train.status === 'in_stazione' && (
                              <button 
                                className="btn btn-danger text-xs py-1 px-2 shadow-[0_0_10px_rgba(239,68,68,0.3)]"
                                onClick={() => handleSuppress(train.id)}
                              >
                                Sopprimi (UC9)
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Card>
        )}
      </div>

      <RouteEditorModal 
        isOpen={isRouteModalOpen}
        onClose={() => setIsRouteModalOpen(false)}
        routeIdToEdit={editingRouteId}
      />
    </div>
  );
}
