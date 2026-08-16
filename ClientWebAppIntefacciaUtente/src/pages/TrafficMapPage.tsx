import {
  MouseEvent as EventoMouse,
  PointerEvent as EventoPuntatore,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Building2, Maximize2, Minus, Plus, Tag, Train as TrainIcon, X } from 'lucide-react';
import { useRailwayStore } from '../store/railwayStore';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import { Route, Station, TrackSegment, Train as TrainType } from '../types';
import './TrafficMapPage.css';

type DiagramPoint = { x: number; y: number };
type GraphEdge = { fromId: string; toId: string; key: string; doppioSenso: boolean };

/**
 * Risultato del posizionamento delle stazioni sul diagramma.
 *
 * <p>`kmPerUnita` vale null quando la mappa NON è geografica (nessuna coordinata
 * in banca dati): in quel caso la barra di scala non ha senso e non va disegnata.
 */
type Disposizione = {
  posizioni: Map<string, DiagramPoint>;
  kmPerUnita: number | null;
  senzaGps: string[];
};

/** Riquadro nel sistema di coordinate del diagramma, usato per le collisioni. */
type Riquadro = { x1: number; y1: number; x2: number; y2: number };

type TipoElemento = 'stazione' | 'treno';

/**
 * Un elemento disegnabile sulla mappa. `ancora` è il punto reale (nodo della
 * stazione o posizione interpolata del treno); `posizione` è dove il simbolo
 * viene effettivamente disegnato dopo lo sventagliamento dei sovrapposti.
 */
type ElementoMappa = {
  tipo: TipoElemento;
  chiave: string;
  id: string;
  nome: string;
  etichetta: string;
  nota: string;
  colore: string;
  classeEtichetta: string;
  ancora: DiagramPoint;
  posizione: DiagramPoint;
  inAvaria: boolean;
  /** Stazione piazzata senza coordinate GPS: sulla mappa si disegna tratteggiata. */
  senzaGps: boolean;
};

/** Insieme di elementi che cadono praticamente nello stesso punto della mappa. */
type GruppoSovrapposti = { chiave: string; ancora: DiagramPoint; elementi: ElementoMappa[] };

type Selezione = { tipo: TipoElemento; id: string } | null;

type Vista = { x: number; y: number; w: number; h: number };

type Etichetta = { x: number; y: number; ancora: 'middle' | 'start' | 'end' };

/**
 * Dimensioni in unità del diagramma. Coincidono con i pixel del riquadro sullo
 * schermo (vengono misurate a runtime): così la rete riempie il pannello invece
 * di stare in un rettangolo fisso con due bande vuote ai lati.
 */
type Riquadro2D = { w: number; h: number };

const RIQUADRO_PREDEFINITO: Riquadro2D = { w: 960, h: 620 };
const MAP_PADDING = 80;
// Quanto la linea si ferma prima del centro della stazione: la punta della freccia
// deve restare fuori dal cerchio (r 13, 17 se selezionata), se no ci finisce sotto.
const STATION_EDGE_GAP = 24;
// Due elementi più vicini di così sono considerati sovrapposti e vengono
// sventagliati: sotto questa distanza i simboli si coprirebbero a vicenda.
const RAGGIO_SOVRAPPOSIZIONE = 26;
// Distanza minima fra due simboli sventagliati, ricavata dal lato del quadrato
// del treno (20) più un po' di aria.
const DISTANZA_MINIMA = 34;
// Limiti dello zoom, espressi come frazione della larghezza del diagramma.
const ZOOM_MIN = 1 / 8;
const ZOOM_MAX = 1.1;
// Un grado di latitudine sono sempre ~111 km (i meridiani sono tutti uguali):
// è il fattore che trasforma le unità del diagramma in chilometri veri.
const GRADO_LAT_KM = 111.19;
// Sotto questa estensione (un centesimo di grado, poco più di un chilometro) si
// smette di ingrandire: con tutte le stazioni nello stesso punto la scala
// diventerebbe infinita.
const SPAN_MINIMO_GRADI = 0.01;
// Lunghezza a riposo di un arco e forza di repulsione del layout a forze, usato
// come ripiego quando le coordinate GPS mancano.
const LUNGHEZZA_ARCO = 175;
const REPULSIONE = 26000;
// Lunghezza indicativa in pixel della barra di scala: da lì si sceglie il numero
// tondo di chilometri da scriverci sopra.
const BARRA_SCALA_PX = 110;

/**
 * Restituisce tutti gli archi della rete, senza duplicati e senza dipendere
 * dalle coordinate GPS. Le tratte fisiche hanno priorità; gli itinerari attivi
 * completano il grafo quando non esiste ancora una tratta fisica corrispondente.
 *
 * <p>Le tratte sono ORIENTATE (il server rifiuta un itinerario che usa A-&gt;B se
 * in tabella c'è solo B-&gt;A), quindi ogni arco tiene il verso in cui si può
 * percorrere. Le due righe A-&gt;B e B-&gt;A restano un solo segmento sulla mappa,
 * marcato come doppio senso: due linee sovrapposte sarebbero indistinguibili.
 */
function buildGraphEdges(
  stations: Station[],
  routes: Route[],
  trackSegments: TrackSegment[],
): GraphEdge[] {
  const stationIds = new Set(stations.map(({ id }) => id));
  const edges = new Map<string, GraphEdge>();

  const addEdge = (fromId: string, toId: string) => {
    if (fromId === toId || !stationIds.has(fromId) || !stationIds.has(toId)) return;

    // La chiave ignora il verso, così i due sensi di marcia finiscono sulla stessa
    // riga; il verso vero e proprio resta in fromId/toId e in doppioSenso.
    const key = [fromId, toId].sort().join('::');
    const esistente = edges.get(key);
    if (!esistente) {
      edges.set(key, { fromId, toId, key, doppioSenso: false });
      return;
    }
    // Stesso arco già visto ma partendo dall'altra stazione: si percorre in entrambi i sensi.
    if (esistente.fromId !== fromId) esistente.doppioSenso = true;
  };

  trackSegments.forEach(({ departureStationId, arrivalStationId }) => {
    addEdge(departureStationId, arrivalStationId);
  });

  routes.filter(({ active }) => active).forEach(({ stationIds }) => {
    for (let index = 0; index < stationIds.length - 1; index += 1) {
      addEdge(stationIds[index], stationIds[index + 1]);
    }
  });

  return [...edges.values()];
}

/** Chiave dell'arco fra due stazioni, indipendente dal verso di percorrenza. */
function chiaveArco(primaStazione: string, secondaStazione: string): string {
  return [primaStazione, secondaStazione].sort().join('::');
}

/**
 * Nel tipo Station le coordinate GPS stanno dentro `coordinates`, ma con nomi
 * che ingannano: x è la LATITUDINE e y la LONGITUDINE (è la mappatura che fa
 * apiClient dai campi latitudine/longitudine della Centrale). Queste due
 * funzioncine servono a non sbagliarsi in giro per il file, visto che quelle x/y
 * non hanno niente a che vedere con le x/y del disegno.
 */
function latitudineDi(station: Station): number {
  return station.coordinates?.x ?? 0;
}

function longitudineDi(station: Station): number {
  return station.coordinates?.y ?? 0;
}

/**
 * Una stazione si può proiettare solo se le sue coordinate sono plausibili.
 * Il punto (0, 0) va scartato apposta: è quello che esce da apiClient quando la
 * Centrale non ha la coordinata (`s.latitudine || 0`), cade nel Golfo di Guinea
 * e da solo schiaccerebbe tutta la rete vera in un angolo della mappa.
 */
function haCoordinateGps(station: Station): boolean {
  const lat = latitudineDi(station);
  const lon = longitudineDi(station);
  return Number.isFinite(lat) && Number.isFinite(lon)
    && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
    && !(lat === 0 && lon === 0);
}

/** Spazio del diagramma al netto dei margini, mai negativo su pannelli stretti. */
function spazioUtile(riquadro: Riquadro2D): Riquadro2D {
  return {
    w: Math.max(40, riquadro.w - 2 * MAP_PADDING),
    h: Math.max(40, riquadro.h - 2 * MAP_PADDING),
  };
}

/**
 * Decide dove disegnare ogni stazione.
 *
 * <p>Regola principale: le stazioni stanno dove dice il GPS, così aggiungendone
 * altre la rete continua a somigliare alla cartina vera. Il layout a forze resta
 * solo come ripiego, per le stazioni a cui le coordinate mancano del tutto (e per
 * l'intera mappa se in banca dati non ce n'è nemmeno una).
 */
