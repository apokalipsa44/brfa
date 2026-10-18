import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { ToastStore } from '../../core/toast.store';

/** Transient notifications (docs/API.md UI): a single polite live region, bottom center. */
@Component({
  selector: 'app-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './toast.html',
})
export class Toast {
  protected readonly toast = inject(ToastStore);
}
