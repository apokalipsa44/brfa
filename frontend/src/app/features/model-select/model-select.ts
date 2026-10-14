import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { CvContextStore } from '../../core/cv-context.store';

/**
 * LM Studio model picker (docs/API.md §3). Independent of the CV and of any analysis: the choice is
 * stored server-side, not part of the analysis payload. A status dot shows what is loaded; when no
 * model is ready the hint explains why, with a refresh action.
 */
@Component({
  selector: 'app-model-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex items-center gap-2">
      <span aria-hidden="true" class="flex h-2.5 w-2.5 shrink-0 rounded-full" [class]="dotTone()"></span>
      <label for="model" class="sr-only">Active model</label>
      <select
        id="model"
        class="w-[clamp(14rem,28vw,26rem)] min-w-0 cursor-pointer truncate rounded-lg border border-slate-300 bg-white px-2 py-1.5 text-sm text-slate-800 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
        [value]="cvContext.activeModelId() ?? ''"
        [title]="cvContext.activeModelId() ?? ''"
        [disabled]="cvContext.selecting() || cvContext.models().length === 0"
        (change)="onSelect($event)"
      >
        @if (cvContext.models().length === 0) {
          <option value="">No models</option>
        } @else {
          @if (cvContext.activeModelId() === null) {
            <option value="">Select a model</option>
          }
          @for (model of cvContext.models(); track model.id) {
            <option [value]="model.id">{{ model.id }}</option>
          }
        }
      </select>
      <button
        type="button"
        class="rounded-md p-1.5 text-slate-500 hover:bg-slate-100 hover:text-slate-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none disabled:opacity-50"
        [disabled]="cvContext.refreshing()"
        aria-label="Refresh model list"
        (click)="cvContext.refreshModels()"
      >
        <span [class.animate-spin]="cvContext.refreshing()" aria-hidden="true">⟳</span>
      </button>
    </div>
  `,
})
export class ModelSelect {
  protected readonly cvContext = inject(CvContextStore);

  onSelect(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (value) this.cvContext.selectModel(value);
  }

  /** Green when a model is ready, amber while loading, grey with no candidates, red otherwise. */
  protected readonly dotTone = computed(() => {
    const ctx = this.cvContext;
    if (ctx.ready()) return 'bg-emerald-500';
    if (ctx.selecting() || ctx.refreshing()) return 'bg-amber-400';
    if (ctx.models().length === 0) return 'bg-slate-300';
    return 'bg-red-400';
  });
}
