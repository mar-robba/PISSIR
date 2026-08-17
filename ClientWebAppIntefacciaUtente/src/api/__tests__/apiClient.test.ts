import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient, mapApiTrain } from '../apiClient';
import type { ApiAlertPayload, ApiTrainPayload } from '../apiTypes';

/**
 * La tratta arriva dalla Centrale orientata come sta a database (partenza → arrivo),
 * ma nella corsa di ritorno il convoglio la percorre al contrario. Leggendola alla
 * lettera la provenienza finiva sulla stessa stazione della destinazione e la mappa,
 * interpolando fra due punti coincidenti, disegnava il treno immobile sul nodo di
 * arrivo per tutta la tratta: metà di ogni viaggio.
 */

const treno = (campi: Partial<ApiTrainPayload> = {}): ApiTrainPayload => ({
  id: 'Mario',
  stato: 'attivo',
  posizioneAttualeTratta: {
    stazionePartenza: { id: 'MI' },
    stazioneArrivo: { id: 'Padova' },
  },
  itinerario: { id: 'rt-1' },
  progresso: 40,
  stazioneCorrente: null,
  prossimaStazione: null,
  direzione: 'andata',
  ...campi,
});

describe('mapApiTrain', () => {
  it('in corsa di ritorno legge gli estremi della tratta scambiati', () => {
    const mappato = mapApiTrain(treno({ direzione: 'ritorno', prossimaStazione: 'MI' }));

    expect(mappato.previousStationId).toBe('Padova');
    expect(mappato.nextStationId).toBe('MI');
    // È questa la condizione che la mappa non sopporta: due estremi coincidenti
    // valgono un treno fermo sul nodo, qualunque sia la percentuale di avanzamento.
    expect(mappato.previousStationId).not.toBe(mappato.nextStationId);
  });

  it('in corsa di andata la tratta si legge nel verso in cui è scritta', () => {
    const mappato = mapApiTrain(treno({ direzione: 'andata', prossimaStazione: 'Padova' }));

    expect(mappato.previousStationId).toBe('MI');
    expect(mappato.nextStationId).toBe('Padova');
  });

  it('a convoglio fermo l\'ultima stazione è quella in cui si trova, come dice la telemetria', () => {
    // Qui la tratta è quella appena percorsa (TO → MI): senza questo controllo lo
    // snapshot scriveva TO dove l'evento TELEMETRY scrive MI, e lo stesso campo
    // raccontava due cose diverse a seconda di quale dei due arrivava per ultimo.
    const mappato = mapApiTrain(treno({
      stato: 'fermo',
      progresso: 100,
      stazioneCorrente: 'MI',
      prossimaStazione: 'Padova',
      posizioneAttualeTratta: {
        stazionePartenza: { id: 'TO' },
        stazioneArrivo: { id: 'MI' },
      },
    }));

    expect(mappato.currentStationId).toBe('MI');
    expect(mappato.previousStationId).toBe('MI');
    expect(mappato.nextStationId).toBe('Padova');
    expect(mappato.status).toBe('in_stazione');
  });
});

/**
 * La presa in carico di un allarme (RF01.4.2) deve arrivare dalla Centrale e non restare
 * nello store del browser che ha premuto il pulsante: se stesse solo lì, sparirebbe al
 * ricaricamento della pagina e l'altro operatore vedrebbe l'allarme ancora libero, che è
 * esattamente il caso che il badge dovrebbe evitare.
 */
describe('getAlerts', () => {
  const rispondiCon = (allarmi: ApiAlertPayload[]) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => allarmi,
    }));
  };

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const allarme = (campi: Partial<ApiAlertPayload> = {}): ApiAlertPayload => ({
    id: 'G1',
    tipo: 'sensore_offline',
    severita: 'CRITICAL',
    messaggio: 'Sensore fuori servizio',
    sorgenteId: 'MI',
    sorgenteTipo: 'STAZIONE',
    timestamp: '2026-08-16T20:00:00Z',
    risolto: false,
    ...campi,
  });

  it('riporta chi ha preso in carico l\'allarme', async () => {
    rispondiCon([allarme({ operatore: 'Mario Rossi' })]);

    const [mappato] = await apiClient.getAlerts();

    expect(mappato.takenBy).toBe('Mario Rossi');
    expect(mappato.acknowledged).toBe(false);
  });

  it('un allarme che nessuno ha preso in carico non ha un assegnatario', async () => {
    rispondiCon([allarme({ operatore: null })]);

    const [mappato] = await apiClient.getAlerts();

    // undefined e non null: è la condizione su cui la pagina decide se mostrare il
    // pulsante "Prendi in carico" oppure il badge con il nome.
    expect(mappato.takenBy).toBeUndefined();
  });
});
