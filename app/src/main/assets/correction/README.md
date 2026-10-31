# Bundled correction resources

These small vocabulary and alias lists were authored specifically for Note2Snap.
They are not copied from an external dictionary and need no runtime download.
No word frequencies or probabilistic confidence values are asserted.

`academic_vocabulary.txt` includes academic/CS terms and common valid words,
including confusable valid words such as form/from and modem/modern. Exact known
words are never corrected. This is deliberately not a complete English dictionary.

`reviewed_ocr_aliases.tsv` is an explicit nonword-to-word allowlist. A known OCR
confusion or common misspelling can be applied only if its target is in the
vocabulary. Lowercase aliases may be applied; title-case words are suggestion-only. All-caps,
mixed-case, numeric, formula and identifier tokens are protected. There is still
no proof that an allowlisted nonword cannot be someone's name or a valid domain
term. Keep the feature off until representative evaluation supports enabling it.

Generic edit-distance neighbours are suggestions only, even when unique. Do not
add valid-word substitutions or sentence/word completions to this list. New aliases
should have preservation tests and independent held-out recognition examples.