function calcolaDisposizione(
  stations: Station[],
  edges: GraphEdge[],
  riquadro: Riquadro2D,
): Disposizione {
  const conGps = stations.filter(haCoordinateGps);
  const senzaGps = stations.filter(station => !haCoordinateGps(station));

  // Con meno di due stazioni geolocalizzate non c'è niente da proiettare (una
  // sola non dà né scala né orientamento): si torna al vecchio layout a forze.
  if (conGps.length < 2) {
    return {
      posizioni: layoutAForze(stations, edges, riquadro),
      kmPerUnita: null,
      senzaGps: senzaGps.map(({ id }) => id),
    };
  }

  const { posizioni, kmPerUnita } = proiettaGeografiche(conGps, riquadro);

  if (senzaGps.length > 0) {
    // Le stazioni senza coordinate partono vicino a quelle a cui sono collegate e
    // poi si assestano con le forze: le geolocalizzate restano inchiodate dove
    // dice il GPS (sono "fisse"), a muoversi sono soltanto le altre.
    const lunghezzaArco = lunghezzaTipicaArchi(edges, posizioni);
    seminaSenzaGps(senzaGps, edges, posizioni, riquadro, lunghezzaArco);
    disponiAForze(
      stations,
      edges,
      riquadro,
      posizioni,
      new Set(conGps.map(({ id }) => id)),
      lunghezzaArco,
    );
  }

  return { posizioni, kmPerUnita, senzaGps: senzaGps.map(({ id }) => id) };
}

/**
 * Proietta le stazioni geolocalizzate sul riquadro del diagramma.
 *
 * <p>È una proiezione equirettangolare centrata sulla latitudine media della
 * rete: la longitudine viene moltiplicata per cos(latitudine media) perché alle
 * nostre latitudini un grado di longitudine vale ~73 km contro i 111 km di un
 * grado di latitudine, e senza quella correzione l'Italia verrebbe fuori larga
 * un terzo in più del vero. La scala poi è la stessa sui due assi (Math.min),
 * quindi la forma della rete non viene stirata e le distanze fra due stazioni
 * qualsiasi si possono confrontare a occhio.
 *
 * <p>Vale per reti di dimensione nazionale: su scala continentale la
 * deformazione ai bordi si vedrebbe, ma qui la rete è quella italiana.
 */
function proiettaGeografiche(
  stazioni: Station[],
  riquadro: Riquadro2D,
): { posizioni: Map<string, DiagramPoint>; kmPerUnita: number } {
  const latMedia = stazioni.reduce((somma, s) => somma + latitudineDi(s), 0) / stazioni.length;
  const compressione = Math.cos((latMedia * Math.PI) / 180);

  // Coordinate ancora in gradi: la y è negata perché sullo schermo il nord sta in
  // alto (y piccola) mentre la latitudine cresce andando verso nord.
  const piane = stazioni.map(station => ({
    id: station.id,
    x: longitudineDi(station) * compressione,
    y: -latitudineDi(station),
  }));

  const minX = Math.min(...piane.map(({ x }) => x));
  const maxX = Math.max(...piane.map(({ x }) => x));
  const minY = Math.min(...piane.map(({ y }) => y));
  const maxY = Math.max(...piane.map(({ y }) => y));
  const larghezza = Math.max(maxX - minX, SPAN_MINIMO_GRADI);
  const altezza = Math.max(maxY - minY, SPAN_MINIMO_GRADI);

  const spazio = spazioUtile(riquadro);
  const scala = Math.min(spazio.w / larghezza, spazio.h / altezza);

  const posizioni = new Map<string, DiagramPoint>();
  piane.forEach(({ id, x, y }) => {
    posizioni.set(id, {
      x: riquadro.w / 2 + (x - (minX + maxX) / 2) * scala,
      y: riquadro.h / 2 + (y - (minY + maxY) / 2) * scala,
    });
  });

  // La scala è in unità del diagramma per grado di latitudine: invertendola si
  // sa quanti chilometri vale un'unità, cioè quanto è lunga la barra di scala.
  return { posizioni, kmPerUnita: GRADO_LAT_KM / scala };
}

/**
 * Lunghezza mediana degli archi già proiettati: è la distanza "normale" fra due
 * stazioni collegate su questa mappa, e serve da lunghezza a riposo quando si
 * infila una stazione senza coordinate in mezzo a quelle geolocalizzate.
 */
function lunghezzaTipicaArchi(edges: GraphEdge[], posizioni: Map<string, DiagramPoint>): number {
  const lunghezze = edges
    .map(({ fromId, toId }) => {
      const da = posizioni.get(fromId);
      const a = posizioni.get(toId);
      return da && a ? Math.hypot(a.x - da.x, a.y - da.y) : null;
    })
    .filter((lunghezza): lunghezza is number => lunghezza !== null && lunghezza > 1)
    .sort((primo, secondo) => primo - secondo);

  return lunghezze.length === 0 ? LUNGHEZZA_ARCO : lunghezze[Math.floor(lunghezze.length / 2)];
}

/**
 * Posizione di partenza delle stazioni senza coordinate: il baricentro delle
 * vicine già piazzate, scostato di un po' perché due stazioni non partano dallo
 * stesso identico punto. Chi non ha nessun collegamento parte dal centro.
 */
function seminaSenzaGps(
  senzaGps: Station[],
  edges: GraphEdge[],
  posizioni: Map<string, DiagramPoint>,
  riquadro: Riquadro2D,
  lunghezzaArco: number,
): void {
  const ordinate = [...senzaGps].sort((primo, secondo) => primo.id.localeCompare(secondo.id));

  ordinate.forEach((station, indice) => {
    const vicine = edges
      .filter(({ fromId, toId }) => fromId === station.id || toId === station.id)
      .map(({ fromId, toId }) => posizioni.get(fromId === station.id ? toId : fromId))
      .filter((punto): punto is DiagramPoint => punto !== undefined);

    const base = vicine.length > 0
      ? {
        x: vicine.reduce((somma, { x }) => somma + x, 0) / vicine.length,
        y: vicine.reduce((somma, { y }) => somma + y, 0) / vicine.length,
      }
      : { x: riquadro.w / 2, y: riquadro.h / 2 };

    const angolo = (2 * Math.PI * indice) / ordinate.length - Math.PI / 2;
    posizioni.set(station.id, {
      x: base.x + lunghezzaArco * 0.6 * Math.cos(angolo),
      y: base.y + lunghezzaArco * 0.6 * Math.sin(angolo),
    });
  });
}

/**
 * Layout a forze deterministico: nodi collegati si attraggono, tutti i nodi si
 * respingono. Le stazioni elencate in `fisse` restano dove sono (fanno forza
 * sulle altre ma non si spostano), così lo stesso codice serve sia da ripiego
 * per l'intera mappa sia per sistemare le poche stazioni senza coordinate.
 */
function disponiAForze(
  stations: Station[],
  edges: GraphEdge[],
  riquadro: Riquadro2D,
  posizioni: Map<string, DiagramPoint>,
  fisse: Set<string>,
  lunghezzaArco: number,
): void {
  const ordinate = [...stations].sort((primo, secondo) => primo.id.localeCompare(secondo.id));
  const count = ordinate.length;
  // La repulsione va riscalata insieme alla lunghezza degli archi, se no su una
  // mappa fitta i nodi liberi schizzerebbero via.
  const repulsione = REPULSIONE * (lunghezzaArco / LUNGHEZZA_ARCO) ** 2;

  for (let iterazione = 0; iterazione < 200; iterazione += 1) {
    const spostamenti = new Map<string, DiagramPoint>(
      ordinate.map(({ id }) => [id, { x: 0, y: 0 }]),
    );

    // Repulsione tra ciascuna coppia: evita sovrapposizioni anche per nodi isolati.
    for (let first = 0; first < count; first += 1) {
      for (let second = first + 1; second < count; second += 1) {
        const firstId = ordinate[first].id;
        const secondId = ordinate[second].id;
        const firstPoint = posizioni.get(firstId)!;
        const secondPoint = posizioni.get(secondId)!;
        let dx = firstPoint.x - secondPoint.x;
        let dy = firstPoint.y - secondPoint.y;
        let distanceSquared = dx * dx + dy * dy;

        // Direzione deterministica se i due punti coincidono esattamente.
        if (distanceSquared < 0.01) {
          dx = first % 2 === 0 ? 1 : -1;
          dy = second % 2 === 0 ? 1 : -1;
          distanceSquared = 2;
        }

        const distance = Math.sqrt(distanceSquared);
        const force = repulsione / distanceSquared;
        const firstMove = spostamenti.get(firstId)!;
        const secondMove = spostamenti.get(secondId)!;
        firstMove.x += (dx / distance) * force;
        firstMove.y += (dy / distance) * force;
        secondMove.x -= (dx / distance) * force;
        secondMove.y -= (dy / distance) * force;
      }
    }

    // Attrazione sugli archi: mantiene vicine le stazioni realmente collegate.
    edges.forEach(({ fromId, toId }) => {
      const from = posizioni.get(fromId);
      const to = posizioni.get(toId);
      const fromMove = spostamenti.get(fromId);
      const toMove = spostamenti.get(toId);
      if (!from || !to || !fromMove || !toMove) return;

      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const distance = Math.max(1, Math.hypot(dx, dy));
      const force = (distance - lunghezzaArco) * 0.075;
      fromMove.x += (dx / distance) * force;
      fromMove.y += (dy / distance) * force;
      toMove.x -= (dx / distance) * force;
      toMove.y -= (dy / distance) * force;
    });

    // Raffreddamento progressivo: il layout converge invece di oscillare.
    const raffreddamento = 0.22 * (1 - iterazione / 200) + 0.03;
    ordinate.forEach(({ id }) => {
      if (fisse.has(id)) return;
      const punto = posizioni.get(id)!;
      const spostamento = spostamenti.get(id)!;
      posizioni.set(id, {
        x: Math.max(
          MAP_PADDING,
          Math.min(riquadro.w - MAP_PADDING, punto.x + spostamento.x * raffreddamento),
        ),
        y: Math.max(
          MAP_PADDING,
          Math.min(riquadro.h - MAP_PADDING, punto.y + spostamento.y * raffreddamento),
        ),
      });
    });
  }
}

