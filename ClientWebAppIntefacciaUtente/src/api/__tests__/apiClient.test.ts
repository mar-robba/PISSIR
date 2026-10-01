import { describe, expect, it } from 'vitest';
import { mapApiTrain, statiDelTreno, statiDellaStazione } from '../apiClient';
import type { ApiTrainPayload } from '../apiTypes';

/**
 * Gli estremi della tratta su cui la mappa disegna il convoglio li decide la CENTRALE,
 * non questo mappatore.
 *
 * Non si ricavano da posizioneAttualeTratta, che la scrive solo il passaggio rilevato
 * dai sensori di terra: con una stazione spenta quel campo resta fermo sull'ultima
 * tratta confermata mentre il convoglio è già andato avanti, e una provenienza vecchia
 * accanto alla destinazione aggiornata darebbe una coppia che non è una tratta della
 * rete (un convoglio in viaggio da Milano a Berlino, due stazioni che nessun binario
 * collega).
 *
 * La Centrale incrocia le due fonti e pubblica il risultato in partenzaCorrente/
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
    expect(mappato.status).toBe('fermo');
  });

  it('con la partenza non confermata non ricostruisce la tratta dal campo vecchio', () => {
    // È il caso della stazione spenta: la destinazione la sa (la dichiara il convoglio),
    // la provenienza no. posizioneAttualeTratta contiene ancora una tratta plausibile,
    // che non deve bastare a comporre una corsa che nella rete non esiste.
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

/**
 * Lo stato composto: un convoglio è in uno stato per ognuno dei suoi automi, e la
 * schermata li deve mostrare tutti. Il caso da cui nasce è quello di un treno trattenuto
 * a metà tratta da una stazione guasta davanti a lui: per la Centrale è "fermo", come uno
 * in sosta al binario, e la web app traduceva "fermo" con "in stazione".
 */
describe('stato composto', () => {
  it('il treno trattenuto a metà tratta è REGOLARE, BLOCCATO_IN_TRATTA e in andata, non "in stazione"', () => {
    const mappato = mapApiTrain(treno({
      stato: 'fermo',
      condizione: 'REGOLARE',
      faseViaggio: 'BLOCCATO_IN_TRATTA',
      direzione: 'andata',
      progresso: 11,
      stazioneCorrente: null,
      prossimaStazione: 'FI',
      partenzaCorrente: 'CA',
      arrivoCorrente: 'FI',
      direzioneCorrente: 'andata',
      posizioneNota: true,
    }));

    expect(mappato.status).toBe('fermo');
    expect(statiDelTreno(mappato).map(stato => stato.valore))
      .toEqual(['REGOLARE', 'BLOCCATO_IN_TRATTA', 'ANDATA']);
  });

  it('in emergenza il convoglio resta anche nella fase in cui era', () => {
    const stati = statiDelTreno(mapApiTrain(treno({
      stato: 'rotto',
      condizione: 'EMERGENZA',
      faseViaggio: 'IN_VIAGGIO',
      direzione: 'ritorno',
    })));

    expect(stati.map(stato => stato.valore)).toEqual(['EMERGENZA', 'IN_VIAGGIO', 'RITORNO']);
    expect(stati[0].tono).toBe('danger');
  });

  it('senza itinerario e al capolinea non c\'è un verso di marcia da mostrare', () => {
    const senza = statiDelTreno(mapApiTrain(treno({ stato: 'fermo', condizione: 'REGOLARE', faseViaggio: 'SENZA_ITINERARIO' })));
    const finita = statiDelTreno(mapApiTrain(treno({ stato: 'capolinea', condizione: 'REGOLARE', faseViaggio: 'FERMO_CAPOLINEA' })));

    expect(senza.map(stato => stato.valore)).toEqual(['REGOLARE', 'SENZA_ITINERARIO']);
    expect(finita.map(stato => stato.valore)).toEqual(['REGOLARE', 'FERMO_CAPOLINEA']);
  });

  it('se il convoglio non ha ancora trasmesso resta lo stato della Centrale, con le sue parole', () => {
    const stati = statiDelTreno(mapApiTrain(treno({ stato: 'fermo' })));

    expect(stati).toHaveLength(1);
    expect(stati[0].valore).toBe('FERMO');
    expect(stati[0].automa).toContain('M13');
  });

  it('una stazione in manutenzione mostra anche lo stato che il nodo dichiara di sé', () => {
    const stati = statiDellaStazione({ status: 'manutenzione', declaredStatus: 'GUASTA' });

    expect(stati.map(stato => stato.valore)).toEqual(['MANUTENZIONE', 'GUASTA']);
  });

  it('fuori dalla manutenzione la Centrale prende per buono il nodo: uno stato solo', () => {
    expect(statiDellaStazione({ status: 'guasta', declaredStatus: 'GUASTA' }).map(stato => stato.valore))
      .toEqual(['GUASTA']);
    // Da OFFLINE il nodo non batte: il suo ultimo stato non è quello di adesso.
    expect(statiDellaStazione({ status: 'offline', declaredStatus: 'ONLINE' }).map(stato => stato.valore))
      .toEqual(['OFFLINE']);
  });
});
