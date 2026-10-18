import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';

import { GapsStore } from '../../core/gaps.store';
import { GapItem, GapKind, GapStatus, MAX_GAP_NOTE_CHARS, MAX_GAP_TEXT_CHARS } from '../../core/models';

/**
 * Skill gaps (docs/API.md §9): what the CV is missing and the nice-to-haves aggregated across every
 * analysed posting, plus the user's own entries. Basic CRUD: add a gap, rename/re-classify it, set a
 * status and a note, remove it. Opening the view (re)loads the aggregation from the backend.
 */
@Component({
  selector: 'app-skill-gaps',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './skill-gaps.html',
})
export class SkillGaps {
  protected readonly gaps = inject(GapsStore);

  readonly closed = output<void>();

  protected readonly maxText = MAX_GAP_TEXT_CHARS;
  protected readonly maxNote = MAX_GAP_NOTE_CHARS;
  protected readonly KIND_LABEL = KIND_LABEL;
  protected readonly STATUS_LABEL = STATUS_LABEL;
  protected readonly STATUSES: GapStatus[] = ['OPEN', 'PLANNED', 'LEARNED'];

  protected readonly filter = signal<GapKind | 'ALL'>('ALL');
  protected readonly newText = signal('');
  protected readonly newKind = signal<GapKind>('MISSING');
  private readonly _collapsed = signal<ReadonlySet<GapStatus>>(new Set());

  protected readonly visible = computed(() => {
    const active = this.filter();
    const items = this.gaps.items();
    return active === 'ALL' ? items : items.filter((gap) => gap.kind === active);
  });

  /**
   * The visible gaps grouped by the user's workflow status (Open → Planned → Learned), so a long
   * backlog stays scannable; empty groups are dropped. Within a group, missing terms come first and
   * the ones raised by the most analyses rise to the top.
   */
  protected readonly groups = computed<Group[]>(() => {
    const items = [...this.visible()];
    return (['OPEN', 'PLANNED', 'LEARNED'] as GapStatus[])
      .map((status) => ({
        status,
        label: STATUS_LABEL[status],
        items: items
          .filter((gap) => gap.status === status)
          .sort(
            (a, b) =>
              (a.kind === b.kind ? 0 : a.kind === 'MISSING' ? -1 : 1) ||
              b.occurrences - a.occurrences ||
              a.text.localeCompare(b.text, undefined, { sensitivity: 'base' }),
          ),
      }))
      .filter((group) => group.items.length > 0);
  });

  protected readonly filters = computed<{ value: GapKind | 'ALL'; label: string }[]>(() => {
    const counts = this.gaps.counts();
    return [
      { value: 'ALL', label: `All (${counts.ALL})` },
      { value: 'MISSING', label: `Missing (${counts.MISSING})` },
      { value: 'NICE', label: `Nice to have (${counts.NICE})` },
    ];
  });

  constructor() {
    this.gaps.load();
  }

  protected isCollapsed(status: GapStatus): boolean {
    return this._collapsed().has(status);
  }

  protected toggleGroup(status: GapStatus): void {
    this._collapsed.update((current) => {
      const next = new Set(current);
      if (next.has(status)) next.delete(status);
      else next.add(status);
      return next;
    });
  }

  protected setFilter(value: GapKind | 'ALL'): void {
    this.filter.set(value);
  }

  /** Creates the gap typed into the add form; ignores an empty value. */
  protected add(): void {
    const text = this.newText().trim();
    if (!text) return;
    this.gaps.create(text, this.newKind(), '');
    this.newText.set('');
  }

  protected rename(gap: GapItem, text: string): void {
    const trimmed = text.trim();
    if (!trimmed || trimmed === gap.text) return;
    this.gaps.update(gap.id, { text: trimmed });
  }

  protected setKind(gap: GapItem, kind: string): void {
    if (kind === gap.kind) return;
    this.gaps.update(gap.id, { kind: kind as GapKind });
  }

  protected setStatus(gap: GapItem, status: string): void {
    if (status === gap.status) return;
    this.gaps.update(gap.id, { status: status as GapStatus });
  }

  protected saveNote(gap: GapItem, note: string): void {
    const trimmed = note.trim();
    if (trimmed === gap.note) return;
    this.gaps.update(gap.id, { note: trimmed });
  }

  protected remove(gap: GapItem): void {
    this.gaps.remove(gap.id);
  }

  protected sourcesTitle(gap: GapItem): string {
    return gap.sources.map((source) => source.title).join('\n');
  }
}

const KIND_LABEL: Record<GapKind, string> = {
  MISSING: 'Missing',
  NICE: 'Nice to have',
};

const STATUS_LABEL: Record<GapStatus, string> = {
  OPEN: 'Open',
  PLANNED: 'Planned',
  LEARNED: 'Learned',
};

/** One status section of the grouped list. */
interface Group {
  status: GapStatus;
  label: string;
  items: GapItem[];
}