/**
 * Disposizione di ripiego, tutta a forze: si usa quando in banca dati non c'è
 * nessuna coordinata GPS utilizzabile. La rete si vede lo stesso, ma la posizione
 * dipende soltanto dagli archi, quindi non corrisponde alla geografia vera.
 */
function layoutAForze(
  stations: Station[],
  edges: GraphEdge[],
  riquadro: Riquadro2D,
): Map<string, DiagramPoint> {
  const ordinate = [...stations].sort((primo, secondo) => primo.id.localeCompare(secondo.id));
  const posizioni = new Map<string, DiagramPoint>();
  const count = ordinate.length;

  if (count === 0) return posizioni;

  const centroX = riquadro.w / 2;
  const centroY = riquadro.h / 2;
  const raggioIniziale = Math.min(riquadro.h / 2 - MAP_PADDING, 80 + count * 18);

  // Posizione iniziale stabile basata sull'ID, poi raffinata dalle forze del grafo.
  ordinate.forEach((station, indice) => {
    const angolo = (2 * Math.PI * indice) / count - Math.PI / 2;
    posizioni.set(station.id, {
      x: centroX + raggioIniziale * Math.cos(angolo),
      y: centroY + raggioIniziale * Math.sin(angolo),
    });
  });

  disponiAForze(stations, edges, riquadro, posizioni, new Set(), LUNGHEZZA_ARCO);
  adattaAlDiagramma(posizioni, riquadro);
  return posizioni;
}

/**
 * Ingrandisce e ricentra il risultato del layout a forze finché la rete non
 * riempie il diagramma: senza questo passaggio poche stazioni restano in un grumo
 * al centro e metà della mappa resta vuota. La scala è uniforme, quindi la forma
 * del grafo non viene deformata.
 *
 * <p>Sulla disposizione geografica NON va applicata: cambierebbe la scala e la
 * barra dei chilometri direbbe una bugia.
 */
function adattaAlDiagramma(positions: Map<string, DiagramPoint>, riquadro: Riquadro2D): void {
  const punti = [...positions.values()];
  if (punti.length === 0) return;

  const minX = Math.min(...punti.map(({ x }) => x));
  const maxX = Math.max(...punti.map(({ x }) => x));
  const minY = Math.min(...punti.map(({ y }) => y));
  const maxY = Math.max(...punti.map(({ y }) => y));
  const larghezza = Math.max(1, maxX - minX);
  const altezza = Math.max(1, maxY - minY);
  const spazio = spazioUtile(riquadro);
  const scala = Math.min(spazio.w / larghezza, spazio.h / altezza, 2.2);

  positions.forEach((punto, id) => {
    positions.set(id, {
      x: riquadro.w / 2 + (punto.x - (minX + maxX) / 2) * scala,
      y: riquadro.h / 2 + (punto.y - (minY + maxY) / 2) * scala,
    });
  });
}

/**
 * Il convoglio sta percorrendo una tratta, cioè va disegnato fra due stazioni invece che
 * sopra a una.
 *
 * Non basta lo stato: un treno che dichiara una stazione corrente è FERMO lì, perché quel
 * campo lo svuota la telemetria stessa appena riparte. Lo stato invece, nello snapshot,
 * può arrivare vecchio di qualche secondo — la Centrale registra l'ingresso in stazione
 * appena le arriva il passaggio, ma "attivo" e la percentuale restano quelli dell'ultimo
 * frame di telemetria — e in quella finestra il treno appena arrivato veniva scagliato
 * quasi sopra la stazione successiva.
 */
function inMarcia(train: TrainType): boolean {
  return (train.status === 'in_viaggio' || train.status === 'in_ritardo')
    && !train.currentStationId;
}

/** Colore del simbolo del treno in base allo stato del convoglio. */
function coloreTreno(train: TrainType): { simbolo: string; classe: string } {
  if (train.status === 'guasto') return { simbolo: '#ef4444', classe: 'map-label--guasto' };
  if (train.status === 'in_ritardo' || train.delayMinutes > 0) {
    return { simbolo: '#f59e0b', classe: 'map-label--ritardo' };
  }
  if (train.status === 'in_stazione' || train.status === 'in_attesa') {
    return { simbolo: '#64748b', classe: 'map-label--fermo' };
  }
  return { simbolo: '#3b82f6', classe: '' };
}

/** Etichetta leggibile dello stato, senza underscore. */
function descriviStato(stato: string): string {
  return stato.replace(/_/g, ' ').toUpperCase();
}

/**
 * Arrotonda i chilometri della barra di scala a 1, 2 o 5 per decade, come fanno
 * le cartine: si preferisce scrivere "200 km" invece di "187 km".
 */
function arrotondaScala(km: number): number {
  const decade = 10 ** Math.floor(Math.log10(km));
  const resto = km / decade;
  if (resto >= 5) return 5 * decade;
  if (resto >= 2) return 2 * decade;
  return decade;
}

/** Scrive la distanza in metri sotto il chilometro, se no in km. */
function formattaScala(km: number): string {
  return km >= 1 ? `${km} km` : `${Math.round(km * 1000)} m`;
}

/** Testo dell'avviso sulle stazioni prive di coordinate GPS utilizzabili. */
function avvisoSenzaGps(quante: number, geografica: boolean): string {
  if (!geografica) return 'Nessuna coordinata GPS: stazioni disposte in base ai collegamenti';
  return quante === 1
    ? '1 stazione senza coordinate GPS valide, messa vicino a quelle collegate'
    : `${quante} stazioni senza coordinate GPS valide, messe vicino a quelle collegate`;
}

/**
 * Coordinate da scrivere nella scheda della stazione. Se il dato c'è ma non sta
 * nei limiti (capita scrivendo a mano nel pannello di amministrazione) lo si
 * mostra lo stesso, segnalandolo: così si capisce quale riga va corretta invece
 * di credere che la coordinata manchi del tutto.
 */
function descriviCoordinate(station: Station): string {
  const lat = latitudineDi(station);
  const lon = longitudineDi(station);
  if (haCoordinateGps(station)) return `${lat.toFixed(4)}, ${lon.toFixed(4)}`;
  return lat === 0 && lon === 0 ? 'non impostate' : `${lat}, ${lon} (fuori scala)`;
}

/**
 * Raggruppa gli elementi che cadono nello stesso punto. L'ordine di ingresso è
 * deterministico (prima le stazioni, poi i treni per nome), quindi i gruppi non
 * cambiano da un aggiornamento WebSocket all'altro se le posizioni non cambiano.
 */
function raggruppaSovrapposti(elementi: ElementoMappa[]): GruppoSovrapposti[] {
  const gruppi: GruppoSovrapposti[] = [];

  elementi.forEach(elemento => {
    const gruppo = gruppi.find(
      ({ ancora }) => Math.hypot(ancora.x - elemento.ancora.x, ancora.y - elemento.ancora.y)
        <= RAGGIO_SOVRAPPOSIZIONE,
    );
    if (gruppo) {
      gruppo.elementi.push(elemento);
      return;
    }
    gruppi.push({ chiave: elemento.chiave, ancora: elemento.ancora, elementi: [elemento] });
  });

  return gruppi;
}

