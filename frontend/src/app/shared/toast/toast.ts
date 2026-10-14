import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { ToastStore } from '../../core/toast.store';

/** Transient notifications (docs/API.md UI): a single polite live region, bottom center. */
@Component({
  selector: 'app-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (toast.message(); as message) {
      <output
        aria-live="polite"
        class="pointer-events-auto flex items-center gap-3 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm text-slate-700 shadow-sm"
      >
        <span>{{ message }}</span>
        <button
          type="button"
          class="rounded-md px-1 text-xs font-medium text-slate-500 hover:bg-slate-50 hover:text-slate-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
          (click)="toast.dismiss()"
        >
          Dismiss
        </button>
      </output>
    }
  `,
})
export class Toast {
  protected readonly toast = inject(ToastStore);
}
