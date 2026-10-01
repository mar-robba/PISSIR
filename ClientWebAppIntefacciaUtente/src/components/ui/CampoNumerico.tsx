import { useState } from 'react';
import type { InputHTMLAttributes } from 'react';

interface CampoNumericoProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'onChange'> {
  valore: number;
  onCambia: (valore: number) => void;
}

/**
 * Campo numerico dei form di amministrazione (tempi di percorrenza, coordinate).
 *
 * Un <input type="number"> legato direttamente al numero si porta dietro lo 0: il
 * campo vuoto vale 0, React ci riscrive "0" e quello che si digita dopo gli finisce
 * in coda ("08899"). Qui il campo tiene il testo così come lo si scrive e passa
 * fuori solo il numero. Lo 0, che in questi form vuol dire "non impostato", si
 * mostra come campo vuoto (con il placeholder, se c'è).
 */
export default function CampoNumerico({ valore, onCambia, ...resto }: CampoNumericoProps) {
  const [testo, setTesto] = useState('');

  // Il testo scritto resta finché corrisponde al numero (Number('') fa 0, quindi il
  // campo svuotato resta vuoto); se il numero cambia da fuori, per esempio perché si
  // è tolta una stazione dall'itinerario, si mostra quello nuovo.
  const mostrato = Number(testo) === valore ? testo : valore === 0 ? '' : String(valore);

  return (
    <input
      {...resto}
      type="number"
      value={mostrato}
      onChange={e => {
        setTesto(e.target.value);
        onCambia(Number(e.target.value));
      }}
    />
  );
}
