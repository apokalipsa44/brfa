import { DestroyRef, inject, Injectable } from '@angular/core';

import { AnalysesStore, isWorking } from './analyses.store';

/**
 * Stops running analyses when the tab goes away (refresh or close), so a local LLM aborts the
 * prompt nobody will read instead of generating it to the end. `pagehide` is the reliable lifecycle
 * event and `navigator.sendBeacon` the only POST that survives an unload. A `persisted` event means
 * the page entered the back/forward cache and is not actually gone, so the run is left alone.
 *
 * The guard is a root service: injecting it (from the shell) installs the listener; destroying the
 * injector removes it. It only knows about analyses that are still working.
 */
@Injectable({ providedIn: 'root' })
export class PageLeaveGuard {
  private readonly analyses = inject(AnalysesStore);

  constructor() {
    window.addEventListener('pagehide', this.onPageHide);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('pagehide', this.onPageHide));
  }

  private readonly onPageHide = (event: PageTransitionEvent): void => {
    if (event.persisted) return;
    for (const analysis of this.analyses.analyses()) {
      if (isWorking(analysis.stage)) {
        navigator.sendBeacon(`/api/analyses/${encodeURIComponent(analysis.id)}/cancel`);
      }
    }
  };
}
