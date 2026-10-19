import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { STAGE_LABELS, isWorking } from '../../core/analyses.store';
import { formatDateTime } from '../../core/format';
import { AnalysisSummary } from '../../core/models';
import { AppliedToggle } from '../../shared/applied-toggle/applied-toggle';
import { InlineTitleEdit } from '../../shared/inline-title-edit/inline-title-edit';
import { ScoreBadge } from '../../shared/score-badge/score-badge';

/**
 * One sidebar card (docs/API.md §4). Working analyses get a compact row (title or placeholder,
 * spinner, stage); finished ones get a card with the title, the evaluation's short description and a
 * status line. The select control stretches over the card; the score, the rename pencil, the delete
 * button and the applied marker live in a reserved right-hand column so they never sit under the text.
 */
@Component({
  selector: 'app-analysis-card',
  imports: [ScoreBadge, AppliedToggle, InlineTitleEdit],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'listitem' },
  templateUrl: './analysis-card.html',
})
export class AnalysisCard {
  readonly summary = input.required<AnalysisSummary>();
  readonly isSelected = input(false);

  readonly selected = output<string>();
  readonly renamed = output<{ id: string; title: string }>();
  readonly appliedChanged = output<{ id: string; applied: boolean }>();
  readonly deleted = output<string>();
  readonly cancelled = output<string>();

  protected readonly editing = signal(false);

  protected readonly compact = computed(() => isWorking(this.summary().stage));

  /** The applied marker only makes sense on a review or a finished analysis. */
  protected readonly showApplied = computed(() => {
    const stage = this.summary().stage;
    return stage === 'AWAITING_REVIEW' || stage === 'COMPLETED';
  });

  /** Falls back to a placeholder until evaluation generates a title. */
  protected readonly title = computed(() => this.summary().title || 'Untitled analysis');

  protected readonly stageLabel = computed(
    () => STAGE_LABELS[this.summary().stage] ?? this.summary().stage,
  );

  protected readonly statusClasses = computed(() => {
    const stage = this.summary().stage;
    if (stage === 'FAILED') return 'text-red-700';
    if (stage === 'CANCELLED') return 'text-slate-500';
    if (stage === 'AWAITING_REVIEW') return 'text-indigo-700';
    if (stage === 'COMPLETED') return 'text-emerald-700';
    return 'text-indigo-600';
  });

  protected readonly statusGlyph = computed(() => {
    const stage = this.summary().stage;
    if (stage === 'COMPLETED') return '✓';
    if (stage === 'FAILED') return '!';
    if (stage === 'CANCELLED') return '×';
    return '';
  });

  protected readonly scoreLabel = computed(() => {
    const score = this.summary().score;
    return score === null ? null : `Match score ${score} out of 100`;
  });

  protected startRename(): void {
    this.editing.set(true);
  }

  protected onSaved(title: string): void {
    this.editing.set(false);
    if (title !== this.summary().title) this.renamed.emit({ id: this.summary().id, title });
  }

  protected readonly formatDateTime = formatDateTime;
}
