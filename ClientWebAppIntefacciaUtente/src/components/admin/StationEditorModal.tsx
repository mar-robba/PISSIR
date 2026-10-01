import { useState, useEffect, useRef } from 'react';
import { useRailwayStore } from '../../store/railwayStore';
import { Station } from '../../types';
import { X, Save } from 'lucide-react';
import Card from '../ui/Card';
import CampoNumerico from '../ui/CampoNumerico';

interface StationEditorModalProps {
  isOpen: boolean;
  onClose: () => void;
  stationIdToEdit?: string | null;
}

/**
 * Modale amministrativo per registrare una nuova stazione nella rete
 * o modificare i dati (nome, coordinate) di una esistente.
 *
 * Il codice è l'id della stazione sulla Centrale (quello con cui si avvia il nodo), quindi
 * in modifica resta bloccato. Stato e binari non si scrivono da qui: lo stato lo decidono
 * il battito del nodo e i comandi del tecnico, i binari restano quelli dell'anagrafica.
 */
export default function StationEditorModal({ isOpen, onClose, stationIdToEdit }: StationEditorModalProps) {
  const { stations, adminCreateStation, adminUpdateStation } = useRailwayStore();
  const stationsRef = useRef(stations);
  stationsRef.current = stations;

  const [formData, setFormData] = useState<Partial<Station>>({
    id: '',
    code: '',
    name: '',
    coordinates: { x: 0, y: 0 },
  });

  useEffect(() => {
    if (isOpen) {
      if (stationIdToEdit) {
        const existingStation = stationsRef.current.find(s => s.id === stationIdToEdit);
        if (existingStation) {
          setFormData({ ...existingStation });
        }
      } else {
        setFormData({
          id: '',
          code: '',
          name: '',
          city: '',
          // Stato e binari il form non li chiede: sono i valori con cui la Centrale
          // registra una stazione nuova, OFFLINE finché il nodo non batte e un binario.
          status: 'offline',
          platforms: 1,
          coordinates: { x: 0, y: 0 },
          // Una stazione appena registrata non ha ancora battuto: il campo resta vuoto e
          // l'elenco mostra un trattino, finché non arriva il primo heartbeat vero.
          lastHeartbeat: null,
        });
      }
    }
  }, [isOpen, stationIdToEdit]);

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
    // La mappa del traffico piazza le stazioni proprio su queste due coordinate:
    // un valore fuori scala le manderebbe fuori dall'Italia (e schiaccerebbe
    // tutte le altre in un angolo), quindi conviene fermarlo qui.
    const latitudine = formData.coordinates?.x ?? 0;
    const longitudine = formData.coordinates?.y ?? 0;
    if (Math.abs(latitudine) > 90 || Math.abs(longitudine) > 180) {
      alert('Coordinate non valide: la latitudine va da -90 a 90, la longitudine da -180 a 180.');
      return;
    }

    if (stationIdToEdit) {
      await adminUpdateStation(stationIdToEdit, {
        name: formData.name,
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
              <label className="block text-sm font-medium text-muted mb-1">Latitudine</label>
              <CampoNumerico
                step="any"
                min="-90"
                max="90"
                className="input-field w-full font-mono"
                placeholder="es. 45.4869"
                valore={formData.coordinates?.x ?? 0}
                onCambia={x => setFormData({
                  ...formData,
                  coordinates: { x, y: formData.coordinates?.y ?? 0 }
                })}
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Longitudine</label>
              <CampoNumerico
                step="any"
                min="-180"
                max="180"
                className="input-field w-full font-mono"
                placeholder="es. 9.2050"
                valore={formData.coordinates?.y ?? 0}
                onCambia={y => setFormData({
                  ...formData,
                  coordinates: { x: formData.coordinates?.x ?? 0, y }
                })}
              />
            </div>
          </div>

          {/* Sono le coordinate con cui la mappa del traffico posiziona la
              stazione: lasciandole a zero la stazione si vede lo stesso, ma
              messa in base alle tratte invece che al posto suo. */}
          <p className="text-xs text-muted mt-2">
            Coordinate GPS in gradi decimali (WGS84): la mappa del traffico
            dispone le stazioni proprio su questi valori. Lasciandole vuote (o a 0)
            la stazione viene piazzata vicino a quelle a cui è collegata.
          </p>
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
