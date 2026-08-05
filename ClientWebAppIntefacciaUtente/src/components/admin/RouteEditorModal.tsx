import { useState, useEffect } from 'react';
import { useRailwayStore } from '../../store/railwayStore';
import { Route } from '../../types';
import { X, Save, Trash2, ArrowUp, ArrowDown } from 'lucide-react';
import Card from '../ui/Card';

interface RouteEditorModalProps {
  isOpen: boolean;
  onClose: () => void;
  routeIdToEdit?: string | null;
}

export default function RouteEditorModal({ isOpen, onClose, routeIdToEdit }: RouteEditorModalProps) {
  const { routes, stations, updateRoute, addRoute } = useRailwayStore();
  
  // Local state for the route being edited
  const [formData, setFormData] = useState<Partial<Route>>({
    name: '',
    code: '',
    stationIds: [],
    travelTimes: [],
    active: true,
  });

  useEffect(() => {
    if (isOpen) {
      if (routeIdToEdit) {
        const existingRoute = routes.find(r => r.id === routeIdToEdit);
        if (existingRoute) {
          setFormData({ ...existingRoute });
        }
      } else {
        // Init empty
        setFormData({
          id: '',
          name: '',
          code: '',
          stationIds: [],
          trainIds: [],
          travelTimes: [],
          active: true,
          createdAt: Date.now(),
        });
      }
    }
    // Non includiamo "routes" tra le dipendenze: altrimenti l'effetto si
    // rieseguirebbe ad ogni aggiornamento dello store, azzerando i dati che
    // l'utente sta ancora digitando nel form (stesso problema di TrainEditorModal).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, routeIdToEdit]);

  if (!isOpen) return null;

  const handleSave = async () => {
    if (!formData.name || !formData.code || formData.stationIds!.length < 2) {
      alert("Compila tutti i campi obbligatori e inserisci almeno 2 stazioni.");
      return;
    }

    if (routeIdToEdit) {
      await updateRoute(routeIdToEdit, formData);
    } else {
      // L'id della nuova tratta è il Nome Tratta così come inserito in UI
      // (non più generato automaticamente): deve quindi essere univoco.
      if (routes.some(r => r.id === formData.name)) {
        alert('Esiste già una tratta con questo nome.');
        return;
      }
      await addRoute({ ...formData, id: formData.name } as Route);
    }
    onClose();
  };

  const addStation = (stationId: string) => {
    if (formData.stationIds!.includes(stationId)) return; // prevent duplicates for simplicity
    
    const newStationIds = [...formData.stationIds!, stationId];
    // Add default travel time if it's not the first station
    const newTravelTimes = newStationIds.length > 1 
      ? [...formData.travelTimes!, 15] 
      : formData.travelTimes!;

    setFormData({
      ...formData,
      stationIds: newStationIds,
      travelTimes: newTravelTimes
    });
  };

  const removeStation = (index: number) => {
    const newStationIds = [...formData.stationIds!];
    newStationIds.splice(index, 1);
    
    const newTravelTimes = [...formData.travelTimes!];
    if (index > 0) {
      // Remove travel time to this station
      newTravelTimes.splice(index - 1, 1);
    } else if (newTravelTimes.length > 0) {
      // If removing first station, remove first travel time
      newTravelTimes.splice(0, 1);
    }

    setFormData({
      ...formData,
      stationIds: newStationIds,
      travelTimes: newTravelTimes
    });
  };

  const updateTravelTime = (index: number, minutes: number) => {
    const newTravelTimes = [...formData.travelTimes!];
    newTravelTimes[index] = minutes;
    setFormData({ ...formData, travelTimes: newTravelTimes });
  };

  const moveStation = (index: number, direction: 'up' | 'down') => {
    if (direction === 'up' && index === 0) return;
    if (direction === 'down' && index === formData.stationIds!.length - 1) return;

    const newStationIds = [...formData.stationIds!];
    const targetIndex = direction === 'up' ? index - 1 : index + 1;
    
    // Swap
    [newStationIds[index], newStationIds[targetIndex]] = [newStationIds[targetIndex], newStationIds[index]];
    
    setFormData({ ...formData, stationIds: newStationIds });
  };

  const availableStations = stations.filter(s => !formData.stationIds?.includes(s.id));

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <Card className="w-full max-w-3xl max-h-[90vh] overflow-hidden flex flex-col shadow-2xl border-primary/20">
        <div className="flex justify-between items-center mb-6 border-b border-border-color pb-4">
          <h2 className="text-xl font-bold">
            {routeIdToEdit ? 'Modifica Tratta' : 'Nuova Tratta'}
          </h2>
          <button onClick={onClose} className="p-1 hover:bg-white/10 rounded-full transition-colors">
            <X size={24} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto pr-2 space-y-6">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Nome Tratta</label>
              <input 
                type="text" 
                className="input-field w-full" 
                value={formData.name || ''} 
                onChange={e => setFormData({ ...formData, name: e.target.value })}
                placeholder="es. Torino — Milano"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Codice Tratta</label>
              <input 
                type="text" 
                className="input-field w-full font-mono uppercase" 
                value={formData.code || ''} 
                onChange={e => setFormData({ ...formData, code: e.target.value.toUpperCase() })}
                placeholder="es. TO-MI-001"
              />
            </div>
          </div>

          <div className="flex items-center gap-2 mb-4">
            <input 
              type="checkbox" 
              id="activeRoute" 
              checked={formData.active || false}
              onChange={e => setFormData({ ...formData, active: e.target.checked })}
              className="w-4 h-4 rounded border-gray-600 bg-gray-800 text-primary focus:ring-primary"
            />
            <label htmlFor="activeRoute" className="text-sm">Tratta Attiva</label>
          </div>

          <div>
            <h3 className="text-md font-semibold mb-3 flex items-center justify-between">
              Itinerario (Stazioni)
              <div className="flex gap-2">
                <select 
                  className="input-field text-sm py-1"
                  onChange={e => {
                    if(e.target.value) {
                      addStation(e.target.value);
                      e.target.value = '';
                    }
                  }}
                  defaultValue=""
                >
                  <option value="" disabled>Aggiungi Stazione...</option>
                  {availableStations.map(s => (
                    <option key={s.id} value={s.id}>{s.name} ({s.code})</option>
                  ))}
                </select>
              </div>
            </h3>

            {/* Le tratte elementari sono archi FISICI della rete e possono essere
                condivise da più itinerari (es. Milano-Bologna sta sia sull'itinerario
                per Napoli sia su quello per Roma): il tempo di percorrenza di un arco
                già usato altrove si modifica dalla pagina "Tratte", non da qui. */}
            <p className="text-xs text-muted mb-3">
              Il tempo indicato vale per il singolo arco fisico fra due stazioni. Se quell'arco è già
              usato da un altro itinerario, il valore resta quello impostato nella pagina Tratte
              (altrimenti si modificherebbero anche gli altri percorsi).
            </p>

            {formData.stationIds?.length === 0 ? (
              <div className="text-center p-6 bg-black/20 rounded-md border border-dashed border-border-color text-muted">
                Nessuna stazione presente in questa tratta. Aggiungine almeno due.
              </div>
            ) : (
              <div className="space-y-2">
                {formData.stationIds?.map((stationId, index) => {
                  const station = stations.find(s => s.id === stationId);
                  if (!station) return null;
                  
                  return (
                    <div key={`${stationId}-${index}`}>
                      <div className="flex items-center gap-3 p-3 bg-black/20 border border-border-color rounded-md">
                        <div className="flex flex-col gap-1">
                          <button 
                            disabled={index === 0} 
                            onClick={() => moveStation(index, 'up')}
                            className="p-1 disabled:opacity-30 hover:bg-white/10 rounded"
                          >
                            <ArrowUp size={14} />
                          </button>
                          <button 
                            disabled={index === formData.stationIds!.length - 1} 
                            onClick={() => moveStation(index, 'down')}
                            className="p-1 disabled:opacity-30 hover:bg-white/10 rounded"
                          >
                            <ArrowDown size={14} />
                          </button>
                        </div>
                        
                        <div className="flex-1">
                          <span className="font-semibold">{station.name}</span>
                          <span className="text-xs text-muted ml-2 font-mono bg-white/5 px-2 py-0.5 rounded">
                            {station.code}
                          </span>
                        </div>
                        
                        <button 
                          onClick={() => removeStation(index)}
                          className="p-2 text-danger hover:bg-danger/10 rounded transition-colors"
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                      
                      {index < formData.stationIds!.length - 1 && (
                        <div className="flex items-center justify-center my-2 group">
                          <div className="w-1 h-8 border-l-2 border-dashed border-border-color"></div>
                          <div className="absolute bg-bg-panel px-3 border border-border-color rounded-full flex items-center gap-2">
                            <span className="text-xs text-muted">Tempo previsto:</span>
                            <input 
                              type="number" 
                              min="1"
                              className="input-field text-center text-xs py-1 w-16"
                              value={formData.travelTimes![index] || 0}
                              onChange={e => updateTravelTime(index, parseInt(e.target.value) || 0)}
                            />
                            <span className="text-xs text-muted">min</span>
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        <div className="flex justify-end gap-3 mt-6 pt-4 border-t border-border-color">
          <button className="btn btn-outline" onClick={onClose}>
            Annulla
          </button>
          <button className="btn btn-primary" onClick={handleSave}>
            <Save size={18} /> Salva Tratta
          </button>
        </div>
      </Card>
    </div>
  );
}
