import { useState, useEffect } from 'react';
import { useRailwayStore } from '../../store/railwayStore';
import { Route } from '../../types';
import { ErroreApi } from '../../api/apiClient';
import type { ApiCoppiaMancante } from '../../api/apiTypes';
import { X, Save, Trash2, ArrowUp, ArrowDown, AlertTriangle } from 'lucide-react';
import Card from '../ui/Card';

interface RouteEditorModalProps {
  isOpen: boolean;
  onClose: () => void;
  routeIdToEdit?: string | null;
}

/** Il salvataggio non riuscito, da mostrare in testa al form. */
interface Rifiuto {
  messaggio: string;
  /** Le coppie di stazioni per cui in rete non esiste nessuna tratta. */
  coppie: ApiCoppiaMancante[];
  /** true se il rifiuto viene dalla Centrale: solo in quel caso l'editing è stato buttato via. */
  editingAzzerato: boolean;
}

export default function RouteEditorModal({ isOpen, onClose, routeIdToEdit }: RouteEditorModalProps) {
  const { routes, stations, trackSegments, updateRoute, addRoute } = useRailwayStore();

  // Local state for the route being edited
  const [formData, setFormData] = useState<Partial<Route>>({
    name: '',
    code: '',
    stationIds: [],
    travelTimes: [],
    active: true,
  });
  const [rifiuto, setRifiuto] = useState<Rifiuto | null>(null);
  const [salvataggioInCorso, setSalvataggioInCorso] = useState(false);

  /**
   * I dati da cui il form parte quando si apre e a cui torna quando la Centrale
   * rifiuta il salvataggio: l'itinerario così com'è salvato, oppure uno vuoto se
   * lo si sta creando.
   */
  const datiSalvati = (): Partial<Route> => {
    const esistente = routeIdToEdit ? routes.find(r => r.id === routeIdToEdit) : undefined;
    if (esistente) return { ...esistente };
    return {
      id: '',
      name: '',
      code: '',
      stationIds: [],
      trainIds: [],
      travelTimes: [],
      active: true,
      createdAt: Date.now(),
    };
  };

  useEffect(() => {
    if (isOpen) {
      setFormData(datiSalvati());
      setRifiuto(null);
    }
    // Non includiamo "routes" tra le dipendenze: altrimenti l'effetto si
    // rieseguirebbe ad ogni aggiornamento dello store, azzerando i dati che
    // l'utente sta ancora digitando nel form (stesso problema di TrainEditorModal).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, routeIdToEdit]);

  if (!isOpen) return null;

  const stationIds = formData.stationIds ?? [];
  const travelTimes = formData.travelTimes ?? [];

  /**
   * I tempi seguono le COPPIE di stazioni, non le posizioni nell'elenco: per ogni
   * coppia consecutiva si tiene il tempo che quella stessa coppia aveva già (anche
   * se corretto a mano), se no quello dell'arco registrato in rete, se no un default.
   * Così aggiungere, togliere o spostare una stazione non fa slittare i tempi delle
   * coppie rimaste uguali.
   */
  const tempiPerElenco = (elenco: string[]): number[] =>
    elenco.slice(0, -1).map((daId, i) => {
      const aId = elenco[i + 1];
      const posizioneAttuale = stationIds.findIndex((id, j) => id === daId && stationIds[j + 1] === aId);
      if (posizioneAttuale >= 0 && travelTimes[posizioneAttuale] !== undefined) {
        return travelTimes[posizioneAttuale];
      }
      const arco = trackSegments.find(
        t => t.departureStationId === daId && t.arrivalStationId === aId
      );
      return arco?.travelTimeMinutes ?? 15;
    });

  /** Sostituisce l'elenco delle stazioni tenendo allineati i tempi di percorrenza. */
  const aggiornaElenco = (elenco: string[]) =>
    setFormData({ ...formData, stationIds: elenco, travelTimes: tempiPerElenco(elenco) });

  /**
   * Si manda alla Centrale l'itinerario così come è stato composto, senza controllare
   * niente qui: è la Centrale a sapere quali tratte esistono davvero in rete, e le
   * verifica tutte PRIMA di scrivere (quindi un rifiuto non lascia niente a metà).
   * Se rifiuta, l'editing viene buttato via e si riparte dalla versione salvata, con
   * l'elenco dei collegamenti da registrare in testa al form.
   */
  const handleSave = async () => {
    setRifiuto(null);
    setSalvataggioInCorso(true);
    try {
      if (routeIdToEdit) {
        // trainIds NON si manda: questo form non gestisce le assegnazioni dei convogli
        // (non c'è nessun campo per sceglierle, si vede solo il conteggio) e rimandare
        // indietro la lista scaricata all'avvio sganciava i treni che nel frattempo erano
        // stati assegnati dalla pagina Amministrazione. Un campo che una schermata non
        // gestisce è meglio che non lo tocchi affatto.
        const { trainIds, ...senzaAssegnazioni } = formData;
        void trainIds;
        await updateRoute(routeIdToEdit, senzaAssegnazioni);
      } else {
        // L'id si manda vuoto apposta: lo genera il server (IT-xxxxxxxx), così non c'è
        // un id scelto a mano da controllare per l'unicità.
        await addRoute({ ...formData, id: '' } as Route);
      }
      onClose();
    } catch (err) {
      console.error('Salvataggio itinerario non riuscito', err);
      // Solo se la Centrale ha risposto si sa che sul server non è cambiato niente e
      // si può ripartire dalla versione salvata: se invece è saltata la connessione
      // l'editing resta lì, così non si butta via il lavoro per un problema di rete.
      const rifiutatoDallaCentrale = err instanceof ErroreApi;
      if (rifiutatoDallaCentrale) setFormData(datiSalvati());
      setRifiuto({
        messaggio: err instanceof Error && err.message
          ? err.message
          : 'La Centrale non ha dato altri dettagli.',
        coppie: rifiutatoDallaCentrale ? err.coppieMancanti : [],
        editingAzzerato: rifiutatoDallaCentrale,
      });
    } finally {
      setSalvataggioInCorso(false);
    }
  };

  const addStation = (stationId: string) => {
    if (stationIds.includes(stationId)) return; // prevent duplicates for simplicity
    aggiornaElenco([...stationIds, stationId]);
  };

  const removeStation = (index: number) => {
    aggiornaElenco(stationIds.filter((_, i) => i !== index));
  };

  const updateTravelTime = (index: number, minutes: number) => {
    const newTravelTimes = [...travelTimes];
    newTravelTimes[index] = minutes;
    setFormData({ ...formData, travelTimes: newTravelTimes });
  };

  const moveStation = (index: number, direction: 'up' | 'down') => {
    const targetIndex = direction === 'up' ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= stationIds.length) return;

    const elenco = [...stationIds];
    [elenco[index], elenco[targetIndex]] = [elenco[targetIndex], elenco[index]];
    aggiornaElenco(elenco);
  };

  const availableStations = stations.filter(s => !stationIds.includes(s.id));

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
          {/* La notifica del rifiuto: le modifiche sono già state annullate, qui si dice
              solo quali collegamenti mancano in rete. Restano visibili finché non si
              riprova a salvare, così si possono andare a registrare nella pagina Tratte. */}
          {rifiuto && (
            <div className="p-4 bg-danger/10 border border-danger/50 rounded-md space-y-2">
              <div className="flex items-center gap-2 text-danger font-semibold">
                <AlertTriangle size={18} />
                {rifiuto.editingAzzerato
                  ? "Modifiche annullate: la Centrale ha rifiutato l'itinerario"
                  : "Itinerario non salvato: la Centrale non ha risposto"}
              </div>
              {rifiuto.coppie.length > 0 ? (
                <>
                  <p className="text-sm text-muted">
                    Queste tratte non esistono in rete: registrale nella pagina Tratte e poi
                    rifai la modifica.
                  </p>
                  <ul className="text-sm space-y-1">
                    {rifiuto.coppie.map(c => (
                      <li key={`${c.partenzaId}-${c.arrivoId}`} className="font-mono">
                        {c.partenzaNome} &rarr; {c.arrivoNome}
                      </li>
                    ))}
                  </ul>
                </>
              ) : (
                <p className="text-sm text-muted">{rifiuto.messaggio}</p>
              )}
            </div>
          )}

          {/* Nome e codice non si scrivono, né qui né in modifica: sono solo informativi.
              Il codice è l'id dell'itinerario e lo assegna il server al salvataggio
              (IT-xxxxxxxx), poi resta perché ci puntano le chiavi esterne degli storici.
              Il nome non esiste nemmeno come colonna: il server lo ricompone a ogni GET
              concatenando i nomi delle stazioni attraversate. Prima erano due campi di
              testo, ma POST e PUT /api/tratte non li leggono: si scrivevano, si salvava
              e al reload tornavano quelli decisi dal server. */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Nome Tratta</label>
              <div className="p-2 bg-black/20 border border-border-color rounded-md">
                {formData.name || <span className="text-muted">— dalle stazioni scelte</span>}
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-muted mb-1">Codice Tratta</label>
              <div className="p-2 bg-black/20 border border-border-color rounded-md font-mono">
                {formData.code || <span className="text-muted">— assegnato al salvataggio</span>}
              </div>
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
                  {availableStations.map(stazione => (
                    <option key={stazione.id} value={stazione.id}>
                      {stazione.name} ({stazione.code})
                    </option>
                  ))}
                </select>
              </div>
            </h3>

            {/* Le tratte elementari sono archi FISICI della rete e possono essere
                condivise da più itinerari (es. Milano-Bologna sta sia sull'itinerario
                per Napoli sia su quello per Roma): il tempo di percorrenza di un arco
                già usato altrove si modifica dalla pagina "Tratte", non da qui. */}
            {stationIds.length === 0 ? (
              <div className="text-center p-6 bg-black/20 rounded-md border border-dashed border-border-color text-muted">
                Nessuna stazione presente in questa tratta. Aggiungine almeno due.
              </div>
            ) : (
              <div className="space-y-2">
                {stationIds.map((stationId, index) => {
                  const station = stations.find(s => s.id === stationId);
                  if (!station) return null;

                  const primaPosizione = index === 0;
                  const ultimaPosizione = index === stationIds.length - 1;

                  return (
                    <div key={`${stationId}-${index}`}>
                      <div className="flex items-center gap-3 p-3 bg-black/20 border border-border-color rounded-md">
                        <div className="flex flex-col gap-1">
                          <button
                            disabled={primaPosizione}
                            title="Sposta la stazione più in alto"
                            onClick={() => moveStation(index, 'up')}
                            className="p-1 disabled:opacity-30 hover:bg-white/10 rounded"
                          >
                            <ArrowUp size={14} />
                          </button>
                          <button
                            disabled={ultimaPosizione}
                            title="Sposta la stazione più in basso"
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
                          title="Togli la stazione dall'itinerario"
                          onClick={() => removeStation(index)}
                          className="p-2 text-danger hover:bg-danger/10 rounded transition-colors"
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>

                      {index < stationIds.length - 1 && (
                        <div className="flex items-center justify-center my-2 group">
                          <div className="w-1 h-8 border-l-2 border-dashed border-border-color"></div>
                          <div className="absolute bg-bg-panel px-3 border border-border-color rounded-full flex items-center gap-2">
                            <span className="text-xs text-muted">Tempo previsto:</span>
                            <input
                              type="number"
                              min="1"
                              className="input-field text-center text-xs py-1 w-16"
                              value={travelTimes[index] || 0}
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

        <div className="flex justify-end items-center gap-3 mt-6 pt-4 border-t border-border-color">
          <button className="btn btn-outline" onClick={onClose}>
            Annulla
          </button>
          <button
            className="btn btn-primary disabled:opacity-40"
            disabled={salvataggioInCorso}
            title="Manda l'itinerario alla Centrale"
            onClick={handleSave}
          >
            <Save size={18} /> {salvataggioInCorso ? 'Invio...' : 'Salva Tratta'}
          </button>
        </div>
      </Card>
    </div>
  );
}
