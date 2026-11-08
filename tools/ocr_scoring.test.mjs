import test from 'node:test';
import assert from 'node:assert/strict';
import { normalize, distance, errors } from './ocr_scoring.mjs';

test('exact lowercase uppercase mixed technical and formula strings have no edits', () => {
  for (const text of ['the quick fox', 'THE QUICK FOX', 'The Quick fox',
    'CoroutineScope SQL HTTP', 'F = ma; x² + y² = r²; O(n log n)', 'I l 1']) {
    assert.equal(errors(text, text).characterEdits, 0);
    assert.equal(errors(text, text).wordEdits, 0);
  }
});
test('normalizes whitespace while preserving case and punctuation', () => {
  assert.equal(normalize('  he k\t os\n'), 'he k os');
  assert.equal(errors('Dog.', 'dog').characterEdits, 2);
  assert.equal(errors('Dog.', 'dog').wordEdits, 1);
});
test('uses Unicode code points instead of UTF-16 code units', () => {
  assert.deepEqual(errors('𐐀²', ''), {
    characterEdits: 2, referenceCharacters: 2, wordEdits: 1, referenceWords: 1
  });
});
test('handles empty references and hypotheses without inventing denominators', () => {
  assert.deepEqual(errors('', 'abc'), {
    characterEdits: 3, referenceCharacters: 0, wordEdits: 1, referenceWords: 0
  });
  assert.deepEqual(errors('', ''), {
    characterEdits: 0, referenceCharacters: 0, wordEdits: 0, referenceWords: 0
  });
});
test('Levenshtein counts insertion deletion and substitution', () => {
  assert.equal(distance(Array.from('kitten'), Array.from('sitting')), 3);
  assert.equal(distance([], Array.from('abc')), 3);
  assert.equal(distance(Array.from('abc'), []), 3);
});
test('returns raw edit counts suitable for micro aggregation', () => {
  assert.deepEqual(errors('one two three', 'one three'), {
    characterEdits: 4, referenceCharacters: 13, wordEdits: 1, referenceWords: 3
  });
});
