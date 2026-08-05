import { useState, useEffect } from 'react';
import { useRailwayStore } from '../../store/railwayStore';
import { Train, TrainStatus } from '../../types';
import { X, Save } from 'lucide-react';
import Card from '../ui/Card';

interface TrainEditorModalProps {
  isOpen: boolean;
  onClose: () => void;
  trainIdToEdit?: string | null;
}

const STATUS_OPTIONS: { value: TrainStatus; label: string }[] = [
  { value: 'in_attesa', label: 'In attesa' },
  { value: 'in_viaggio', label: 'In viaggio' },
  { value: 'in_stazione', label: 'In stazione' },
  { value: 'in_ritardo', label: 'In ritardo' },
  { value: 'guasto', label: 'Guasto' },
  { value: 'soppresso', label: 'Soppresso' },
];

/**
 * Modale amministrativo per creare un nuovo treno o modificare i dati (tratta, stato,
 * passeggeri, ritardo) di uno esistente (UC8).
 *
 * Il nome del convoglio si sceglie solo in creazione: è la chiave primaria del treno
 * sulla Centrale, quindi in modifica il campo è in sola lettura.
 */
export default function TrainEditorModal({ isOpen, onClose, trainIdToEdit }: TrainEditorModalProps) {
  const { trains, routes, adminCreateTrain, adminUpdateTrain } = useRailwayStore();

  const [formData, setFormData] = useState<Partial<Train>>({
    id: '',
    routeId: '',
    status: 'in_attesa',
    passengers: 0,
    delayMinutes: 0,
  });

  useEffect(() => {
    if (isOpen) {
      if (trainIdToEdit) {
        const existingTrain = trains.find(t => t.id === trainIdToEdit);
        if (existingTrain) {
          setFormData({ ...existingTrain });
        }
      } else {
        setFormData({
          id: '',
          routeId: '',
          status: 'in_attesa',
          currentStationId: null,
          nextStationId: null,
          previousStationId: null,
          direction: 'andata',
          arrivalTime: null,
          departureTime: null,
          passengers: 0,
          delayMinutes: 0,
          progressPercent: 0,
          lastUpdate: Date.now(),
        });
      }
    }
    // Non includiamo "trains" tra le dipendenze: l'array viene ricreato ad ogni
    // aggiornamento websocket (telemetria) e altrimenti l'effetto si rieseguirebbe
    // di continuo, azzerando i dati che l'utente sta ancora digitando nel form.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, trainIdToEdit]);

  if (!isOpen) return null;

  const handleSave = async () => {
    if (trainIdToEdit) {
      // Il nome del convoglio non è tra i campi modificabili: è la chiave primaria
      // del treno sulla Centrale, quindi si aggiornano solo stato e tratta.
      await adminUpdateTrain(trainIdToEdit, {
        routeId: formData.routeId,
        status: formData.status,
        passengers: formData.passengers,
        delayMinutes: formData.delayMinutes,
      });
    } else {
      // Il nome scritto nel form diventa l'identificativo del convoglio (chiave
      // primaria Treni.id_convoglio): deve esserci e deve essere univoco.
      const nomeConvoglio = (formData.id ?? '').trim();
      if (!nomeConvoglio) {
        alert('Inserisci il nome del convoglio (es. IC 351).');
        return;
      }
      if (trains.some(t => t.id === nomeConvoglio)) {
        alert('Esiste già un treno con questo nome di convoglio.');
        return;
      }
      await adminCreateTrain({ ...formData, id: nomeConvoglio } as Train);
    }
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <Card className="w-full max-w-xl max-h-[90vh] overflow-hidden flex flex-col shadow-2xl border-primary/20">
        <div className="flex justify-between items-center mb-6 border-b border-border-color pb-4">
          <h2 className="text-xl font-bold">
            {trainIdToEdit ? 'Modifica Treno' : 'Nuovo Treno'}
          </h2>
          <button onClick={onClose} className="p-1 hover:bg-white/10 rounded-full transition-colors">
            <X size={24} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto pr-2 space-y-6">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Convoglio</label>
              <input
                type="text"
                className="input-field w-full font-mono disabled:opacity-60 disabled:cursor-not-allowed"
                value={formData.id || ''}
                onChange={e => setFormData({ ...formData, id: e.target.value })}
                placeholder="es. IC 351"
                maxLength={50}
                disabled={!!trainIdToEdit}
              />
              {trainIdToEdit && (
                <p className="text-xs text-muted mt-1">
                  Il nome identifica il convoglio e non si può cambiare: per rinominarlo
                  elimina il treno e ricrealo.
                </p>
              )}
            </div>
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Stato</label>
              <select
                className="input-field w-full"
                value={formData.status || 'in_attesa'}
                onChange={e => setFormData({ ...formData, status: e.target.value as TrainStatus })}
              >
                {STATUS_OPTIONS.map(opt => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-muted mb-1">Tratta Assegnata</label>
            <select
              className="input-field w-full"
              value={formData.routeId || ''}
              onChange={e => setFormData({ ...formData, routeId: e.target.value })}
            >
              <option value="">Nessuna tratta</option>
              {routes.map(r => (
                <option key={r.id} value={r.id}>{r.name} ({r.code})</option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Passeggeri</label>
              <input
                type="number"
                min="0"
                className="input-field w-full"
                value={formData.passengers ?? 0}
                onChange={e => setFormData({ ...formData, passengers: parseInt(e.target.value) || 0 })}
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Ritardo (min)</label>
              <input
                type="number"
                min="0"
                className="input-field w-full"
                value={formData.delayMinutes ?? 0}
                onChange={e => setFormData({ ...formData, delayMinutes: parseInt(e.target.value) || 0 })}
              />
            </div>
          </div>
        </div>

        <div className="flex justify-end gap-3 mt-6 pt-4 border-t border-border-color">
          <button className="btn btn-outline" onClick={onClose}>
            Annulla
          </button>
          <button className="btn btn-primary" onClick={handleSave}>
            <Save size={18} /> Salva Treno
          </button>
        </div>
      </Card>
    </div>
  );
}
