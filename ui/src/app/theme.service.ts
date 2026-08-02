import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

const STORAGE_KEY = 'sql-explorer.theme';

export interface ThemeOption {
  /** value of the data-theme attribute ('' = default) */
  id: string;
  label: string;
}

export const THEMES: ThemeOption[] = [
  { id: '', label: 'Midnight (default)' },
  { id: 'solarized-dark', label: 'Solarized Dark' },
  { id: 'solarized-light', label: 'Solarized Light' },
  { id: 'darcula', label: 'Darcula' },
];

/**
 * Swaps color themes by setting data-theme on <html>; every theme is a set of
 * CSS custom property overrides in styles.css. index.html applies the stored
 * theme before Angular boots to avoid a flash of the wrong theme.
 */
@Injectable({
  providedIn: 'root'
})
export class ThemeService {

  readonly current$ = new BehaviorSubject<string>(localStorage.getItem(STORAGE_KEY) ?? '');

  constructor() {
    this.apply(this.current$.value);
  }

  getCurrent(): string {
    return this.current$.value;
  }

  setCurrent(id: string): void {
    if (id) {
      localStorage.setItem(STORAGE_KEY, id);
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
    this.apply(id);
    this.current$.next(id);
  }

  private apply(id: string): void {
    if (id) {
      document.documentElement.setAttribute('data-theme', id);
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
  }
}
