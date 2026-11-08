/**
 * Pure OCR evaluation metrics. No file access, model inference, or engine selection.
 * Whitespace is normalized; case and punctuation remain significant.
 * CER uses Unicode code points; WER uses whitespace-separated tokens.
 */
export const normalize = text => text.trim().replace(/\s+/gu, ' ');
export function distance(reference, hypothesis) {
  let row = Array.from({length: hypothesis.length + 1}, (_, i) => i);
  reference.forEach((r, i) => {
    const next = [i + 1];
    hypothesis.forEach((h, j) => {
      next.push(Math.min(next[j] + 1, row[j + 1] + 1, row[j] + (r === h ? 0 : 1)));
    });
    row = next;
  });
  return row[hypothesis.length];
}
export function errors(reference, hypothesis) {
  const r = normalize(reference), h = normalize(hypothesis);
  return {
    characterEdits: distance(Array.from(r), Array.from(h)), referenceCharacters: Array.from(r).length,
    wordEdits: distance(r ? r.split(' ') : [], h ? h.split(' ') : []), referenceWords: r ? r.split(' ').length : 0
  };
}
