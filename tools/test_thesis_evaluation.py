"""Synthetic metric/provenance tests, not measured device OCR results."""
import csv
import json
from pathlib import Path
import tempfile
import unittest
import evaluate_ocr as evaluation


class ThesisEvaluationTest(unittest.TestCase):
    def test_independent_engine_failure_is_not_a_blank_prediction(self):
        records={'a:0001':{'crnn':{'text':''},'crnnError':'failure','paddle':{'text':'word'}}}
        labels={'a:0001':{'ground_truth':'word','source':'gallery','category':'clear','segmentation_status':'correct'}}
        report=evaluation.score(records,labels)
        self.assertEqual(0,report['paired_samples'])
        self.assertEqual(1,report['crnn_failures'])
        self.assertEqual(0,report['paddle_all_labeled']['CER_nonblank'])
        self.assertEqual(0,report['crnn_all_labeled']['samples'])

    def test_bad_segmentation_separate_from_correctly_segmented_scores(self):
        records={k:{'crnn':{'text':p},'paddle':{'text':p}} for k,p in [('a:0001','word'),('a:0002','I')]}
        labels={k:{'ground_truth':g,'source':'gallery','category':'clear','segmentation_status':status}
                for k,g,status in [('a:0001','word','correct'),('a:0002','','extra')]}
        report=evaluation.score(records,labels)
        self.assertEqual(1,report['segmentation_status_counts']['extra'])
        self.assertEqual(0,report['correctly_segmented_paired_accuracy']['paddle']['CER_nonblank'])
        self.assertGreater(report['paired_accuracy']['paddle']['CER_including_blank_insertions'],0)

    def test_missing_crop_provenance_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d,'scan.jsonl').write_text(json.dumps({'crnn':{'text':'a'},'paddle':{'text':'a'},'cropFile':'scan-assets/absent.png'})+'\n')
            with self.assertRaisesRegex(ValueError,'provenance'):evaluation.read_records(d)

    def test_document_scores_keep_extra_lines_and_note_layer_separate(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d,'documents.tsv')
            fields=['scan_id','layer','transcribed','ground_truth','crnn_text','paddle_text']
            with p.open('w',newline='') as f:
                w=csv.DictWriter(f,fields,dialect='excel-tab');w.writeheader()
                for layer,pred in [('raw_ocr','a I'),('final_note','a')]:
                    w.writerow(dict(scan_id='one',layer=layer,transcribed='yes',ground_truth='a',crnn_text=pred,paddle_text=pred))
            report=evaluation.document_scores(p)
            self.assertGreater(report['layers']['raw_ocr']['paddle']['CER_nonblank'],0)
            self.assertEqual(0,report['layers']['final_note']['paddle']['CER_nonblank'])

    def test_html_escapes_raw_ocr(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d,'report.html')
            evaluation.html_report({'a:0001':{'crnn':{'text':'<script>'},'paddle':{'text':'x'}}},{},p)
            self.assertIn('&lt;script&gt;',p.read_text())
            self.assertNotIn('<script>',p.read_text())

if __name__=='__main__':unittest.main()
