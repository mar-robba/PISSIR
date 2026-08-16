import { describe, expect, it, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import TrafficMapPage from '../TrafficMapPage';
import { useRailwayStore } from '../../store/railwayStore';
import type { Route, Station, Train } from '../../types';

/**
 * Verifica il caso che rendeva la mappa illeggibile: treni fermi in stazione,
 * quindi disegnati esattamente sopra il nodo della stazione. Devono restare
 * distinguibili e si deve poter scegliere di quale elemento aprire la scheda.
 */

/**
 * Le coordinate sono quelle vere delle tre stazioni: coordinates.x è la
 * latitudine e coordinates.y la longitudine (è la mappatura di apiClient).
 */
const stazione = (
  id: string,
  code: string,
  name: string,
  latitudine: number,
  longitudine: number,
): Station => ({
  id,
  code,
  name,
  city: name,
  status: 'operativa',
  coordinates: { x: latitudine, y: longitudine },
  lastHeartbeat: Date.now(),
  platforms: 4,
});

/** Legge dal transform del marker il punto in cui è stato disegnato. */
const puntoDelMarker = (contenitore: HTMLElement, nome: string): { x: number; y: number } => {
  const marker = [...contenitore.querySelectorAll('.map-marker')]
    .find(nodo => nodo.querySelector('title')?.textContent?.startsWith(nome)) as SVGGElement;
  const numeri = marker.style.transform.match(/-?\d+(\.\d+)?/g)!;
  return { x: parseFloat(numeri[0]), y: parseFloat(numeri[1]) };
};

const treno = (id: string, campi: Partial<Train> = {}): Train => ({
  id,
  status: 'in_stazione',
  currentStationId: 'st-mi',
  nextStationId: null,
  previousStationId: null,
  routeId: 'r-1',
  direction: 'andata',
  arrivalTime: null,
  departureTime: null,
  delayMinutes: 0,
  passengers: 42,
  lastUpdate: Date.now(),
  progressPercent: 0,
  ...campi,
});

const itinerario: Route = {
  id: 'r-1',
  name: 'Linea di prova',
  code: 'MI-TO-001',
  stationIds: ['st-mi', 'st-to', 'st-pa'],
  trainIds: [],
  travelTimes: [30, 30],
  active: true,
  createdAt: Date.now(),
};

beforeEach(() => {
  useRailwayStore.setState({
    stations: [
      stazione('st-mi', 'MI', 'Milano Centrale', 45.4869, 9.2050),
      stazione('st-to', 'TO', 'Torino Porta Nuova', 45.0625, 7.6784),
      stazione('st-pa', 'PA', 'Palermo Centrale', 38.1109, 13.3316),
    ],
    trains: [
      treno('IC 100'),
      treno('IC 200'),
      treno('REG 300', {
        status: 'in_viaggio',
        currentStationId: null,
        previousStationId: 'st-to',
        nextStationId: 'st-pa',
        progressPercent: 50,
      }),
    ],
    routes: [itinerario],
    trackSegments: [],
  });
});

describe('TrafficMapPage', () => {
  it('dispone le stazioni secondo latitudine e longitudine', () => {
    const { container } = render(<TrafficMapPage />);

    const milano = puntoDelMarker(container, 'Milano');
    const torino = puntoDelMarker(container, 'Torino');
    const palermo = puntoDelMarker(container, 'Palermo');

    // Torino sta a ovest di Milano, Palermo a sud-est di tutte e due.
    expect(torino.x).toBeLessThan(milano.x);
    expect(palermo.x).toBeGreaterThan(milano.x);
    expect(milano.y).toBeLessThan(torino.y); // Milano è più a nord, quindi più in alto
    expect(palermo.y).toBeGreaterThan(torino.y);

    // La proporzione fra i due assi è quella della proiezione: la longitudine è
    // compressa di cos(latitudine media), se no la mappa verrebbe fuori larga.
    const latMedia = (45.4869 + 45.0625 + 38.1109) / 3;
    const compressione = Math.cos((latMedia * Math.PI) / 180);
    const attesa = ((13.3316 - 9.2050) * compressione) / (45.4869 - 38.1109);
    expect((palermo.x - milano.x) / (palermo.y - milano.y)).toBeCloseTo(attesa, 5);
  });

  it('avvisa e tratteggia la stazione a cui mancano le coordinate GPS', () => {
    // Caso della stazione appena creata dal pannello di amministrazione senza
    // compilare latitudine e longitudine: (0, 0) non è una posizione vera.
    useRailwayStore.setState({
      stations: [
        stazione('st-mi', 'MI', 'Milano Centrale', 45.4869, 9.2050),
        stazione('st-to', 'TO', 'Torino Porta Nuova', 45.0625, 7.6784),
        stazione('st-pa', 'PA', 'Palermo Centrale', 38.1109, 13.3316),
        stazione('st-xx', 'XX', 'Stazione Nuova', 0, 0),
      ],
      trains: [],
    });

    const { container } = render(<TrafficMapPage />);

    expect(
      screen.getByText('1 stazione senza coordinate GPS valide, messa vicino a quelle collegate'),
    ).toBeInTheDocument();
    expect(container.querySelectorAll('.map-marker__simbolo[stroke-dasharray]')).toHaveLength(1);

    // Le altre tre restano dove dice il GPS: la nuova non deve spostarle.
    expect(puntoDelMarker(container, 'Torino').x)
      .toBeLessThan(puntoDelMarker(container, 'Milano').x);
  });

  it('non lascia che una coordinata fuori scala schiacci il resto della rete', () => {
    // Caso vero trovato in banca dati: una stazione con longitudine 450, cioè
    // fuori dal mondo. Proiettandola le altre finirebbero tutte in un punto solo.
    useRailwayStore.setState({
      stations: [
        stazione('st-mi', 'MI', 'Milano Centrale', 45.4869, 9.2050),
        stazione('st-to', 'TO', 'Torino Porta Nuova', 45.0625, 7.6784),
        stazione('st-pa', 'PA', 'Palermo Centrale', 38.1109, 13.3316),
        stazione('st-zz', 'ZZ', 'Stazione Sballata', 25, 450),
      ],
      trains: [],
    });

    const { container } = render(<TrafficMapPage />);

    const milano = puntoDelMarker(container, 'Milano');
    const palermo = puntoDelMarker(container, 'Palermo');
    expect(Math.hypot(palermo.x - milano.x, palermo.y - milano.y)).toBeGreaterThan(300);
    expect(
      screen.getByText('1 stazione senza coordinate GPS valide, messa vicino a quelle collegate'),
    ).toBeInTheDocument();
  });

  it('separa i simboli sovrapposti invece di disegnarli uno sopra l\'altro', () => {
    const { container } = render(<TrafficMapPage />);

    const posizioni = [...container.querySelectorAll('.map-marker')]
      .map(marker => (marker as SVGGElement).style.transform);

    expect(posizioni).toHaveLength(6); // 3 stazioni + 3 treni
    expect(new Set(posizioni).size).toBe(posizioni.length);
  });

  it('segnala con un disco numerato quanti elementi stanno nello stesso punto', () => {
    const { container } = render(<TrafficMapPage />);

    const conteggi = [...container.querySelectorAll('.map-cluster-hub__conteggio')]
      .map(nodo => nodo.textContent);

    // Milano: la stazione più i due treni fermi lì.
    expect(conteggi).toEqual(['3']);
  });

  it('permette di scegliere quale scheda aprire fra gli elementi sovrapposti', () => {
    const { container } = render(<TrafficMapPage />);

    fireEvent.click(container.querySelector('.map-cluster-hub')!);

    const selettore = screen.getByText('3 elementi qui').closest('.map-chooser') as HTMLElement;
    expect(within(selettore).getByText('Milano Centrale')).toBeInTheDocument();
    expect(within(selettore).getByText('IC 100')).toBeInTheDocument();
    expect(within(selettore).getByText('IC 200')).toBeInTheDocument();

    fireEvent.click(within(selettore).getByText('IC 200'));

    // La scheda aperta è quella del treno scelto, non quella della stazione.
    expect(screen.queryByText('3 elementi qui')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'IC 200' })).toBeInTheDocument();
    expect(screen.getByText('Passeggeri')).toBeInTheDocument();
  });

  it('non inventa una posizione quando i due estremi della tratta coincidono', () => {
    // È lo stato che arrivava dallo snapshot per tutta la corsa di ritorno: interpolando
    // fra una stazione e sé stessa il convoglio veniva disegnato immobile sul nodo di
    // arrivo, come se fosse già arrivato. Meglio dichiararlo non localizzabile.
    useRailwayStore.setState({
      trains: [
        treno('REG 400', {
          status: 'in_viaggio',
          currentStationId: null,
          previousStationId: 'st-pa',
          nextStationId: 'st-pa',
          progressPercent: 60,
        }),
      ],
    });

    const { container } = render(<TrafficMapPage />);

    expect(container.querySelectorAll('.map-marker')).toHaveLength(3); // le sole stazioni
    expect(screen.getByText('1 treni senza posizione nota')).toBeInTheDocument();
  });

  it('tiene in stazione il convoglio che ne dichiara una, anche se lo stato dice "in viaggio"', () => {
    // È la finestra fra il passaggio (che la Centrale registra subito) e il frame di
    // telemetria successivo (fino a cinque secondi dopo): lo snapshot metteva insieme lo
    // stato vecchio e la posizione nuova, e il treno appena arrivato a Torino veniva
    // disegnato al 97% della tratta verso Palermo, cioè quasi sopra la stazione dopo.
    useRailwayStore.setState({
      trains: [
        treno('REG 500', {
          status: 'in_viaggio',
          currentStationId: 'st-to',
          previousStationId: 'st-mi',
          nextStationId: 'st-pa',
          progressPercent: 97,
        }),
      ],
    });

    const { container } = render(<TrafficMapPage />);
    fireEvent.click(container.querySelector('.map-cluster-hub')!);

    const selettore = screen.getByText('2 elementi qui').closest('.map-chooser') as HTMLElement;
    expect(within(selettore).getByText('Torino Porta Nuova')).toBeInTheDocument();
    expect(within(selettore).getByText('REG 500')).toBeInTheDocument();
  });

  it('mostra la barra di avanzamento solo per un treno che sta percorrendo una tratta', () => {
    const { container } = render(<TrafficMapPage />);
    const simbolo = (nome: string) => [...container.querySelectorAll('.map-marker')]
      .find(marker => marker.querySelector('title')?.textContent?.startsWith(nome))!;

    // In viaggio: la percentuale ha un senso e la barra dice fra quali stazioni.
    fireEvent.click(simbolo('REG 300'));
    expect(screen.getByText('50%')).toBeInTheDocument();

    // Fermo in stazione: il 100% della tratta precedente non è un avanzamento,
    // era solo un residuo dell'ultimo frame di telemetria rimasto a schermo.
    fireEvent.click(container.querySelector('.map-cluster-hub')!);
    const selettore = screen.getByText('3 elementi qui').closest('.map-chooser') as HTMLElement;
    fireEvent.click(within(selettore).getByText('IC 100'));
    expect(screen.queryByText('100%')).not.toBeInTheDocument();
    expect(screen.getByText('In sosta a Milano Centrale')).toBeInTheDocument();
  });

  it('apre la scheda della stazione cliccando direttamente il suo simbolo', () => {
    const { container } = render(<TrafficMapPage />);

    const simboloTorino = [...container.querySelectorAll('.map-marker')]
      .find(marker => marker.querySelector('title')?.textContent?.startsWith('Torino'))!;
    fireEvent.click(simboloTorino);

    expect(screen.getByRole('heading', { name: 'Torino Porta Nuova' })).toBeInTheDocument();
    expect(screen.getByText('Binari')).toBeInTheDocument();
  });
});
