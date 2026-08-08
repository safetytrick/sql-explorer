/**
 * The merge group behind one profile, shaped for display.
 *
 * A profile can absorb many queries that normalized to the same text. Those
 * members are *not* one-per-MySQL-digest: the same statement running on
 * several servers or schemas arrives as several members sharing one digest
 * (in the live corpus a single profile had 22 members and one distinct
 * digest). Counting members would tell a reader "22 variants" when there is
 * one. So the group is folded by digest, and the member count rides along.
 */
import type {MergedChild, RepeatedBlock} from '../query-search.service';

export interface MergeVariant {
  /** The MySQL performance-schema digest — the lookup key worth showing. */
  digest: string;
  /** Members that carried this digest. */
  members: number;
  /** Executions summed across those members. */
  executed: number;
  /** Execution-weighted mean of the members' average times. */
  averageTime: number;
  /** Repeat counts this variant collapsed, in the representative's order. */
  repeatCounts: number[];
  /** True when those counts differ from the representative's. */
  differs: boolean;
}

export function repeatCounts(blocks: RepeatedBlock[] | null | undefined): number[] {
  return (blocks ?? []).map(block => block.count);
}

function sameCounts(left: number[], right: number[]): boolean {
  return left.length === right.length && left.every((count, index) => count === right[index]);
}

/**
 * Fold a profile's merged children into one row per distinct digest.
 * Ordered by executions, descending — the variant carrying the load first.
 */
export function mergeVariants(children: MergedChild[] | null | undefined,
                              representativeBlocks: RepeatedBlock[] | null | undefined): MergeVariant[] {
  const representative = repeatCounts(representativeBlocks);
  const byDigest = new Map<string, MergeVariant>();

  for (const child of children ?? []) {
    const counts = repeatCounts(child.repeatedBlocks);
    const existing = byDigest.get(child.digest);
    if (!existing) {
      byDigest.set(child.digest, {
        digest: child.digest,
        members: 1,
        executed: child.executed,
        averageTime: child.averageTime,
        repeatCounts: counts,
        differs: !sameCounts(counts, representative)
      });
      continue;
    }
    // weighted before the totals move, or the weighting uses the new total
    const totalExecuted = existing.executed + child.executed;
    existing.averageTime = totalExecuted === 0
      ? existing.averageTime
      : (existing.averageTime * existing.executed + child.averageTime * child.executed) / totalExecuted;
    existing.executed = totalExecuted;
    existing.members += 1;
    // members sharing a digest can still have collapsed different amounts;
    // surface that rather than hiding it behind the first one seen
    existing.differs = existing.differs || !sameCounts(counts, representative);
  }

  return [...byDigest.values()].sort((left, right) => right.executed - left.executed);
}
