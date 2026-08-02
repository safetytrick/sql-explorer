import { Injectable } from '@angular/core';

/**
 * Holds the visibility of the Explain panel. Extracted from AppComponent so
 * NavBarComponent doesn't have to inject the root component — that import
 * created an app.component ↔ nav-bar.component cycle whose behavior depended
 * on module evaluation order.
 */
@Injectable({
  providedIn: 'root'
})
export class ExplainService {

  showExplain = false;

  toggle(): void {
    this.showExplain = !this.showExplain;
  }
}
