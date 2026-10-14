import { Injectable, signal } from '@angular/core';

/** Small transient notifications (e.g. an analysis finished while the user was elsewhere). */
@Injectable({ providedIn: 'root' })
export class ToastStore {
  private readonly _message = signal<string | null>(null);
  private timer = 0;

  readonly message = this._message.asReadonly();

  show(message: string, durationMs = 6000): void {
    this._message.set(message);
    if (this.timer) clearTimeout(this.timer);
    this.timer = window.setTimeout(() => this._message.set(null), durationMs);
  }

  dismiss(): void {
    if (this.timer) clearTimeout(this.timer);
    this._message.set(null);
  }
}
