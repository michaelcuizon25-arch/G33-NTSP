"""Unit tests use synthetic records only; they are not Android recognition results."""
import csv
import io
import json
from pathlib import Path
import tempfile
import tarfile
import unittest
from unittest.mock import patch
import evaluate_ocr as evaluation


class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def records(self):
        rows = [dict(crnn=dict(text='cat', inferenceNs=2_000_000), paddle=dict(text='cat', inferenceIncludingColdInitNs=9_000_000)),
                dict(crnn=dict(text=''), paddle=dict(text='phantom', inferenceIncludingColdInitNs=3_000_000)),
                dict(crnn=dict(text='dog'), paddleError={'type': 'UnsupportedDimension'})]
        (self.root / 'scan.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows))
        return evaluation.read_records(self.root)

    def labels(self, records):
        path = self.root / 'labels.tsv'
        evaluation.template(records, path)
        with path.open(newline='') as stream:
            rows = list(csv.DictReader(stream, dialect='excel-tab'))
        for i, row in enumerate(rows):
            row.update(source='gallery', category='blank' if i == 1 else 'clear', transcribed='yes', ground_truth=['cat', '', 'dog'][i])
            row['paddle_prediction'] = 'edited/spoofed prediction must never be scored'
        with path.open('w', newline='') as stream:
            writer = csv.DictWriter(stream, evaluation.FIELDS, dialect='excel-tab'); writer.writeheader(); writer.writerows(rows)
        return path

    def test_hand_computed_distance_and_unicode(self):
        self.assertEqual(3, evaluation.distance('kitten', 'sitting'))
        self.assertEqual(1, evaluation.distance('𝜓', 'ψ'))
        self.assertEqual(1, evaluation.distance(['red', 'cat'], ['cat']))

    def test_blank_denominators_and_insertions_are_explicit(self):
        only_blank = evaluation.accuracy([('', 'ghost'), ('', '')])
        self.assertIsNone(only_blank['CER_including_blank_insertions'])
        self.assertEqual(1, only_blank['blank_false_positives'])
        self.assertEqual(.5, only_blank['blank_false_positive_rate'])
        mixed = evaluation.accuracy([('a', 'a'), ('', 'b')])
        self.assertEqual(1., mixed['CER_including_blank_insertions'])
        self.assertEqual(0., mixed['CER_nonblank'])

    def test_unlabeled_empty_truth_is_distinct_from_transcribed_blank(self):
        records = self.records(); labels = self.root / 'unlabeled.tsv'
        evaluation.template(records, labels)
        self.assertEqual({}, evaluation.read_labels(labels, records))
        parsed = evaluation.read_labels(self.labels(records), records)
        self.assertEqual('', parsed['scan:0002']['ground_truth'])

    def test_pairing_excludes_failures_and_uses_original_records(self):
        records = self.records(); report = evaluation.score(records, evaluation.read_labels(self.labels(records), records))
        self.assertEqual(2, report['paired_samples'])
        self.assertEqual(1, report['paddle_failures'])
        self.assertEqual(3, report['crnn_all_labeled']['samples'])
        self.assertEqual(1, report['paired_accuracy']['paddle']['blank_false_positives'])
        self.assertEqual(1, report['paired_accuracy']['paddle']['exact_matches'])
        self.assertEqual(9., report['timings_all_records']['paddle/first_success_may_include_cold_init']['median_ms'])
        self.assertEqual(3., report['timings_all_records']['paddle/subsequent_success']['median_ms'])

    def test_duplicate_or_unknown_labels_and_implicit_empty_truth_rejected(self):
        records = self.records(); path = self.labels(records)
        text = path.read_text()
        path.write_text(text + text.splitlines()[1] + '\n')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            evaluation.read_labels(path, records)
        path.write_text(text.replace('scan:0001', 'missing:0001'))
        with self.assertRaisesRegex(ValueError, 'Unknown'):
            evaluation.read_labels(path, records)
        path.write_text(text.replace('\tblank\t', '\tclear\t'))
        with self.assertRaisesRegex(ValueError, 'empty reference'):
            evaluation.read_labels(path, records)

    def test_mixed_model_versions_cannot_be_silently_pooled(self):
        records = self.records()
        records['scan:0001']['paddleModelSha256'] = 'model-one'
        records['scan:0002']['paddleModelSha256'] = 'model-two'
        with self.assertRaisesRegex(ValueError, 'Mixed Paddle'):
            evaluation.score(records, {})

    def test_template_never_overwrites_manual_labels(self):
        records = self.records(); path = self.labels(records)
        with self.assertRaises(FileExistsError):
            evaluation.template(records, path)

    def test_perf_keeps_stage_totals_separate_from_line_events(self):
        events = [dict(kind='LINE_INFERENCE', flow='SINGLE_CAMERA', session=1, stage='CRNN_INFERENCE', elapsedNs=2_000_000, count=1),
                  dict(kind='STAGE', flow='SINGLE_CAMERA', session=1, stage='CRNN_INFERENCE', elapsedNs=6_000_000, count=2, mainThreadStarts=0)]
        path = self.root / 'perf.log'
        path.write_text('unrelated log\n' + '\n'.join('I/NOTE2SNAP_PERF: ' + json.dumps(x) for x in events))
        report = evaluation.perf_report(path)
        self.assertEqual(2, len(report['groups']))
        stage = next(x for x in report['groups'] if x['kind'] == 'STAGE')
        self.assertEqual(3., stage['mean_per_call_ms'])
        self.assertEqual(0, stage['main_thread_starts'])

    def test_invalid_timing_and_partial_record_rejected(self):
        for value in [-1, float('nan'), float('inf'), True]:
            with self.assertRaises(ValueError): evaluation.timing_ns(value)
        (self.root / 'scan.jsonl').write_text('{"crnn":')
        with self.assertRaises(json.JSONDecodeError): evaluation.read_records(self.root)

    def archive(self, name='files/paddle-comparisons/scan.jsonl', payload=b'{"raw":true}\n'):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            entry = tarfile.TarInfo(name); entry.size = len(payload); archive.addfile(entry, io.BytesIO(payload))
        return data.getvalue()

    @patch('evaluate_ocr.subprocess.run')
    def test_collect_reads_without_deleting_and_refuses_changed_snapshot(self, run):
        run.return_value.stdout = self.archive()
        output = self.root / 'collected'
        self.assertEqual(1, evaluation.collect(output))
        command = run.call_args.args[0]
        self.assertIn('run-as', command); self.assertNotIn('rm', command)
        (output / 'scan.jsonl').write_text('manually preserved snapshot')
        with self.assertRaisesRegex(ValueError, 'differs'): evaluation.collect(output)
        self.assertEqual('manually preserved snapshot', (output / 'scan.jsonl').read_text())

    @patch('evaluate_ocr.subprocess.run')
    def test_baseline_records_are_excluded_without_silent_partial_scan_updates(self, run):
        baseline = self.root / 'baseline'; baseline.mkdir()
        (baseline / 'scan.jsonl').write_bytes(b'{"raw":true}\n')
        run.return_value.stdout = self.archive()
        with self.assertRaisesRegex(ValueError, 'No record files'):
            evaluation.collect(self.root / 'new', baseline=baseline)
        (baseline / 'scan.jsonl').write_bytes(b'older partial file')
        with self.assertRaisesRegex(ValueError, 'Baseline record changed'):
            evaluation.collect(self.root / 'new', baseline=baseline)

    @patch('evaluate_ocr.subprocess.run')
    def test_collect_rejects_archive_path_traversal(self, run):
        for name in ['../../escape.jsonl', 'files/paddle-comparisons/..\\..\\escape.jsonl']:
            run.return_value.stdout = self.archive(name)
            with self.assertRaisesRegex(ValueError, 'Unexpected'): evaluation.collect(self.root / 'out')


if __name__ == '__main__':
    unittest.main()
