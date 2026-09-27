import { describe, expect, it } from 'vitest';
import { mapApiTrain } from '../apiClient';
import type { ApiTrainPayload } from '../apiTypes';

/**
 * Gli estremi della tratta su cui la mappa disegna il convoglio li decide la CENTRALE,
 * non questo mappatore.
 *
 * Prima venivano ricavati qui da posizioneAttualeTratta, che però la scrive solo il
 * passaggio rilevato dai sensori di terra: con una stazione spenta quel campo resta
 * fermo sull'ultima tratta confermata mentre il convoglio è già andato avanti, e la
 * provenienza vecchia finiva accanto alla destinazione aggiornata. La coppia che ne
 * usciva non era una tratta della rete — il caso reale era un convoglio disegnato in
 * viaggio da Milano a Berlino, due stazioni che nessun binario collega.
 *
 * Adesso la Centrale incrocia le due fonti e pubblica il risultato in partenzaCorrente/
 * arrivoCorrente/posizioneNota; qui si traduce e basta, null compresi.
 */

const treno = (campi: Partial<ApiTrainPayload> = {}): ApiTrainPayload => ({
  id: 'Mario',
  stato: 'attivo',
  // Resta nel payload perché la Centrale continua a mandarlo (serve alla pagina
  // Transiti): questi test controllano proprio che la posizione NON venga più di qui.
  posizioneAttualeTratta: {
    stazionePartenza: { id: 'MI' },
    stazioneArrivo: { id: 'Padova' },
  },
  itinerario: { id: 'rt-1' },
  progresso: 40,
  stazioneCorrente: null,
  prossimaStazione: null,
  direzione: 'andata',
  partenzaCorrente: null,
  arrivoCorrente: null,
  direzioneCorrente: null,
  posizioneNota: false,
  incertezzaPosizione: null,
  ...campi,
});

describe('mapApiTrain', () => {
  it('prende gli estremi della tratta da quelli garantiti dalla Centrale', () => {
    const mappato = mapApiTrain(treno({
      partenzaCorrente: 'MI',
      arrivoCorrente: 'Padova',
      direzioneCorrente: 'andata',
      posizioneNota: true,
      prossimaStazione: 'Padova',
    }));

    expect(mappato.previousStationId).toBe('MI');
    expect(mappato.nextStationId).toBe('Padova');
    expect(mappato.direction).toBe('andata');
    expect(mappato.positionKnown).toBe(true);
  });

  it('in corsa di ritorno la Centrale ha già scambiato gli estremi', () => {
    // La tratta a database resta MI → Padova, ma percorsa al contrario: gli estremi
    // arrivano già letti nel verso di marcia, qui non si gira più niente.
    const mappato = mapApiTrain(treno({
      direzione: 'ritorno',
      prossimaStazione: 'MI',
      partenzaCorrente: 'Padova',
      arrivoCorrente: 'MI',
      direzioneCorrente: 'ritorno',
      posizioneNota: true,
    }));

    expect(mappato.previousStationId).toBe('Padova');
    expect(mappato.nextStationId).toBe('MI');
    expect(mappato.direction).toBe('ritorno');
    // È questa la condizione che la mappa non sopporta: due estremi coincidenti
    // valgono un treno fermo sul nodo, qualunque sia la percentuale di avanzamento.
    expect(mappato.previousStationId).not.toBe(mappato.nextStationId);
  });

  it('a convoglio fermo l\'ultima stazione è quella in cui si trova, come dice la telemetria', () => {
    const mappato = mapApiTrain(treno({
      stato: 'fermo',
      progresso: 100,
      stazioneCorrente: 'MI',
      prossimaStazione: 'Padova',
      partenzaCorrente: 'MI',
      arrivoCorrente: 'Padova',
      direzioneCorrente: 'andata',
      posizioneNota: true,
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

  it('con la partenza non confermata non ricostruisce la tratta dal campo vecchio', () => {
    // È il caso della stazione spenta: la destinazione la sa (la dichiara il convoglio),
    // la provenienza no. posizioneAttualeTratta contiene ancora una tratta plausibile e
    // prima bastava a comporre una corsa che nella rete non esiste.
    const mappato = mapApiTrain(treno({
      direzione: 'ritorno',
      prossimaStazione: 'BE',
      partenzaCorrente: null,
      arrivoCorrente: 'BE',
      direzioneCorrente: 'ritorno',
      posizioneNota: false,
      incertezzaPosizione: 'PARTENZA_SCONOSCIUTA',
    }));

    expect(mappato.previousStationId).toBeNull();
    expect(mappato.nextStationId).toBe('BE');
    expect(mappato.positionKnown).toBe(false);
    expect(mappato.positionDoubt).toBe('PARTENZA_SCONOSCIUTA');
  });

  it('senza telemetria recente non garantisce nemmeno la direzione', () => {
    const mappato = mapApiTrain(treno({
      direzione: 'andata',
      posizioneNota: false,
      incertezzaPosizione: 'NESSUNA_TELEMETRIA',
    }));

    expect(mappato.previousStationId).toBeNull();
    expect(mappato.nextStationId).toBeNull();
    expect(mappato.direction).toBeNull();
    expect(mappato.positionKnown).toBe(false);
  });

  it('una Centrale che non si esprime vale come incertezza, non come posizione buona', () => {
    // Nessuno dei campi nuovi nel payload: nel dubbio il convoglio finisce in elenco,
    // non sulla mappa.
    const mappato = mapApiTrain({ id: 'Vecchio', stato: 'attivo', stazioneCorrente: 'MI' });

    expect(mappato.positionKnown).toBe(false);
    expect(mappato.previousStationId).toBeNull();
  });
});
