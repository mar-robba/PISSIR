import { useState, useEffect } from 'react';
import { useRailwayStore } from '../../store/railwayStore';
import { Station, StationStatus } from '../../types';
import { X, Save } from 'lucide-react';
import Card from '../ui/Card';

interface StationEditorModalProps {
  isOpen: boolean;
  onClose: () => void;
  stationIdToEdit?: string | null;
}

const STATUS_OPTIONS: { value: StationStatus; label: string }[] = [
  { value: 'operativa', label: 'Operativa' },
  { value: 'guasta', label: 'Guasta' },
  { value: 'manutenzione', label: 'In manutenzione' },
  { value: 'offline', label: 'Offline' },
];

/**
 * Modale amministrativo per registrare una nuova stazione nella rete
 * o modificare i dati (nome, stato, binari, coordinate) di una esistente.
 */
export default function StationEditorModal({ isOpen, onClose, stationIdToEdit }: StationEditorModalProps) {
  const { stations, adminCreateStation, adminUpdateStation } = useRailwayStore();

  const [formData, setFormData] = useState<Partial<Station>>({
    id: '',
    code: '',
    name: '',
    status: 'operativa',
    platforms: 1,
    coordinates: { x: 0, y: 0 },
  });

  useEffect(() => {
    if (isOpen) {
      if (stationIdToEdit) {
        const existingStation = stations.find(s => s.id === stationIdToEdit);
        if (existingStation) {
          setFormData({ ...existingStation });
        }
      } else {
        setFormData({
          id: '',
          code: '',
          name: '',
          city: '',
          status: 'operativa',
          platforms: 1,
          coordinates: { x: 0, y: 0 },
          lastHeartbeat: Date.now(),
        });
      }
    }
  }, [isOpen, stationIdToEdit, stations]);

  if (!isOpen) return null;

  const handleSave = async () => {
    if (!formData.id || !formData.name) {
      alert('Inserisci codice e nome della stazione.');
      return;
    }
    if (!stationIdToEdit && stations.some(s => s.id === formData.id)) {
      alert('Esiste già una stazione con questo codice.');
      return;
    }

    if (stationIdToEdit) {
      await adminUpdateStation(stationIdToEdit, {
        name: formData.name,
        status: formData.status,
        platforms: formData.platforms,
        coordinates: formData.coordinates,
      });
    } else {
      await adminCreateStation({ ...formData, code: formData.id, city: formData.name } as Station);
    }
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <Card className="w-full max-w-xl max-h-[90vh] overflow-hidden flex flex-col shadow-2xl border-primary/20">
        <div className="flex justify-between items-center mb-6 border-b border-border-color pb-4">
          <h2 className="text-xl font-bold">
            {stationIdToEdit ? 'Modifica Stazione' : 'Nuova Stazione'}
          </h2>
          <button onClick={onClose} className="p-1 hover:bg-white/10 rounded-full transition-colors">
            <X size={24} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto pr-2 space-y-6">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Codice Stazione</label>
              <input
                type="text"
                className="input-field w-full font-mono uppercase"
                value={formData.id || ''}
                disabled={!!stationIdToEdit}
                onChange={e => setFormData({ ...formData, id: e.target.value.toUpperCase() })}
                placeholder="es. TO"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Nome</label>
              <input
                type="text"
                className="input-field w-full"
                value={formData.name || ''}
                onChange={e => setFormData({ ...formData, name: e.target.value })}
                placeholder="es. Torino Porta Nuova"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Stato</label>
              <select
                className="input-field w-full"
                value={formData.status || 'operativa'}
                onChange={e => setFormData({ ...formData, status: e.target.value as StationStatus })}
              >
                {STATUS_OPTIONS.map(opt => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Binari</label>
              <input
                type="number"
                min="1"
                className="input-field w-full"
                value={formData.platforms ?? 1}
                onChange={e => setFormData({ ...formData, platforms: parseInt(e.target.value) || 1 })}
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Latitudine</label>
              <input
                type="number"
                step="any"
                className="input-field w-full font-mono"
                value={formData.coordinates?.x ?? 0}
                onChange={e => setFormData({
                  ...formData,
                  coordinates: { x: parseFloat(e.target.value) || 0, y: formData.coordinates?.y ?? 0 }
                })}
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Longitudine</label>
              <input
                type="number"
                step="any"
                className="input-field w-full font-mono"
                value={formData.coordinates?.y ?? 0}
                onChange={e => setFormData({
                  ...formData,
                  coordinates: { x: formData.coordinates?.x ?? 0, y: parseFloat(e.target.value) || 0 }
                })}
              />
            </div>
          </div>
        </div>

        <div className="flex justify-end gap-3 mt-6 pt-4 border-t border-border-color">
          <button className="btn btn-outline" onClick={onClose}>
            Annulla
          </button>
          <button className="btn btn-primary" onClick={handleSave}>
            <Save size={18} /> Salva Stazione
          </button>
        </div>
      </Card>
    </div>
  );
}
