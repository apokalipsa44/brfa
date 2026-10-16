/**
 * Rough "expected fit" rating (0–3) for the two LLM steps, derived from the model id alone — LM
 * Studio's deeper metadata (quantization, active params) is not part of the contract. This is a
 * heuristic, not a benchmark (docs/DECISIONS.md): Mixture-of-Experts models with few active
 * parameters win on throughput, models that cannot turn thinking off and very low-bit quantizations
 * lose, and embedding models cannot run the chat steps at all.
 */
export function modelRating(id: string): number {
  const n = id.toLowerCase();
  if (n.includes('embed')) return 0;
  if (n.includes('reasoning')) return 1; // thinking that cannot be disabled -> slow (e.g. ministral-*-reasoning)
  if (n.includes('iq1') || n.includes('iq2') || n.includes('q1_0') || n.includes('q2_k')) return 1;
  const active = n.match(/a(\d+(?:\.\d+)?)b(?![a-z0-9])/);
  if (active && Number.parseFloat(active[1]) <= 4.5) return 3; // MoE, ~<=4B active: fast and capable
  return 2; // everything else: a usable default
}

/** The rating as a 3-star bar ("★★☆"); a 0 rating (embeddings) renders as an em dash. */
export function modelStars(id: string): string {
  const rating = modelRating(id);
  if (rating === 0) return '—';
  return '★'.repeat(rating) + '☆'.repeat(3 - rating);
}
