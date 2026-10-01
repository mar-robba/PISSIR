import Badge from './Badge';
import { StatoAutoma } from '../../types';
import './StatoComposto.css';

interface StatoCompostoProps {
  /** Lo stato di ogni automa dell'elemento, da statiDelTreno o statiDellaStazione. */
  stati: StatoAutoma[];
  className?: string;
}

/**
 * Lo stato composto di un treno o di una stazione: un badge per ogni automa, tutti
 * insieme, perché l'elemento è in tutti quegli stati nello stesso momento.
 *
 * Passandoci sopra col mouse ogni badge dice di quale automa è il valore: "REGOLARE" e
 * "BLOCCATO IN TRATTA" sono due risposte a due domande diverse (come sta il convoglio, e
 * dove si trova), e chi legge deve poterlo sapere.
 */
export default function StatoComposto({ stati, className = '' }: StatoCompostoProps) {
  return (
    <span className={`stato-composto ${className}`}>
      {stati.map(stato => (
        <Badge key={stato.automa} type={stato.tono} title={stato.automa}>
          {stato.valore.replace(/_/g, ' ')}
        </Badge>
      ))}
    </span>
  );
}
