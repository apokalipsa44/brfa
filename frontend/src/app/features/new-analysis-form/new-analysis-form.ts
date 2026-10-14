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
  template: `
    <form class="mx-auto max-w-2xl" (submit)="onSubmit($event)">
      <h2 class="text-lg font-semibold text-slate-900">New analysis</h2>
      <p class="mt-1 text-sm text-slate-500">
        Paste a job posting. Your CV is matched against it and the changes are suggested before anything
        is rewritten.
      </p>

      <label for="posting" class="mt-5 block text-sm font-medium text-slate-700">Job posting</label>
      <textarea
        id="posting"
        rows="12"
        class="mt-1.5 w-full resize-y rounded-xl border border-slate-300 bg-white p-3 text-sm text-slate-900 placeholder:text-slate-400 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
        placeholder="Paste the job posting text here…"
        [value]="posting()"
        (input)="onInput($event)"
      ></textarea>

      <div class="mt-1.5 flex items-center justify-between gap-3">
        <p class="text-sm" [class]="hintClasses()">
          @if (hint(); as message) {
            {{ message }}
          }
        </p>
        <p class="shrink-0 text-xs text-slate-400 tabular-nums" aria-live="polite">
          {{ length() }} / {{ maxChars }} characters
        </p>
      </div>

      @if (analyses.actionError(); as message) {
        <p role="alert" class="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{{ message }}</p>
      }

      <button
        type="submit"
        class="mt-4 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors duration-200 hover:bg-indigo-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
        [disabled]="analyses.creating() || !canSubmit()"
      >
        {{ analyses.creating() ? 'Starting…' : 'Analyze' }}
      </button>
    </form>
  `,
})
export class NewAnalysisForm {
  protected readonly analyses = inject(AnalysesStore);
  protected readonly cv = inject(CvContextStore);

  protected readonly maxChars = MAX_JOB_POSTING_CHARS;

  private readonly posting = signal('');
  private readonly length = signal(0);

  /** Single source for the disabled state and the one-line helper text. */
  protected readonly hint = computed<string | null>(() => {
    if (!this.cv.hasCv()) return 'Upload a CV to continue.';
    if (!this.cv.ready()) return this.cv.reason() ?? 'Load a model in LM Studio to continue.';
    if (this.length() === 0) return 'Paste a job posting to continue.';
    if (this.length() > this.maxChars) return `The posting is over ${this.maxChars} characters.`;
    return null;
  });

  protected readonly canSubmit = computed(
    () => this.cv.hasCv() && this.cv.ready() && this.length() > 0 && this.length() <= this.maxChars,
  );

  protected readonly hintClasses = computed(() =>
    this.hint() === null ? 'text-slate-400' : 'text-slate-500',
  );

  protected onInput(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    this.posting.set(value);
    this.length.set(value.length);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    const text = this.posting().trim();
    if (!text || text.length > this.maxChars) return;
    this.analyses.create(text);
    this.posting.set('');
    this.length.set(0);
  }
}
