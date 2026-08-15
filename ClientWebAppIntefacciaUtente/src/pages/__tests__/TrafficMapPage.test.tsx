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

const stazione = (id: string, code: string, name: string): Station => ({
  id,
  code,
  name,
  city: name,
  status: 'operativa',
  coordinates: { x: 0, y: 0 },
  lastHeartbeat: Date.now(),
  platforms: 4,
});

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
      stazione('st-mi', 'MI', 'Milano Centrale'),
      stazione('st-to', 'TO', 'Torino Porta Nuova'),
      stazione('st-pa', 'PA', 'Palermo Centrale'),
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

  it('apre la scheda della stazione cliccando direttamente il suo simbolo', () => {
    const { container } = render(<TrafficMapPage />);

    const simboloTorino = [...container.querySelectorAll('.map-marker')]
      .find(marker => marker.querySelector('title')?.textContent?.startsWith('Torino'))!;
    fireEvent.click(simboloTorino);

    expect(screen.getByRole('heading', { name: 'Torino Porta Nuova' })).toBeInTheDocument();
    expect(screen.getByText('Binari')).toBeInTheDocument();
  });
});
