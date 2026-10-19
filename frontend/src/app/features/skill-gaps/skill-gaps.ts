import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CvContextStore } from '../../core/cv-context.store';
import { CvImprovementStore } from '../../core/cv-improvement.store';
import { GapsStore } from '../../core/gaps.store';
import { formatDateTime } from '../../core/format';
import {
  GapItem,
  GapKind,
  GapStatus,
  GroupedSkill,
  MAX_GAP_NOTE_CHARS,
  MAX_GAP_TEXT_CHARS,
} from '../../core/models';
import { InlineTitleEdit } from '../../shared/inline-title-edit/inline-title-edit';

/**
 * Skill gaps (docs/API.md §9): what the CV is missing and the nice-to-haves aggregated across every
 * analysed posting, plus the user's own entries. Two views: the grouped + ranked skills the background
 * job folds and scores (the default), and the curated list (add / rename / classify / annotate / remove
 * a gap). The grouped view also lets the user tick skills and build a standalone improved CV (§10).
 */
@Component({
  selector: 'app-skill-gaps',
  imports: [InlineTitleEdit],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './skill-gaps.html',
})
export class SkillGaps {
  protected readonly gaps = inject(GapsStore);
  protected readonly improvement = inject(CvImprovementStore);
  protected readonly cv = inject(CvContextStore);

  protected readonly maxText = MAX_GAP_TEXT_CHARS;
  protected readonly maxNote = MAX_GAP_NOTE_CHARS;
  protected readonly KIND_LABEL = KIND_LABEL;
  protected readonly STATUS_LABEL = STATUS_LABEL;
  protected readonly STATUSES: GapStatus[] = ['OPEN', 'PLANNED', 'LEARNED'];
  protected readonly formatDateTime = formatDateTime;

  protected readonly mode = signal<'list' | 'grouped'>('grouped');
  protected readonly filter = signal<GapKind | 'ALL'>('ALL');
  protected readonly newText = signal('');
  protected readonly newKind = signal<GapKind>('MISSING');
  private readonly _collapsed = signal<ReadonlySet<GapStatus>>(new Set());

  /** Ids of the grouped skills ticked for the next CV build. */
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly selectedCount = computed(() => this.selected().size);

  /** The grouped skill whose label is being renamed inline, if any. */
  protected readonly editingClusterId = signal<string | null>(null);

  /** Label for the build button: the running stage, or the pending selection count. */
  protected readonly buildLabel = computed(() => {
    const stage = this.improvement.state()?.stage;
    if (stage === 'REWRITING') return 'Rewriting the CV…';
    if (stage === 'RENDERING') return 'Rendering the PDF…';
    const count = this.selectedCount();
    return count === 0 ? 'Build improved CV' : `Build improved CV (${count})`;
  });

  /** True when a build could start: a CV and a ready model exist and a skill is ticked (§10). */
  protected readonly canBuild = computed(
    () => this.cv.hasCv() && this.cv.ready() && this.selectedCount() > 0,
  );

  /** One-line reason why the build button is disabled (mirrors the analysis form). */
  protected readonly buildHint = computed<string | null>(() => {
    if (!this.cv.hasCv()) return 'Upload a CV to continue.';
    if (!this.cv.ready()) return this.cv.reason() ?? 'Load a model in LM Studio to continue.';
    return null;
  });

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
    this.gaps.loadClusters(); // grouped is the default view
    this.improvement.load();
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

  /** Switches between the grouped overview (default, loaded up front) and the curated list. */
  protected setMode(mode: 'list' | 'grouped'): void {
    this.mode.set(mode);
  }

  /** Regroups + ranks the gaps now (synchronous on the backend; the button shows a spinner). */
  protected rebuild(): void {
    this.selected.set(new Set()); // ids change after a regroup
    this.gaps.rebuildClusters();
  }

  protected isSelected(id: string): boolean {
    return this.selected().has(id);
  }

  protected toggleSelected(id: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  /** Builds a standalone improved CV from the active CV plus the ticked grouped skills (§10). */
  protected buildCv(): void {
    if (!this.canBuild()) return;
    const labels = this.gaps
      .clusters()
      .filter((cluster) => this.selected().has(cluster.id))
      .map((cluster) => cluster.label);
    if (labels.length === 0) return;
    this.improvement.start(labels);
  }

  /** Opens the inline label editor for one grouped skill. */
  protected startRenameCluster(id: string): void {
    this.editingClusterId.set(id);
  }

  protected cancelRenameCluster(): void {
    this.editingClusterId.set(null);
  }

  /** Saves a renamed grouped-skill label (no-op when unchanged). */
  protected saveClusterName(cluster: GroupedSkill, label: string): void {
    this.editingClusterId.set(null);
    if (label !== cluster.label) this.gaps.renameCluster(cluster.id, label);
  }

  /** Removes a grouped skill (and its gaps); drops it from the pending CV selection too. */
  protected removeCluster(cluster: GroupedSkill): void {
    this.selected.update((current) => {
      if (!current.has(cluster.id)) return current;
      const next = new Set(current);
      next.delete(cluster.id);
      return next;
    });
    this.gaps.removeCluster(cluster.id);
  }

  /** 1-5 importance as filled/empty stars for the grouped rows. */
  protected stars(importance: number): string {
    const filled = Math.max(0, Math.min(5, Math.round(importance)));
    return '★'.repeat(filled) + '☆'.repeat(5 - filled);
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
