import { describe, expect, it } from 'vitest';

import { modelRating, modelStars } from './model-rating';

describe('modelRating', () => {
  it('rates MoE models with few active parameters highest', () => {
    expect(modelRating('qwen3-coder-30b-a3b-compacted-19b-256k')).toBe(3);
    expect(modelRating('google/gemma-4-26b-a4b')).toBe(3);
    expect(modelRating('mellum2.1-12b-a2.5b-thinking')).toBe(3);
  });

  it('treats other chat models as a usable default', () => {
    expect(modelRating('google/gemma-4-e4b')).toBe(2);
    expect(modelRating('google/gemma-4-12b')).toBe(2);
  });

  it('demotes forced-reasoning and very low-bit quantizations', () => {
    expect(modelRating('mistralai/ministral-3-14b-reasoning')).toBe(1);
    expect(modelRating('prism-ml/bonsai-27b-q1_0')).toBe(1);
  });

  it('marks embedding models as unusable for chat', () => {
    expect(modelRating('text-embedding-nomic-embed-text-v1.5')).toBe(0);
  });
});

describe('modelStars', () => {
  it('renders a filled 3-star bar', () => {
    expect(modelStars('qwen3-coder-30b-a3b-compacted-19b-256k')).toBe('★★★');
    expect(modelStars('google/gemma-4-e4b')).toBe('★★☆');
    expect(modelStars('mistralai/ministral-3-14b-reasoning')).toBe('★☆☆');
    expect(modelStars('text-embedding-nomic-embed-text-v1.5')).toBe('—');
  });
});
