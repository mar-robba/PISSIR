import { useMemo, useState } from 'react';
import { ArrowRight, Search, Timer } from 'lucide-react';
import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import './RoutesPage.css';

/** Consultazione delle tratte fisiche, separata dagli itinerari composti. */
export default function TrackSegmentsPage() {
  const { trackSegments, stations } = useRailwayStore();
  const [search, setSearch] = useState('');
  const stationName = (id: string) => stations.find((s) => s.id === id)?.name ?? id;
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return !q ? trackSegments : trackSegments.filter((t) =>
      `${t.id} ${stationName(t.departureStationId)} ${stationName(t.arrivalStationId)}`.toLowerCase().includes(q));
  }, [trackSegments, search, stations]);

  return <div className="routes-page animate-fade-in">
    <div className="routes-page-heading"><div><span className="routes-eyebrow">Rete ferroviaria</span><h2>Tratte fisiche</h2><p>Collegamenti diretti tra due stazioni della rete.</p></div></div>
    <div className="routes-workspace"><Card className="routes-list-card"><div className="routes-card-heading"><div><h3>Tratte disponibili</h3><p>{filtered.length} collegamenti</p></div><label className="routes-search"><Search size={17}/><input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Cerca per codice o stazione"/></label></div>
      <div className="table-container"><table><thead><tr><th>Codice</th><th>Partenza</th><th>Arrivo</th><th>Percorrenza</th></tr></thead><tbody>{filtered.map((t) => <tr key={t.id}><td className="font-mono text-primary">{t.id}</td><td>{stationName(t.departureStationId)}</td><td><span className="flex items-center gap-2"><ArrowRight size={15}/>{stationName(t.arrivalStationId)}</span></td><td><span className="flex items-center gap-2"><Timer size={15}/>{t.travelTimeMinutes} min</span></td></tr>)}</tbody></table></div>
      {filtered.length === 0 && <div className="routes-no-results"><Search size={24}/><p>Nessuna tratta corrisponde alla ricerca.</p></div>}</Card></div>
  </div>;
}
