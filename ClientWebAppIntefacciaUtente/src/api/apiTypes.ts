import type { User } from '../types';

export interface ApiDashboardPayload {
  totalTrains: number;
  trainsInMotion: number;
  trainsDelayed: number;
  stationsOperative: number;
  stationsFaulty: number;
  activeAlerts: number;
  avgDelay: number;
}

export interface ApiTrainPayload {
  id: string;
  nome?: string;
  stato?: string;
  posizioneAttualeTratta?: {
    stazioneArrivo?: { id: string };
    stazionePartenza?: { id: string };
  };
  itinerario?: { id: string };
  ritardo?: number;
  passeggeri?: number;
  progresso?: number;
  ultimoAggiornamento?: string;
}

export interface ApiStationPayload {
  id: string;
  nome?: string;
  stato?: string;
  latitudine?: number;
  longitudine?: number;
  binari?: number;
  ultimoHeartbeat?: string;
}

export interface ApiRoutePayload {
  id: string;
  nome?: string;
  stazioni?: string[];
  attivo?: boolean;
  treniIds?: string[];
}

export interface ApiAlertPayload {
  id: string;
  tipo?: string;
  severita?: string;
  messaggio?: string;
  sorgenteId?: string;
  timestamp?: string;
  risolto?: boolean;
  timestampRisoluzione?: string;
}

export interface ApiTransitPayload {
  id: string;
  trainId?: string;
  trenoId?: string;
  stationId?: string;
  stazioneId?: string;
  tipo?: string;
  type?: string;
  timestamp?: string;
  delayed?: boolean;
  inRitardo?: boolean;
  delayMinutes?: number;
  ritardo?: number;
}

export interface ApiLoginResponse {
  token: string;
  user: User;
}
