import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { AnalysesStore } from '../../core/analyses.store';
import { CvContextStore } from '../../core/cv-context.store';
import { MAX_JOB_POSTING_CHARS } from '../../core/models';

/**
 * New analysis form (docs/API.md §4): a posting textarea plus the single primary action. The CV and
 * the model are context, not inputs. After the 202 the form clears and stays usable — the pipeline
 * runs in the background.
 */
@Component({
  selector: 'app-new-analysis-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './new-analysis-form.html',
})
export class NewAnalysisForm {
  protected readonly analyses = inject(AnalysesStore);
  protected readonly cv = inject(CvContextStore);

  protected readonly maxChars = MAX_JOB_POSTING_CHARS;

  private readonly posting = signal('');

  protected readonly length = computed(() => this.posting().length);

  /** True when the input is a single http(s) link, so the backend fetches it (docs/API.md §4). */
  protected readonly isLink = computed(() => URL_PATTERN.test(this.posting().trim()));

  /** Single source for the disabled state and the one-line helper text. */
  protected readonly hint = computed<string | null>(() => {
    if (!this.cv.hasCv()) return 'Upload a CV to continue.';
    if (!this.cv.ready()) return this.cv.reason() ?? 'Load a model in LM Studio to continue.';
    if (this.length() === 0) return 'Paste a job posting or a link to continue.';
    if (!this.isLink() && this.length() > this.maxChars)
      return `The posting is over ${this.maxChars} characters.`;
    return null;
  });

  protected readonly canSubmit = computed(
    () =>
      this.cv.hasCv() &&
      this.cv.ready() &&
      this.length() > 0 &&
      (this.isLink() || this.length() <= this.maxChars),
  );

  protected onInput(event: Event): void {
    this.posting.set((event.target as HTMLTextAreaElement).value);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    const text = this.posting().trim();
    if (!text) return;
    if (URL_PATTERN.test(text)) {
      this.analyses.createFromUrl(text);
    } else {
      if (text.length > this.maxChars) return;
      this.analyses.create(text);
    }
    this.posting.set('');
  }
}

/** A single http(s) URL on its own line is treated as a posting link. */
const URL_PATTERN = /^https?:\/\/\S+$/i;