/**
 * Dispone in cerchio gli elementi di un gruppo sovrapposto ("sventagliamento"),
 * così ognuno resta cliccabile da solo. Il raggio cresce col numero di elementi
 * in modo che due simboli vicini restino comunque a DISTANZA_MINIMA.
 *
 * <p>Se nel gruppo c'è una stazione, questa resta ferma sul suo nodo (è lì che
 * convergono le tratte) e a spostarsi sono soltanto i treni che ci stanno sopra.
 */
function sventaglia(gruppo: GruppoSovrapposti): void {
  gruppo.elementi.forEach(elemento => { elemento.posizione = gruppo.ancora; });
  if (gruppo.elementi.length === 1) return;

  const stazione = gruppo.elementi.find(({ tipo }) => tipo === 'stazione');
  const daDisporre = stazione
    ? gruppo.elementi.filter(elemento => elemento !== stazione)
    : gruppo.elementi;
  const quanti = daDisporre.length;
  if (quanti === 0) return;

  const raggioMinimo = stazione ? 36 : 30;
  const raggio = quanti === 1
    ? raggioMinimo
    : Math.min(90, Math.max(raggioMinimo, DISTANZA_MINIMA / (2 * Math.sin(Math.PI / quanti))));

  daDisporre.forEach((elemento, indice) => {
    const angolo = (2 * Math.PI * indice) / quanti - Math.PI / 2;
    elemento.posizione = {
      x: gruppo.ancora.x + raggio * Math.cos(angolo),
      y: gruppo.ancora.y + raggio * Math.sin(angolo),
    };
  });
}

/**
 * Dove mettere il disco col numero di elementi sovrapposti: sul punto stesso se
 * lì non c'è nessun simbolo, altrimenti come pastiglia appoggiata alla stazione.
 */
function posizioneDisco(gruppo: GruppoSovrapposti): DiagramPoint {
  const conStazione = gruppo.elementi.some(({ tipo }) => tipo === 'stazione');
  return conStazione
    ? { x: gruppo.ancora.x + 14, y: gruppo.ancora.y - 14 }
    : gruppo.ancora;
}

function siSovrappongono(primo: Riquadro, secondo: Riquadro): boolean {
  return primo.x1 < secondo.x2 && primo.x2 > secondo.x1
    && primo.y1 < secondo.y2 && primo.y2 > secondo.y1;
}

// Posizioni in cui si prova a mettere l'etichetta, in ordine di preferenza.
const POSIZIONI_ETICHETTA: { dx: number; dy: number; ancora: 'middle' | 'start' | 'end' }[] = [
  { dx: 0, dy: 26, ancora: 'middle' },
  { dx: 0, dy: -20, ancora: 'middle' },
  { dx: 18, dy: 4, ancora: 'start' },
  { dx: -18, dy: 4, ancora: 'end' },
  { dx: 0, dy: 38, ancora: 'middle' },
  { dx: 0, dy: -32, ancora: 'middle' },
];

/**
 * Decide dove scrivere ogni etichetta evitando che i testi si accavallino fra
 * loro o finiscano sopra un simbolo. Gli elementi senza posto libero restano
 * senza etichetta fissa: il nome compare comunque al passaggio del mouse e nel
 * pannello di dettaglio.
 */
function piazzaEtichette(
  elementi: ElementoMappa[],
  gruppi: GruppoSovrapposti[],
  selezione: Selezione,
): Map<string, Etichetta> {
  const piazzate = new Map<string, Etichetta>();
  const occupati: Riquadro[] = elementi.map(({ posizione }) => ({
    x1: posizione.x - 14,
    y1: posizione.y - 14,
    x2: posizione.x + 14,
    y2: posizione.y + 14,
  }));

  // Anche i dischi dei gruppi sovrapposti sono ingombri da evitare.
  gruppi.filter(({ elementi: dentro }) => dentro.length > 1).forEach(gruppo => {
    const disco = posizioneDisco(gruppo);
    occupati.push({ x1: disco.x - 10, y1: disco.y - 10, x2: disco.x + 10, y2: disco.y + 10 });
  });

  // Chi è selezionato ha la precedenza sul posto migliore, poi le stazioni.
  const priorita = (elemento: ElementoMappa) => {
    if (selezione && selezione.tipo === elemento.tipo && selezione.id === elemento.id) return 0;
    return elemento.tipo === 'stazione' ? 1 : 2;
  };
  const ordinati = [...elementi].sort(
    (primo, secondo) => priorita(primo) - priorita(secondo)
      || primo.chiave.localeCompare(secondo.chiave),
  );

  ordinati.forEach(elemento => {
    const larghezza = elemento.etichetta.length * (elemento.tipo === 'treno' ? 5.6 : 6) + 6;
    const posizioneLibera = POSIZIONI_ETICHETTA.find(({ dx, dy, ancora }) => {
      const centroX = elemento.posizione.x + dx;
      const centroY = elemento.posizione.y + dy;
      const sinistra = ancora === 'middle'
        ? centroX - larghezza / 2
        : ancora === 'start' ? centroX : centroX - larghezza;
      const riquadro: Riquadro = {
        x1: sinistra,
        y1: centroY - 10,
        x2: sinistra + larghezza,
        y2: centroY + 3,
      };
      if (occupati.some(altro => siSovrappongono(altro, riquadro))) return false;
      occupati.push(riquadro);
      return true;
    });

    if (posizioneLibera) {
      piazzate.set(elemento.chiave, {
        x: elemento.posizione.x + posizioneLibera.dx,
        y: elemento.posizione.y + posizioneLibera.dy,
        ancora: posizioneLibera.ancora,
      });
    }
  });

  return piazzate;
}

/** Tiene la vista dentro il diagramma, con un margine di cortesia. */
function limitaVista(vista: Vista, riquadro: Riquadro2D): Vista {
  const margineX = vista.w * 0.3;
  const margineY = vista.h * 0.3;
  const x = vista.w >= riquadro.w
    ? (riquadro.w - vista.w) / 2
    : Math.max(-margineX, Math.min(riquadro.w - vista.w + margineX, vista.x));
  const y = vista.h >= riquadro.h
    ? (riquadro.h - vista.h) / 2
    : Math.max(-margineY, Math.min(riquadro.h - vista.h + margineY, vista.y));
  return { ...vista, x, y };
}

/** Applica uno zoom mantenendo fermo il punto (fuocoX, fuocoY) del diagramma. */
function zoomVerso(
  vista: Vista,
  fattore: number,
  fuocoX: number,
  fuocoY: number,
  riquadro: Riquadro2D,
): Vista {
  const larghezza = Math.max(
    riquadro.w * ZOOM_MIN,
    Math.min(riquadro.w * ZOOM_MAX, vista.w * fattore),
  );
  const rapporto = larghezza / vista.w;
  return limitaVista({
    x: fuocoX - (fuocoX - vista.x) * rapporto,
    y: fuocoY - (fuocoY - vista.y) * rapporto,
    w: larghezza,
    h: (riquadro.h / riquadro.w) * larghezza,
  }, riquadro);
}

