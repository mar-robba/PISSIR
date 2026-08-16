import { useMemo, useState } from 'react';
import { ArrowRightLeft, LogIn, LogOut, Search } from 'lucide-react';
import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';

/**
 * Storico dei transiti (RF01.5): per ogni passaggio si vede il convoglio, la stazione,
 * la tratta percorsa, il verso e l'istante in cui è avvenuto.
 *
 * La catena era già tutta montata — endpoint REST, chiamata, mappatura, tipo, store e
 * aggiornamento in tempo reale sull'evento TRANSIT — ma nessun componente leggeva
 * `transits`: i dati arrivavano nel browser e restavano lì senza che nessuno potesse
 * consultarli. Questa pagina è l'ultimo pezzo.
 */
export default function TransitsPage() {
  const transits = useRailwayStore((s) => s.transits);
  const stations = useRailwayStore((s) => s.stations);
  const trackSegments = useRailwayStore((s) => s.trackSegments);
  const [search, setSearch] = useState('');
  const [verso, setVerso] = useState<'tutti' | 'ingresso' | 'uscita'>('tutti');

  const nomeStazione = (id: string) => stations.find((s) => s.id === id)?.name ?? id;

  /** Descrizione leggibile della tratta: "Milano Centrale → Bologna Centrale". */
  const descrizioneTratta = (trackSegmentId: string | null) => {
    if (!trackSegmentId) return '—';
    const arco = trackSegments.find((t) => t.id === trackSegmentId);
    if (!arco) return trackSegmentId;
    return `${nomeStazione(arco.departureStationId)} → ${nomeStazione(arco.arrivalStationId)}`;
  };

  const transitiVisibili = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    return transits
      .filter((t) => (verso === 'tutti' ? true : t.type === verso))
      .filter((t) => !query
        || t.trainId.toLocaleLowerCase().includes(query)
        || nomeStazione(t.stationId).toLocaleLowerCase().includes(query)
        || t.stationId.toLocaleLowerCase().includes(query))
      // Ordine cronologico decrescente: il timestamp è quello del passaggio, non quello
      // di arrivo del messaggio, quindi i transiti rimandati dal buffer di una stazione
      // dopo un'interruzione si rimettono al posto giusto invece di accodarsi in testa.
      .slice()
      .sort((a, b) => b.timestamp - a.timestamp);
  }, [transits, search, verso, stations]);

  return (
    <div className="flex flex-col gap-6 animate-fade-in h-full">
      <div className="flex justify-between items-center flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold mb-1">Storico Transiti</h2>
          <p className="text-muted text-sm">
            Passaggi rilevati dai sensori di terra: convoglio, stazione, tratta, verso e istante.
          </p>
        </div>

        <div className="flex gap-2 p-1 bg-black/20 rounded-md border border-border-color">
          {(['tutti', 'ingresso', 'uscita'] as const).map((valore) => (
            <button
              key={valore}
              className={`px-4 py-1.5 rounded text-sm transition-colors ${verso === valore ? 'bg-primary text-white' : 'hover:bg-white/5'}`}
              onClick={() => setVerso(valore)}
            >
              {valore === 'tutti' ? 'Tutti' : valore === 'ingresso' ? 'Ingressi' : 'Uscite'}
            </button>
          ))}
        </div>
      </div>

      <Card title={`Transiti registrati (${transitiVisibili.length})`} className="flex flex-col">
        <div className="mb-4 flex items-center gap-2">
          <Search size={16} className="text-muted" />
          <input
            type="text"
            placeholder="Cerca per convoglio o stazione..."
            className="input-field w-full max-w-md"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>

        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Convoglio</th>
                <th>Stazione</th>
                <th>Tratta</th>
                <th>Verso</th>
                <th>Istante</th>
                <th>Ritardo</th>
              </tr>
            </thead>
            <tbody>
              {transitiVisibili.map((transit) => (
                <tr key={transit.id}>
                  <td className="font-mono text-sm font-bold text-primary">{transit.trainId}</td>
                  <td className="font-medium">{nomeStazione(transit.stationId)}</td>
                  <td className="text-sm text-muted">{descrizioneTratta(transit.trackSegmentId)}</td>
                  <td>
                    <Badge type={transit.type === 'ingresso' ? 'info' : 'neutral'}>
                      <span className="flex items-center gap-1">
                        {transit.type === 'ingresso' ? <LogIn size={13} /> : <LogOut size={13} />}
                        {transit.type.toUpperCase()}
                      </span>
                    </Badge>
                  </td>
                  <td className="text-sm font-mono">{new Date(transit.timestamp).toLocaleString()}</td>
                  <td>
                    {transit.delayMinutes > 0 ? (
                      <span className="text-danger font-medium">+{transit.delayMinutes} min</span>
                    ) : (
                      <span className="text-success text-sm">In orario</span>
                    )}
                  </td>
                </tr>
              ))}
              {transitiVisibili.length === 0 && (
                <tr>
                  <td colSpan={6} className="text-center text-muted p-8">
                    <ArrowRightLeft size={32} className="mb-2 opacity-20 inline-block" />
                    <p className="m-0">Nessun transito da mostrare per il filtro selezionato.</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
