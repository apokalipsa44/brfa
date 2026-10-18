import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { AnalysesStore } from '../../core/analyses.store';
import { CvContextStore } from '../../core/cv-context.store';
import { modelStars } from '../../core/model-rating';

/**
 * LM Studio model picker (docs/API.md §3). Independent of the CV and of any analysis: the choice is
 * stored server-side, not part of the analysis payload. A status dot shows what is loaded; when no
 * model is ready the hint explains why, with a refresh action. The picker is locked while an
 * analysis runs — a switch would unload the model it is using (docs/API.md §6.3).
 */
@Component({
  selector: 'app-model-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './model-select.html',
})
export class ModelSelect {
  protected readonly cvContext = inject(CvContextStore);
  protected readonly analyses = inject(AnalysesStore);

  onSelect(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (value) this.cvContext.selectModel(value);
  }

  /** Tooltip: why the picker is locked, else the active id (docs/API.md §6.3). */
  protected readonly pickerTitle = computed(() =>
    this.analyses.anyWorking()
      ? 'An analysis is running — wait for it to finish or cancel it to switch the model.'
      : (this.cvContext.activeModelId() ?? ''),
  );

  /** Model ids prefixed with a heuristic star bar (core/model-rating.ts), precomputed for the template. */
  protected readonly options = computed(() =>
    this.cvContext.models().map((model) => ({
      id: model.id,
      label: `${modelStars(model.id)} ${model.id}`,
    })),
  );

  /** Green when a model is ready, amber while loading, grey with no candidates, red otherwise. */
  protected readonly dotTone = computed(() => {
    const ctx = this.cvContext;
    if (ctx.ready()) return 'bg-emerald-500';
    if (ctx.selecting() || ctx.refreshing()) return 'bg-amber-400';
    if (ctx.models().length === 0) return 'bg-slate-300';
    return 'bg-red-400';
  });
}