export default function TrafficMapPage() {
  const { stations, trains, routes, trackSegments } = useRailwayStore();
  const [selezione, setSelezione] = useState<Selezione>(null);
  const [evidenziato, setEvidenziato] = useState<string | null>(null);
  const [mostraEtichette, setMostraEtichette] = useState(true);
  // Riquadro misurato del pannello: il diagramma ci si adatta, così non restano
  // bande vuote quando la finestra è più larga o più stretta del previsto.
  const [riquadro, setRiquadro] = useState<Riquadro2D>(RIQUADRO_PREDEFINITO);
  // `null` significa "tutto il diagramma": è la vista finché non si usa lo zoom.
  const [vista, setVista] = useState<Vista | null>(null);
  const [inTrascinamento, setInTrascinamento] = useState(false);
  const [selettore, setSelettore] = useState<
    { elementi: ElementoMappa[]; x: number; y: number } | null
  >(null);

  const contenitoreRef = useRef<HTMLDivElement | null>(null);
  const svgRef = useRef<SVGSVGElement | null>(null);
  // Stato del trascinamento: serve a distinguere una panoramica da un click.
  const trascinamento = useRef<{ x: number; y: number; mosso: boolean } | null>(null);
  const haTrascinato = useRef(false);

  const graphEdges = useMemo(
    () => buildGraphEdges(stations, routes, trackSegments),
    [stations, routes, trackSegments],
  );
  const disposizione = useMemo(
    () => calcolaDisposizione(stations, graphEdges, riquadro),
    [stations, graphEdges, riquadro],
  );
  const graphLayout = disposizione.posizioni;
  const senzaGps = useMemo(() => new Set(disposizione.senzaGps), [disposizione]);
  const getCoordinates = (stationId: string): DiagramPoint =>
    graphLayout.get(stationId) ?? { x: riquadro.w / 2, y: riquadro.h / 2 };

  const vistaPiena: Vista = { x: 0, y: 0, w: riquadro.w, h: riquadro.h };
  const vistaCorrente = vista ?? vistaPiena;

  /**
   * Barra di scala: quanti chilometri veri sono i pixel disegnati a schermo.
   * Il rapporto cambia con lo zoom (la viewBox si stringe mentre il pannello
   * resta grande uguale), quindi ogni volta si sceglie il numero tondo di km che
   * sta in circa BARRA_SCALA_PX pixel e si ricalcola la lunghezza esatta.
   */
  const scalaGrafica = useMemo(() => {
    if (disposizione.kmPerUnita === null) return null;
    const pixelPerUnita = riquadro.w / vistaCorrente.w;
    const kmPerPixel = disposizione.kmPerUnita / pixelPerUnita;
    const km = arrotondaScala(kmPerPixel * BARRA_SCALA_PX);
    if (!Number.isFinite(km) || km <= 0) return null;
    return { km, larghezza: km / kmPerPixel };
  }, [disposizione.kmPerUnita, riquadro.w, vistaCorrente.w]);

  // I dettagli vengono ricavati ogni volta dallo store: così il pannello resta
  // aggiornato in tempo reale invece di mostrare la fotografia del click.
  const stazioneSelezionata = selezione?.tipo === 'stazione'
    ? stations.find(({ id }) => id === selezione.id) ?? null
    : null;
  const trenoSelezionato = selezione?.tipo === 'treno'
    ? trains.find(({ id }) => id === selezione.id) ?? null
    : null;
  // Stesse due condizioni usate per posizionare il simbolo sul diagramma, così il pannello
  // non racconta un avanzamento che la mappa non è in grado di disegnare.
  const inMarciaSelezionato = !!trenoSelezionato && inMarcia(trenoSelezionato);
  const trattaSelezionataNota = !!trenoSelezionato?.previousStationId
    && !!trenoSelezionato?.nextStationId
    && trenoSelezionato.previousStationId !== trenoSelezionato.nextStationId;

  /**
   * Costruisce l'elenco di tutto ciò che va disegnato (stazioni e treni
   * localizzabili) e ne separa i simboli sovrapposti.
   */
  const { elementi, gruppi, trainiNonLocalizzabili } = useMemo(() => {
    const elencoStazioni: ElementoMappa[] = [...stations]
      .sort((primo, secondo) => primo.id.localeCompare(secondo.id))
      .map(station => {
        const inAvaria = station.status === 'guasta' || station.status === 'offline';
        const ancora = getCoordinates(station.id);
        return {
          tipo: 'stazione' as const,
          chiave: `stazione:${station.id}`,
          id: station.id,
          nome: station.name,
          etichetta: station.code,
          nota: `Stazione · ${descriviStato(station.status)}`,
          colore: inAvaria ? '#ef4444' : '#10b981',
          classeEtichetta: '',
          ancora,
          posizione: ancora,
          inAvaria,
          senzaGps: senzaGps.has(station.id),
        };
      });

    let scartati = 0;
    const elencoTreni: ElementoMappa[] = [...trains]
      .filter(({ status }) => status !== 'soppresso')
      .sort((primo, secondo) => primo.id.localeCompare(secondo.id))
      .map((train): ElementoMappa | null => {
        const prevStation = stations.find(({ id }) => id === train.previousStationId);
        const nextStation = stations.find(({ id }) => id === train.nextStationId);
        const currentStation = stations.find(({ id }) => id === train.currentStationId);
        const isMoving = inMarcia(train);
        // Gli estremi devono essere due stazioni DIVERSE: se coincidono l'interpolazione
        // restituisce sempre lo stesso punto e il convoglio verrebbe disegnato immobile su
        // un nodo mentre in realtà sta percorrendo una tratta. Meglio ripiegare su quello
        // che si sa per certo (la stazione corrente) che mostrare una posizione inventata.
        const trattaNota = !!prevStation && !!nextStation && prevStation.id !== nextStation.id;

        let ancora: DiagramPoint | null = null;
        if (isMoving && trattaNota && prevStation && nextStation) {
          // Interpolazione lineare: 0% coincide con la stazione precedente,
          // 100% con la successiva. Il clamp protegge la vista da frame incompleti.
          // Il digital twin calcola la propria posizione GPS nello stesso modo
          // (TrainJourneyEngine interpola lat/lon sulla percentuale), quindi ora
          // che le stazioni stanno sulle coordinate vere il punto disegnato qui è
          // proprio quello che il treno pubblica nella telemetria.
          const pct = Math.max(0, Math.min(100, train.progressPercent)) / 100;
          const prevCoords = getCoordinates(prevStation.id);
          const nextCoords = getCoordinates(nextStation.id);
          ancora = {
            x: prevCoords.x + (nextCoords.x - prevCoords.x) * pct,
            y: prevCoords.y + (nextCoords.y - prevCoords.y) * pct,
          };
        } else if (currentStation) {
          ancora = getCoordinates(currentStation.id);
        }

        // Un treno senza stazione corrente né estremi di tratta non è localizzabile.
        if (!ancora) {
          scartati += 1;
          return null;
        }

        const colori = coloreTreno(train);
        return {
          tipo: 'treno' as const,
          chiave: `treno:${train.id}`,
          id: train.id,
          nome: train.id,
          // I nomi lunghi vengono accorciati: il nome intero resta nel selettore
          // dei sovrapposti e nel pannello di dettaglio.
          etichetta: train.id.length > 14 ? `${train.id.slice(0, 13)}…` : train.id,
          nota: `Treno · ${descriviStato(train.status)}`,
          colore: colori.simbolo,
          classeEtichetta: colori.classe,
          ancora,
          posizione: ancora,
          inAvaria: train.status === 'guasto',
          senzaGps: false,
        };
      })
      .filter((elemento): elemento is ElementoMappa => elemento !== null);

    const tutti = [...elencoStazioni, ...elencoTreni];
    const raggruppati = raggruppaSovrapposti(tutti);
    raggruppati.forEach(sventaglia);

    return { elementi: tutti, gruppi: raggruppati, trainiNonLocalizzabili: scartati };
  }, [stations, trains, graphLayout, senzaGps]);

  const etichette = useMemo(
    () => (mostraEtichette
      ? piazzaEtichette(elementi, gruppi, selezione)
      : new Map<string, Etichetta>()),
    [elementi, gruppi, selezione, mostraEtichette],
  );

  /**
   * Archi da evidenziare: quelli della stazione selezionata oppure l'intero
   * itinerario del treno selezionato, così si capisce dove sta andando.
   */
  const archiEvidenziati = useMemo(() => {
    const chiavi = new Set<string>();
    if (stazioneSelezionata) {
      graphEdges.forEach(({ fromId, toId, key }) => {
        if (fromId === stazioneSelezionata.id || toId === stazioneSelezionata.id) chiavi.add(key);
      });
    }
    if (trenoSelezionato) {
      const itinerario = routes.find(({ id }) => id === trenoSelezionato.routeId);
      itinerario?.stationIds.forEach((stationId, indice) => {
        const successiva = itinerario.stationIds[indice + 1];
        if (successiva) chiavi.add(chiaveArco(stationId, successiva));
      });
      if (trenoSelezionato.previousStationId && trenoSelezionato.nextStationId) {
        chiavi.add(chiaveArco(trenoSelezionato.previousStationId, trenoSelezionato.nextStationId));
      }
    }
    return chiavi;
  }, [graphEdges, routes, stazioneSelezionata, trenoSelezionato]);

  // Misura del pannello: il diagramma usa le stesse proporzioni dello spazio
  // disponibile, quindi la rete lo riempie invece di finire in una fascia.
  // È un layout effect così la prima immagine disegnata ha già la misura giusta.
  useLayoutEffect(() => {
    const contenitore = contenitoreRef.current;
    if (!contenitore || typeof ResizeObserver === 'undefined') return undefined;

    const misura = () => {
      const dimensioni = contenitore.getBoundingClientRect();
      if (dimensioni.width < 50 || dimensioni.height < 50) return;
      setRiquadro(precedente => (
        Math.abs(precedente.w - dimensioni.width) < 8
          && Math.abs(precedente.h - dimensioni.height) < 8
          ? precedente
          : { w: Math.round(dimensioni.width), h: Math.round(dimensioni.height) }
      ));
    };

    misura();
    const osservatore = new ResizeObserver(misura);
    osservatore.observe(contenitore);
    return () => osservatore.disconnect();
  }, []);

  // Zoom con la rotella. Il listener è nativo perché React registra "wheel" come
  // passivo e preventDefault verrebbe ignorato (la pagina scorrerebbe sotto).
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return undefined;

    const allaRotella = (evento: WheelEvent) => {
      evento.preventDefault();
      const dimensioni = svg.getBoundingClientRect();
      setVista(precedente => {
        const attuale = precedente ?? { x: 0, y: 0, w: riquadro.w, h: riquadro.h };
        const scala = Math.min(dimensioni.width / attuale.w, dimensioni.height / attuale.h);
        const scartoX = (dimensioni.width - attuale.w * scala) / 2;
        const scartoY = (dimensioni.height - attuale.h * scala) / 2;
        const fuocoX = attuale.x + (evento.clientX - dimensioni.left - scartoX) / scala;
        const fuocoY = attuale.y + (evento.clientY - dimensioni.top - scartoY) / scala;
        return zoomVerso(attuale, evento.deltaY > 0 ? 1.15 : 1 / 1.15, fuocoX, fuocoY, riquadro);
      });
    };

    svg.addEventListener('wheel', allaRotella, { passive: false });
    return () => svg.removeEventListener('wheel', allaRotella);
  }, [riquadro]);

  // Esc chiude il selettore dei sovrapposti.
  useEffect(() => {
    if (!selettore) return undefined;
    const allaPressione = (evento: KeyboardEvent) => {
      if (evento.key === 'Escape') setSelettore(null);
    };
    window.addEventListener('keydown', allaPressione);
    return () => window.removeEventListener('keydown', allaPressione);
  }, [selettore]);

  const scalaCorrente = (): number => {
    const dimensioni = svgRef.current?.getBoundingClientRect();
    if (!dimensioni) return 1;
    return Math.min(dimensioni.width / vistaCorrente.w, dimensioni.height / vistaCorrente.h);
  };

  const avviaTrascinamento = (evento: EventoPuntatore<SVGSVGElement>) => {
    if (evento.button !== 0) return;
    haTrascinato.current = false;
    trascinamento.current = { x: evento.clientX, y: evento.clientY, mosso: false };
    svgRef.current?.setPointerCapture?.(evento.pointerId);
  };

  const durante = (evento: EventoPuntatore<SVGSVGElement>) => {
    const stato = trascinamento.current;
    if (!stato) return;
    const dx = evento.clientX - stato.x;
    const dy = evento.clientY - stato.y;
    if (!stato.mosso && Math.hypot(dx, dy) < 4) return;

    stato.mosso = true;
    setInTrascinamento(true);
    stato.x = evento.clientX;
    stato.y = evento.clientY;
    const scala = scalaCorrente();
    setVista(precedente => {
      const attuale = precedente ?? vistaPiena;
      return limitaVista({
        ...attuale,
        x: attuale.x - dx / scala,
        y: attuale.y - dy / scala,
      }, riquadro);
    });
  };

  const concludiTrascinamento = (evento: EventoPuntatore<SVGSVGElement>) => {
    haTrascinato.current = trascinamento.current?.mosso ?? false;
    trascinamento.current = null;
    setInTrascinamento(false);
    svgRef.current?.releasePointerCapture?.(evento.pointerId);
  };

  const zoomDaPulsante = (fattore: number) => {
    setVista(precedente => {
      const attuale = precedente ?? vistaPiena;
      return zoomVerso(
        attuale,
        fattore,
        attuale.x + attuale.w / 2,
        attuale.y + attuale.h / 2,
        riquadro,
      );
    });
  };

  const seleziona = (elemento: ElementoMappa) => {
    setSelezione({ tipo: elemento.tipo, id: elemento.id });
    setSelettore(null);
  };

  /** Apre il menù di scelta fra gli elementi che stanno nello stesso punto. */
  const apriSelettore = (gruppo: GruppoSovrapposti, evento: EventoMouse) => {
    const dimensioni = contenitoreRef.current?.getBoundingClientRect();
    if (!dimensioni) return;
    setSelettore({
      elementi: gruppo.elementi,
      x: Math.max(130, Math.min(dimensioni.width - 130, evento.clientX - dimensioni.left)),
      y: Math.min(dimensioni.height - 60, evento.clientY - dimensioni.top),
    });
  };

  const eSelezionato = (elemento: ElementoMappa) =>
    selezione?.tipo === elemento.tipo && selezione.id === elemento.id;

  // Disegna gli archi usati anche dal layout automatico delle stazioni. La freccia
  // indica il verso percorribile: una punta sola per il senso unico, una punta per
  // parte quando la tratta esiste in tabella in entrambi i versi.
  const renderConnections = () => graphEdges.map(({ fromId, toId, key, doppioSenso }) => {
    const from = getCoordinates(fromId);
    const to = getCoordinates(toId);
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const distanza = Math.max(1, Math.hypot(dx, dy));
    // Su stazioni molto vicine si accorcia meno, se no la linea si annullerebbe.
    const scarto = Math.min(STATION_EDGE_GAP, distanza / 2 - 1);
    const offsetX = (dx / distanza) * scarto;
    const offsetY = (dy / distanza) * scarto;
    const attiva = archiEvidenziati.has(key);
    const spenta = selezione !== null && !attiva;

    return (
      <line
        key={key}
        x1={from.x + offsetX}
        y1={from.y + offsetY}
        x2={to.x - offsetX}
        y2={to.y - offsetY}
        className={`map-edge${attiva ? ' map-edge--attiva' : ''}${spenta ? ' map-edge--spenta' : ''}`}
        markerEnd={`url(#${attiva ? 'punta-tratta-attiva' : 'punta-tratta'})`}
        markerStart={doppioSenso
          ? `url(#${attiva ? 'punta-tratta-attiva' : 'punta-tratta'})`
          : undefined}
      />
    );
  });

  /**
   * Quando in un punto ci sono più elementi, dal centro reale parte una stanghetta
   * verso ogni simbolo spostato e nel centro resta un disco col numero di elementi:
   * cliccandolo si sceglie quale scheda aprire.
   */
  const renderGruppi = () => gruppi.filter(({ elementi: dentro }) => dentro.length > 1)
    .map(gruppo => {
      const disco = posizioneDisco(gruppo);
      return (
      <g key={`gruppo-${gruppo.chiave}`}>
        {gruppo.elementi.filter(({ posizione }) => posizione !== gruppo.ancora).map(elemento => (
          <line
            key={`stanghetta-${elemento.chiave}`}
            className="map-fan-leader"
            x1={gruppo.ancora.x}
            y1={gruppo.ancora.y}
            x2={elemento.posizione.x}
            y2={elemento.posizione.y}
          />
        ))}
        <g
          className="map-cluster-hub"
          transform={`translate(${disco.x}, ${disco.y})`}
          onClick={evento => {
            if (haTrascinato.current) return;
            apriSelettore(gruppo, evento);
          }}
        >
          <title>{`${gruppo.elementi.length} elementi in questo punto`}</title>
          <circle className="map-cluster-hub__disco" r={9} />
          <text className="map-cluster-hub__conteggio" y={3}>{gruppo.elementi.length}</text>
        </g>
      </g>
      );
    });

  // Con un treno selezionato gli altri convogli si attenuano, così si segue a
  // colpo d'occhio quello scelto e il suo itinerario.
  const eAttenuato = (elemento: ElementoMappa) =>
    trenoSelezionato !== null && elemento.tipo === 'treno' && !eSelezionato(elemento);

  const renderElementi = () => elementi.map(elemento => {
    const selezionato = eSelezionato(elemento);
    const inRisalto = selezionato || evidenziato === elemento.chiave;
    const spento = eAttenuato(elemento);

    return (
      <g
        key={elemento.chiave}
        className={`map-marker${selezionato ? ' map-marker--selezionato' : ''}${spento ? ' map-marker--spento' : ''}`}
        style={{ transform: `translate(${elemento.posizione.x}px, ${elemento.posizione.y}px)` }}
        onClick={() => {
          if (haTrascinato.current) return;
          seleziona(elemento);
        }}
        onPointerEnter={() => setEvidenziato(elemento.chiave)}
        onPointerLeave={() => setEvidenziato(atteso => (atteso === elemento.chiave ? null : atteso))}
      >
        <title>{`${elemento.nome} — ${elemento.nota}`}</title>
        {/* Alone di selezione: sostituisce il bordo scuro, che si confondeva con le tratte. */}
        {selezionato && <circle className="map-alone" r={elemento.tipo === 'stazione' ? 21 : 19} />}
        {/* Il cerchio pulsante rende immediatamente visibile un guasto. */}
        {elemento.inAvaria && (
          <circle className="map-pulse" r={17} fill="none" stroke="#ef4444" strokeWidth="2" />
        )}
        {/* Bordo tratteggiato = stazione senza coordinate GPS: sta lì per i suoi
            collegamenti, non perché sia davvero in quel punto d'Italia. */}
        {elemento.tipo === 'stazione' ? (
          <circle
            className="map-marker__simbolo"
            r={inRisalto ? 15 : 13}
            fill={elemento.colore}
            stroke="#ffffff"
            strokeWidth="3"
            strokeDasharray={elemento.senzaGps ? '4 3' : undefined}
          />
        ) : (
          <rect
            className="map-marker__simbolo"
            x={inRisalto ? -12 : -10}
            y={inRisalto ? -12 : -10}
            width={inRisalto ? 24 : 20}
            height={inRisalto ? 24 : 20}
            rx={5}
            fill={elemento.colore}
            stroke="#ffffff"
            strokeWidth="2.5"
          />
        )}
      </g>
    );
  });

  /**
   * Le etichette stanno in un livello a parte, disegnato sopra tutto il resto:
   * quelle che non hanno trovato posto compaiono comunque se l'elemento è
   * selezionato o sotto il puntatore.
   */
  const renderEtichette = () => elementi.map(elemento => {
    const piazzata = etichette.get(elemento.chiave);
    const inRisalto = eSelezionato(elemento) || evidenziato === elemento.chiave;
    if (!piazzata && !inRisalto) return null;
    const posizione = piazzata ?? {
      x: elemento.posizione.x,
      y: elemento.posizione.y + 26,
      ancora: 'middle' as const,
    };

    return (
      <text
        key={`etichetta-${elemento.chiave}`}
        x={posizione.x}
        y={posizione.y}
        textAnchor={posizione.ancora}
        className={`map-label ${elemento.tipo === 'treno' ? 'map-label--treno' : ''} ${elemento.classeEtichetta} ${inRisalto ? 'map-label--evidenziata' : ''} ${eAttenuato(elemento) && !inRisalto ? 'map-label--spenta' : ''}`}
      >
        {inRisalto && elemento.tipo === 'treno' ? elemento.nome : elemento.etichetta}
      </text>
    );
  });

  const treniInStazione = stazioneSelezionata
    ? trains.filter(({ currentStationId, status }) =>
      currentStationId === stazioneSelezionata.id && status !== 'soppresso')
    : [];
  const itinerarioTreno = trenoSelezionato
    ? routes.find(({ id }) => id === trenoSelezionato.routeId)
    : null;
  const nomeStazione = (stationId: string | null) =>
    stations.find(({ id }) => id === stationId)?.name ?? '—';

  return (
    <div className="map-page animate-fade-in">
      <div className="map-workspace">
        {/* Diagramma della rete */}
        <div className="glass-panel map-canvas-card" ref={contenitoreRef}>
          <svg
            ref={svgRef}
            className={`map-svg${inTrascinamento ? ' map-svg--trascinamento' : ''}`}
            viewBox={`${vistaCorrente.x} ${vistaCorrente.y} ${vistaCorrente.w} ${vistaCorrente.h}`}
            preserveAspectRatio="xMidYMid meet"
            onPointerDown={avviaTrascinamento}
            onPointerMove={durante}
            onPointerUp={concludiTrascinamento}
            onPointerCancel={concludiTrascinamento}
          >
            <defs>
              <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
                <path d="M 40 0 L 0 0 0 40" fill="none" stroke="rgba(15,23,42,0.05)" strokeWidth="1" />
              </pattern>
              {/* Punta di freccia delle tratte. orient="auto-start-reverse" fa sì che
                  lo stesso marker si giri da solo quando è usato come markerStart,
                  così il doppio senso è una linea con due punte e non due linee.
                  markerUnits="userSpaceOnUse" la tiene della stessa misura a
                  prescindere dallo spessore della linea. */}
              <marker
                id="punta-tratta"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="12"
                markerHeight="12"
                markerUnits="userSpaceOnUse"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" />
              </marker>
              <marker
                id="punta-tratta-attiva"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="12"
                markerHeight="12"
                markerUnits="userSpaceOnUse"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#3b82f6" />
              </marker>
            </defs>
            {/* Sfondo cliccabile: serve anche a deselezionare. */}
            <rect
              x={-riquadro.w}
              y={-riquadro.h}
              width={riquadro.w * 3}
              height={riquadro.h * 3}
              fill="url(#grid)"
              onClick={() => {
                if (haTrascinato.current) return;
                setSelezione(null);
                setSelettore(null);
              }}
            />

            {/* Ordine di disegno: tratte, gruppi sovrapposti, simboli, etichette. */}
            <g>{renderConnections()}</g>
            <g>{renderGruppi()}</g>
            <g>{renderElementi()}</g>
            <g>{renderEtichette()}</g>
          </svg>

          <div className="map-hint">
            Rotella per zoom · trascina per spostare · click su un simbolo per i dettagli
          </div>

          <div className="map-toolbar">
            <button
              type="button"
              className={`map-tool-btn${mostraEtichette ? ' map-tool-btn--attivo' : ''}`}
              onClick={() => setMostraEtichette(attuale => !attuale)}
              title={mostraEtichette ? 'Nascondi le etichette' : 'Mostra le etichette'}
            >
              <Tag size={15} />
            </button>
            <button
              type="button"
              className="map-tool-btn"
              onClick={() => zoomDaPulsante(1 / 1.25)}
              title="Ingrandisci"
            >
              <Plus size={15} />
            </button>
            <button
              type="button"
              className="map-tool-btn"
              onClick={() => zoomDaPulsante(1.25)}
              title="Riduci"
            >
              <Minus size={15} />
            </button>
            <button
              type="button"
              className="map-tool-btn"
              onClick={() => setVista(null)}
              title="Adatta alla finestra"
            >
              <Maximize2 size={15} />
            </button>
          </div>

          <div className="map-legend">
            <span className="map-legend-item">
              <span className="map-legend-dot" style={{ background: '#10b981' }} />
              Stazione operativa
            </span>
            <span className="map-legend-item">
              <span className="map-legend-dot" style={{ background: '#ef4444' }} />
              Stazione guasta
            </span>
            <span className="map-legend-item">
              <span className="map-legend-dot map-legend-dot--treno" style={{ background: '#3b82f6' }} />
              Treno regolare
            </span>
            <span className="map-legend-item">
              <span className="map-legend-dot map-legend-dot--treno" style={{ background: '#f59e0b' }} />
              Treno in ritardo
            </span>
            <span className="map-legend-item">
              <span className="map-legend-dot map-legend-dot--treno" style={{ background: '#64748b' }} />
              Treno fermo
            </span>
            <span className="map-legend-item">
              <span className="map-legend-dot" style={{ background: '#ffffff', border: '1.5px solid #94a3b8' }} />
              Elementi sovrapposti
            </span>
            {senzaGps.size > 0 && (
              <span className="map-legend-item">
                <span className="map-legend-dot map-legend-dot--senza-gps" />
                Stazione senza GPS
              </span>
            )}
          </div>

          {/* Avvisi e barra di scala impilati nell'angolo, così non si coprono. */}
          <div className="map-angolo">
            {trainiNonLocalizzabili > 0 && (
              <div className="map-avviso">
                {trainiNonLocalizzabili} treni senza posizione nota
              </div>
            )}

            {senzaGps.size > 0 && (
              <div className="map-avviso">
                {avvisoSenzaGps(senzaGps.size, disposizione.kmPerUnita !== null)}
              </div>
            )}

            {/* Con le stazioni sulle coordinate vere le distanze sui due assi
                valgono gli stessi chilometri: la barra dice quanti. */}
            {scalaGrafica && (
              <div className="map-scala" title="Le stazioni sono disposte sulle coordinate GPS reali">
                <span
                  className="map-scala__barra"
                  style={{ width: `${scalaGrafica.larghezza}px` }}
                />
                <span className="map-scala__testo">{formattaScala(scalaGrafica.km)}</span>
              </div>
            )}
          </div>

          {/* Menù di scelta fra gli elementi che condividono lo stesso punto. */}
          {selettore && (
            <>
              <div className="map-chooser-backdrop" onClick={() => setSelettore(null)} />
              <div className="map-chooser" style={{ left: selettore.x, top: selettore.y }}>
                <div className="map-chooser__testata">
                  <span>{selettore.elementi.length} elementi qui</span>
                  <button
                    type="button"
                    className="map-chooser__chiudi"
                    onClick={() => setSelettore(null)}
                    title="Chiudi"
                  >
                    <X size={14} />
                  </button>
                </div>
                <div className="map-chooser__lista">
                  {selettore.elementi.map(elemento => (
                    <button
                      type="button"
                      key={elemento.chiave}
                      className="map-chooser__voce"
                      onClick={() => seleziona(elemento)}
                      onPointerEnter={() => setEvidenziato(elemento.chiave)}
                      onPointerLeave={() => setEvidenziato(null)}
                    >
                      <span
                        className={`map-chooser__pallino${elemento.tipo === 'treno' ? ' map-chooser__pallino--treno' : ''}`}
                        style={{ background: elemento.colore }}
                      />
                      <span className="map-chooser__testo">
                        <span className="map-chooser__nome">{elemento.nome}</span>
                        <span className="map-chooser__nota">{elemento.nota}</span>
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            </>
          )}
        </div>

        {/* Pannello di dettaglio */}
        <Card
          title="Dettagli"
          className="map-detail-card"
          action={selezione && (
            <button
              type="button"
              className="map-tool-btn"
              onClick={() => setSelezione(null)}
              title="Chiudi la scheda"
            >
              <X size={15} />
            </button>
          )}
        >
          {!stazioneSelezionata && !trenoSelezionato && (
            <div className="map-detail-vuoto">
              <p>Seleziona un treno o una stazione sulla mappa per vederne i dettagli.</p>
              <p>
                Dove più elementi finiscono nello stesso punto vengono disposti a raggiera:
                il disco numerato al centro apre l&apos;elenco per scegliere quale aprire.
              </p>
            </div>
          )}

          {stazioneSelezionata && (
            <div className="animate-fade-in">
              <h3 className="map-detail__titolo">{stazioneSelezionata.name}</h3>
              <p className="map-detail__sottotitolo font-mono">
                Codice: {stazioneSelezionata.code}
                {stazioneSelezionata.city ? ` · ${stazioneSelezionata.city}` : ''}
              </p>

              <div className="map-detail__stati">
                <Badge type={stazioneSelezionata.status === 'operativa' ? 'success' : 'danger'}>
                  {descriviStato(stazioneSelezionata.status)}
                </Badge>
                {stazioneSelezionata.operatorsDispatched && (
                  <Badge type="warning">Squadra inviata</Badge>
                )}
              </div>

              {stazioneSelezionata.faultDescription && (
                <div className="map-detail__anomalia">
                  <strong>Anomalia:</strong> {stazioneSelezionata.faultDescription}
                </div>
              )}

              <div className="map-detail__righe">
                <div className="map-detail__riga">
                  <span className="map-detail__etichetta">Binari</span>
                  <span className="map-detail__valore">{stazioneSelezionata.platforms}</span>
                </div>
                <div className="map-detail__riga">
                  <span className="map-detail__etichetta">Coordinate GPS</span>
                  <span className="map-detail__valore font-mono">
                    {descriviCoordinate(stazioneSelezionata)}
                  </span>
                </div>
                <div className="map-detail__riga">
                  <span className="map-detail__etichetta">Ultimo heartbeat</span>
                  <span className="map-detail__valore">
                    {stazioneSelezionata.lastHeartbeat
                      ? new Date(stazioneSelezionata.lastHeartbeat).toLocaleTimeString()
                      : '—'}
                  </span>
                </div>
                <div className="map-detail__riga">
                  <span className="map-detail__etichetta">Tratte collegate</span>
                  <span className="map-detail__valore">{archiEvidenziati.size}</span>
                </div>
              </div>

              <div className="map-detail__sezione">
                <div className="map-detail__sezione-titolo">Treni in stazione</div>
                {treniInStazione.length === 0 ? (
                  <p className="text-sm text-muted">Nessun convoglio presente.</p>
                ) : (
                  treniInStazione.map(train => (
                    <button
                      type="button"
                      key={train.id}
                      className="map-detail__collegamento"
                      onClick={() => setSelezione({ tipo: 'treno', id: train.id })}
                    >
                      <TrainIcon size={14} />
                      {train.id}
                    </button>
                  ))
                )}
              </div>
            </div>
          )}

          {trenoSelezionato && (
            <div className="animate-fade-in">
              <h3 className="map-detail__titolo font-mono">{trenoSelezionato.id}</h3>
              <p className="map-detail__sottotitolo">
                {itinerarioTreno ? `${itinerarioTreno.name} · ` : ''}
                corsa di {trenoSelezionato.direction}
              </p>

              <div className="map-detail__stati">
                <Badge
                  type={trenoSelezionato.status === 'in_viaggio'
                    ? 'info'
                    : trenoSelezionato.status === 'in_ritardo'
                      ? 'warning'
                      : trenoSelezionato.status === 'guasto' ? 'danger' : 'neutral'}
                >
                  {descriviStato(trenoSelezionato.status)}
                </Badge>
                {trenoSelezionato.delayMinutes > 0 && (
                  <Badge type="danger">+{trenoSelezionato.delayMinutes} min</Badge>
                )}
              </div>

              {/* La barra misura l'avanzamento SULLA TRATTA, quindi ha senso solo mentre
                  il convoglio la sta percorrendo e solo se i due estremi sono stazioni
                  diverse. A treno fermo restava a schermo un 100% che non voleva dire
                  niente (la sosta non è un avanzamento) e, quando gli estremi
                  coincidevano, la barra saliva fra una stazione e sé stessa. */}
              {inMarciaSelezionato && trattaSelezionataNota ? (
                <div className="map-detail__progresso">
                  <div className="map-detail__progresso-barra">
                    <div
                      className="map-detail__progresso-riempimento"
                      style={{
                        width: `${Math.max(0, Math.min(100, trenoSelezionato.progressPercent))}%`,
                      }}
                    />
                  </div>
                  <div className="map-detail__progresso-estremi">
                    <span>{nomeStazione(trenoSelezionato.previousStationId)}</span>
                    <span>{Math.round(trenoSelezionato.progressPercent)}%</span>
                    <span>{nomeStazione(trenoSelezionato.nextStationId)}</span>
                  </div>
                </div>
              ) : (
                <p className="map-detail__sottotitolo">
                  {trenoSelezionato.currentStationId
                    ? `In sosta a ${nomeStazione(trenoSelezionato.currentStationId)}`
                    : 'Avanzamento sulla tratta non disponibile'}
                  {trenoSelezionato.nextStationId
                    ? ` · prossima fermata ${nomeStazione(trenoSelezionato.nextStationId)}`
                    : ''}
                </p>
              )}

              <div className="map-detail__righe">
                <div className="map-detail__riga">
                  <span className="map-detail__etichetta">Da (ultima stazione)</span>
                  <span className="map-detail__valore">
                    {nomeStazione(trenoSelezionato.previousStationId)}
                  </span>
                </div>
                <div className="map-detail__riga">
                  <span className="map-detail__etichetta">Verso (prossima)</span>
                  <span className="map-detail__valore">
                    {nomeStazione(trenoSelezionato.nextStationId)}
                  </span>
                </div>
                <div className="map-detail__riga">
                  <span className="map-detail__etichetta">Passeggeri</span>
                  <span className="map-detail__valore">{trenoSelezionato.passengers}</span>
                </div>
                {trenoSelezionato.arrivalTime && (
                  <div className="map-detail__riga">
                    <span className="map-detail__etichetta">Arrivo previsto</span>
                    <span className="map-detail__valore">
                      {new Date(trenoSelezionato.arrivalTime).toLocaleTimeString()}
                    </span>
                  </div>
                )}
                <div className="map-detail__riga">
                  <span className="map-detail__etichetta">Ultimo aggiornamento</span>
                  <span className="map-detail__valore">
                    {new Date(trenoSelezionato.lastUpdate).toLocaleTimeString()}
                  </span>
                </div>
              </div>

              {trenoSelezionato.currentStationId && (
                <div className="map-detail__sezione">
                  <div className="map-detail__sezione-titolo">Stazione attuale</div>
                  <button
                    type="button"
                    className="map-detail__collegamento"
                    onClick={() => setSelezione({
                      tipo: 'stazione',
                      id: trenoSelezionato.currentStationId!,
                    })}
                  >
                    <Building2 size={14} />
                    {nomeStazione(trenoSelezionato.currentStationId)}
                  </button>
                </div>
              )}
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
