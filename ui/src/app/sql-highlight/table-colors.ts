import {Source} from './sql-scopes';

/** Number of table colours; the hues themselves live in styles.css (`.sqt-c0`…). */
export const PALETTE_SIZE = 10;

/**
 * The order a taken slot is probed in. The palette's hues go round the wheel,
 * so +5 is the opposite colour: the second instance of a self-join lands as
 * far from the first as the palette allows, not on its neighbour.
 */
const PROBE = [0, 5, 3, 7, 2, 8, 1, 6, 4, 9];

/** FNV-1a: stable across sessions and browsers, which is the whole point. */
export function hashName(name: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < name.length; i++) {
    h ^= name.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** The slot a table gets whenever it has the query to itself. */
export function preferredSlot(colorKey: string): number {
  return hashName(colorKey) % PALETTE_SIZE;
}

/**
 * One colour per table instance. Each takes its name's preferred slot unless
 * an earlier instance in the same statement already holds it, so `account`
 * is the same colour in every query where it appears once, and the two
 * `account`s of a self-join are never the same colour. Past PALETTE_SIZE
 * instances colours repeat; there is no way round that with a fixed palette.
 */
export function assignColors(sources: readonly Source[]): Map<Source, number> {
  const out = new Map<Source, number>();
  const taken = new Set<number>();
  for (const s of sources) {
    const base = preferredSlot(s.colorKey);
    const slot = PROBE.map(o => (base + o) % PALETTE_SIZE).find(c => !taken.has(c)) ?? base;
    taken.add(slot);
    out.set(s, slot);
    if (taken.size === PALETTE_SIZE) { taken.clear(); }
  }
  return out;
}
