import {compactCount} from './compact-count';
import {EnvironmentState, EnvironmentSummary} from './environment.service';

/**
 * How the environment selector reads an environment's state (sqlx-nbi.8).
 *
 * A problem has to read as one at a glance, on the status button itself, not
 * only once the chooser is opened: every number on every page is about the
 * environment, and a corpus missing a server looks exactly like a complete one.
 */

/** The state, falling back to loaded / not loaded for a backend that does not report one. */
export function environmentState(env: EnvironmentSummary): EnvironmentState {
  if (env.health) { return env.health.state; }
  return env.sources?.loaded ? 'LIVE' : 'UNAVAILABLE';
}

/** Stale, partial or unavailable: something the reader must know before trusting a number. */
export function isProblem(state: EnvironmentState): boolean {
  return state === 'STALE' || state === 'PARTIAL' || state === 'UNAVAILABLE';
}

export const STATE_LABEL: Record<EnvironmentState, string> = {
  LIVE: 'live',
  CACHED: 'cached',
  PULLING: 'pulling',
  STALE: 'stale',
  PARTIAL: 'partial',
  UNAVAILABLE: 'unavailable',
};

/** What each state means, for hover text. */
export const STATE_MEANING: Record<EnvironmentState, string> = {
  LIVE: 'Read live from its servers; every source present and within the max age.',
  CACHED: 'Served from its cache. No server is connected until you pull it.',
  PULLING: 'Being read from its servers now; what you see meanwhile is what was there before.',
  STALE: 'Every source is present, but some data is older than the max age or the last pull failed.',
  PARTIAL: 'Some sources have no data at all: no capture, and they could not be read. Numbers leave them out.',
  UNAVAILABLE: 'Nothing to show: no capture, and its servers could not be read.',
};

/** "3d", "5h", "40m": how old, coarsely. */
export function ageOf(from: string | null | undefined, now: Date = new Date()): string | null {
  if (!from) { return null; }
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(from).getTime()) / 60000));
  if (minutes >= 60 * 24) { return `${Math.floor(minutes / (60 * 24))}d`; }
  if (minutes >= 60) { return `${Math.floor(minutes / 60)}h`; }
  return `${minutes}m`;
}

/** "cached · 6h old · 216k statements", "partial · 1 problem", "live · loaded Nov 4, 9:29 PM". */
export function environmentStatusLine(env: EnvironmentSummary, now: Date = new Date()): string {
  const state = environmentState(env);
  const parts: string[] = [STATE_LABEL[state]];
  const age = ageOf(env.health?.oldestCapture, now);
  if (age && state !== 'UNAVAILABLE') { parts.push(`${age} old`); }
  const problems = env.health?.problems.length ?? 0;
  if (isProblem(state) && problems > 0) {
    parts.push(`${problems} problem${problems === 1 ? '' : 's'}`);
  } else if (env.sources?.loaded) {
    const count = env.sources.importStats?.count;
    parts.push(count === null || count === undefined
      ? `${env.sources.sourceCount} source${env.sources.sourceCount === 1 ? '' : 's'}`
      : `${compactCount(count)} statements`);
  }
  return parts.join(' · ');
}
