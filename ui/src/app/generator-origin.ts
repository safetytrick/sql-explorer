import type {GeneratorCandidate} from './api-types';
import type {GeneratorInfo} from './annotation.service';

/**
 * Which generator wrote a statement, as far as this environment lets us say
 * (sqlx-n5j.12).
 *
 * <p>Analysis stores CANDIDATES — every generator whose signature matched,
 * with the rule. Two generators can share a shape, so a candidate becomes an
 * asserted origin only when a person has confirmed the environment uses that
 * generator. That is applied here, when read, so confirming needs no
 * re-analysis:
 *
 * <ul>
 *   <li><b>asserted</b> — exactly one matching generator is confirmed as used;
 *   <li><b>ambiguous</b> — more than one is, and the statement fits both;
 *   <li><b>candidate</b> — none is confirmed, so every match is shown as one;
 *   <li><b>none</b> — nothing matched, or only generators a person has said
 *       this environment does not use.
 * </ul>
 */
export type OriginKind = 'asserted' | 'ambiguous' | 'candidate' | 'none';

export interface GeneratorOrigin {
  kind: OriginKind;
  generators: string[];
  /** Generator → the rules that matched, for the hover text. */
  rules: Map<string, string[]>;
}

export type GeneratorStatuses = Map<string, 'used' | 'not-used' | null>;

export function generatorStatuses(generators: GeneratorInfo[]): GeneratorStatuses {
  return new Map(generators.map(generator => [generator.name, generator.status]));
}

export function generatorOrigin(candidates: GeneratorCandidate[] | null | undefined,
                                statuses: GeneratorStatuses): GeneratorOrigin {
  const rules = new Map<string, string[]>();
  for (const candidate of candidates ?? []) {
    if (statuses.get(candidate.generator) === 'not-used') { continue; }
    rules.set(candidate.generator, [...(rules.get(candidate.generator) ?? []), candidate.rule]);
  }
  const matched = [...rules.keys()];
  const confirmed = matched.filter(generator => statuses.get(generator) === 'used');
  if (confirmed.length === 1) {
    return {kind: 'asserted', generators: confirmed, rules};
  }
  if (confirmed.length > 1) {
    return {kind: 'ambiguous', generators: confirmed, rules};
  }
  return {kind: matched.length ? 'candidate' : 'none', generators: matched, rules};
}

/** How an origin reads on a page: `doctrine-orm`, `doctrine-orm or hibernate`, `candidate: doctrine-orm`. */
export function originLabel(origin: GeneratorOrigin): string {
  switch (origin.kind) {
    case 'asserted': return origin.generators[0];
    case 'ambiguous': return origin.generators.join(' or ');
    case 'candidate': return (origin.generators.length > 1 ? 'candidates: ' : 'candidate: ') + origin.generators.join(', ');
    default: return '';
  }
}

/** Which rules matched, for hover text. */
export function originRules(origin: GeneratorOrigin): string {
  return [...origin.rules.entries()].map(([generator, rules]) => generator + ': ' + rules.join(', ')).join('; ');
}
