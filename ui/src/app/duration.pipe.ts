import {Pipe, PipeTransform} from '@angular/core';

/**
 * Formats a duration value (in milliseconds) to a human-readable string.
 * Replaces the ngx-moment amDuration pipe.
 */
@Pipe({
    name: 'duration',
    standalone: false
})
export class DurationPipe implements PipeTransform {
  transform(ms: number): string {
    if (!ms || ms <= 0) { return '0s'; }
    const totalSeconds = Math.floor(ms / 1000);
    const days = Math.floor(totalSeconds / 86400);
    const hours = Math.floor((totalSeconds % 86400) / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (days > 0) { return `${days}d ${hours}h`; }
    if (hours > 0) { return `${hours}h ${minutes}m`; }
    if (minutes > 0) { return `${minutes}m ${seconds}s`; }
    return `${seconds}s`;
  }
}
