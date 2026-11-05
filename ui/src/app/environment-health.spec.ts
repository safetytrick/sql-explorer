import {environmentState, environmentStatusLine, isProblem, ageOf} from './environment-health';
import {EnvironmentState, EnvironmentSummary} from './environment.service';

const NOW = new Date('2026-11-05T12:00:00Z');

function env(state: EnvironmentState, problems: string[] = [], oldestCapture: string | null = '2026-11-05T06:00:00Z'): EnvironmentSummary {
  return {
    name: 'offline-dummy', servers: [],
    sources: {loaded: true, sourceCount: 2, loadedOn: null, importStats: {count: 216000} as never},
    health: {state, oldestCapture, maxAgeMinutes: 1440, problems, lastPull: null},
  };
}

describe('environment health', () => {
  it('reads a cached environment with its age and size', () => {
    expect(environmentStatusLine(env('CACHED'), NOW)).toBe('cached · 6h old · 216k statements');
    expect(isProblem('CACHED')).toBeFalse();
  });

  it('counts problems instead of statements when something is wrong', () => {
    const partial = env('PARTIAL', ['dummy-replica: nothing to serve']);
    expect(environmentStatusLine(partial, NOW)).toBe('partial · 6h old · 1 problem');
    expect(isProblem('PARTIAL')).toBeTrue();
    expect(isProblem('STALE')).toBeTrue();
    expect(isProblem('UNAVAILABLE')).toBeTrue();
  });

  it('gives no age for an environment with nothing to serve', () => {
    expect(environmentStatusLine(env('UNAVAILABLE', ['x'], null), NOW)).toBe('unavailable · 1 problem');
  });

  it('falls back to loaded or not when the backend reports no state', () => {
    const legacy: EnvironmentSummary = {name: 'regression', servers: [],
      sources: {loaded: false, sourceCount: 0, loadedOn: null, importStats: null}};
    expect(environmentState(legacy)).toBe('UNAVAILABLE');
    expect(environmentState({...legacy, sources: {...legacy.sources, loaded: true}})).toBe('LIVE');
  });

  it('says ages coarsely', () => {
    expect(ageOf('2026-11-02T11:00:00Z', NOW)).toBe('3d');
    expect(ageOf('2026-11-05T11:20:00Z', NOW)).toBe('40m');
    expect(ageOf(null, NOW)).toBeNull();
  });
});
