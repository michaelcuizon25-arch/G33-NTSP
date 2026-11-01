"""Offline Stage 3C collection, manual-label templates, paired CER/WER and PERF summaries.
Python 3 standard library only. Never installs an APK or changes recognition settings.
"""
import argparse
import csv
import io
import json
import math
import re
import statistics
import subprocess
import tarfile
from collections import defaultdict
from pathlib import Path, PurePosixPath

FIELDS = ['sample_id', 'source', 'category', 'transcribed', 'ground_truth', 'original_text',
          'region_id', 'line_box', 'crop_width', 'crop_height', 'case_name',
          'crnn_prediction', 'paddle_prediction', 'paddle_error', 'crnn_preprocessing_ms', 'crnn_inference_ms',
          'crnn_decoding_ms', 'paddle_preprocessing_ms', 'paddle_inference_including_cold_ms', 'paddle_decoding_ms', 'notes']
CATEGORIES = ['clear', 'blank', 'faint', 'cursive', 'numbers', 'formulas', 'computer_science', 'diagram', 'other']


def distance(reference, prediction):
    row = list(range(len(prediction) + 1))
    for i, a in enumerate(reference, 1):
        following = [i]
        for j, b in enumerate(prediction, 1):
            following.append(min(following[-1] + 1, row[j] + 1, row[j - 1] + (a != b)))
        row = following
    return row[-1]


def read_records(directory):
    records = {}
    for file in sorted(Path(directory).glob('*.jsonl')):
        for number, line in enumerate(file.read_text(encoding='utf-8').splitlines(), 1):
            if not line.strip():
                raise ValueError(f'{file.name}:{number}: empty/truncated record')
            record = json.loads(line)
            if not isinstance(record.get('crnn', {}).get('text'), str):
                raise ValueError(f'{file.name}:{number}: missing CRNN text')
            if 'paddle' in record and not isinstance(record['paddle'].get('text'), str):
                raise ValueError(f'{file.name}:{number}: malformed Paddle prediction')
            if 'paddle' not in record and 'paddleError' not in record:
                raise ValueError(f'{file.name}:{number}: missing Paddle result/error')
            sample_id = f'{file.stem}:{number:04d}'
            records[sample_id] = record
    if not records:
        raise ValueError('No comparison JSONL records found; a rejected/blank scan can legitimately produce none')
    return records


def template(records, output):
    with Path(output).open('x', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, FIELDS, dialect='excel-tab')
        writer.writeheader()
        for sample_id, record in records.items():
            def ms(engine, field):
                value = record.get(engine, {}).get(field)
                return timing_ns(value) if value is not None else ''
            writer.writerow(dict(sample_id=sample_id, source=record.get('source', ''),
                category=record.get('category', ''), transcribed='no', ground_truth='', original_text='',
                region_id=record.get('regionId', ''), line_box=json.dumps(record.get('box', [])),
                crop_width=record.get('imageWidth', ''), crop_height=record.get('imageHeight', ''), case_name=record.get('caseName', ''),
                crnn_preprocessing_ms=ms('crnn', 'preprocessingNs'), crnn_inference_ms=ms('crnn', 'inferenceNs'),
                crnn_decoding_ms=ms('crnn', 'decodingNs'), paddle_preprocessing_ms=ms('paddle', 'preprocessingNs'),
                paddle_inference_including_cold_ms=ms('paddle', 'inferenceIncludingColdInitNs'), paddle_decoding_ms=ms('paddle', 'decodingNs'),
                crnn_prediction=record['crnn']['text'], paddle_prediction=record.get('paddle', {}).get('text', ''),
                paddle_error=json.dumps(record['paddleError'], ensure_ascii=False) if 'paddleError' in record else '', notes=record.get('caseName', '')))


def read_labels(path, records):
    labels = {}
    seen = set()
    with Path(path).open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream, dialect='excel-tab')
        if not set(['sample_id', 'transcribed', 'ground_truth', 'source', 'category']).issubset(reader.fieldnames or []):
            raise ValueError('Missing required label columns')
        for row in reader:
            sample_id = row['sample_id']
            if sample_id not in records or sample_id in seen:
                raise ValueError(f'Unknown or duplicate sample_id: {sample_id}')
            seen.add(sample_id)
            if row['transcribed'] not in ('yes', 'no'):
                raise ValueError(f'{sample_id}: transcribed must be yes or no')
            if row['transcribed'] == 'yes':
                if not isinstance(row['ground_truth'], str):
                    raise ValueError(f'{sample_id}: missing ground_truth field')
                if not row['source'] or row['category'] not in CATEGORIES:
                    raise ValueError(f'{sample_id}: supply source and a documented category')
                if row['ground_truth'] == '' and row['category'] not in ('blank', 'diagram'):
                    raise ValueError(f'{sample_id}: empty reference requires blank or diagram category')
                labels[sample_id] = row
    return labels


def accuracy(pairs):
    ce = we = chars = words = exact = blanks = false_positive = nonblank_ce = nonblank_we = 0
    for truth, prediction in pairs:
        c, w = distance(truth, prediction), distance(truth.split(), prediction.split())
        ce += c; we += w; chars += len(truth); words += len(truth.split()); exact += truth == prediction
        if not truth:
            blanks += 1; false_positive += prediction != ''
        else:
            nonblank_ce += c; nonblank_we += w
    return dict(samples=len(pairs), exact_matches=exact, reference_characters=chars, reference_words=words,
        character_errors=ce, word_errors=we, CER_including_blank_insertions=ce / chars if chars else None,
        WER_including_blank_insertions=we / words if words else None,
        CER_nonblank=nonblank_ce / chars if chars else None, WER_nonblank=nonblank_we / words if words else None,
        blank_samples=blanks, blank_false_positives=false_positive,
        blank_false_positive_rate=false_positive / blanks if blanks else None)


def summary(values):
    if not values:
        return dict(count=0, median_ms=None, p95_ms=None)
    values = sorted(values)
    rank = .95 * (len(values) - 1)
    lo = int(rank); hi = min(lo + 1, len(values) - 1)
    p95 = values[lo] + (rank - lo) * (values[hi] - values[lo])
    return dict(count=len(values), median_ms=statistics.median(values), p95_ms=p95,
                min_ms=values[0], max_ms=values[-1])


def timing_ns(value):
    if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value < 0:
        raise ValueError('Timings must be finite nonnegative nanoseconds')
    return value / 1_000_000


def score(records, labels):
    hashes = sorted({r['paddleModelSha256'] for r in records.values() if isinstance(r.get('paddleModelSha256'), str)})
    if len(hashes) > 1:
        raise ValueError('Mixed Paddle model hashes; score each model/run separately')
    paired = {k: v for k, v in labels.items() if 'paddle' in records[k]}
    def engines(selected):
        return {engine: accuracy([(label['ground_truth'], records[key][engine]['text'])
                                 for key, label in selected.items()]) for engine in ['crnn', 'paddle']}
    groups = defaultdict(dict)
    for key, label in paired.items():
        groups[f"{label['source']}/{label['category']}"][key] = label
    line_results = []
    for key, label in labels.items():
        record = records[key]
        row = dict(sample_id=key, source=label['source'], category=label['category'], ground_truth=label['ground_truth'])
        for engine in ['crnn', 'paddle']:
            if engine in record:
                text = record[engine]['text']; truth = label['ground_truth']
                row[engine] = dict(prediction=text, character_errors=distance(truth, text),
                                   word_errors=distance(truth.split(), text.split()))
        if 'paddleError' in record:
            row['paddle_error'] = record['paddleError']
        line_results.append(row)
    times = defaultdict(list); seen = set()
    for key, record in records.items():
        scan = key.rsplit(':', 1)[0]
        for field in ['preprocessingNs', 'inferenceNs', 'decodingNs']:
            if field in record['crnn']:
                times['crnn/' + field].append(timing_ns(record['crnn'][field]))
        if 'paddle' in record:
            for field in ['preprocessingNs', 'decodingNs']:
                if field in record['paddle']:
                    times['paddle/' + field].append(timing_ns(record['paddle'][field]))
            field = 'inferenceIncludingColdInitNs'
            if field in record['paddle']:
                group = 'first_success_may_include_cold_init' if scan not in seen else 'subsequent_success'
                times['paddle/' + group].append(timing_ns(record['paddle'][field]))
            seen.add(scan)
    return dict(scope='User-collected records only; no device identity or successful offline operation inferred',
        normalization='Exact Unicode codepoints, case and whitespace for CER; whitespace tokenization for WER',
        records=len(records), paddle_model_hashes=hashes, manually_labeled=len(labels), paired_samples=len(paired),
        paddle_failures=sum('paddle' not in r for r in records.values()),
        paired_accuracy=engines(paired),
        crnn_all_labeled=accuracy([(v['ground_truth'], records[k]['crnn']['text']) for k, v in labels.items()]),
        by_source_category={name: engines(group) for name, group in sorted(groups.items())},
        timings_all_records={name: summary(values) for name, values in times.items()}, lines=line_results)


def perf_report(path):
    groups = defaultdict(list)
    count = 0
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        marker = line.find('{')
        if marker < 0:
            continue
        try:
            event = json.loads(line[marker:])
        except json.JSONDecodeError:
            continue
        if not {'kind', 'flow', 'session', 'elapsedNs'}.issubset(event):
            continue
        if event['kind'] not in ('STAGE', 'TOTAL', 'LINE_INFERENCE', 'BATCH_IMAGE', 'MLKIT_REQUEST'):
            continue
        timing_ns(event['elapsedNs'])
        groups[(event['flow'], event['kind'], event.get('stage', 'TOTAL'))].append(event); count += 1
    if not count:
        raise ValueError('No NOTE2SNAP_PERF JSON events found')
    rows = []
    for (flow, kind, stage), events in sorted(groups.items()):
        values = [timing_ns(e['elapsedNs']) for e in events]
        calls = sum(e.get('count', 0) for e in events) if kind == 'STAGE' else len(events)
        rows.append(dict(flow=flow, kind=kind, stage=stage, event_times=summary(values), calls=calls,
            total_ms=sum(values), mean_per_call_ms=sum(values) / calls if calls else None,
            failures=sum(e.get('failures', 0) for e in events),
            main_thread_starts=sum(e.get('mainThreadStarts', 0) for e in events)))
    return dict(events=count, warning='Stages are inclusive; do not sum nested stages or duplicate STAGE/per-line events. Session IDs can repeat across processes; collect one log per process/run.', groups=rows)


def collect(output, adb='adb', serial=None, baseline=None):
    command = [adb] + (['-s', serial] if serial else [])
    command += ['exec-out', 'run-as', 'com.example.note2snap', 'tar', '-cf', '-', 'files/paddle-comparisons']
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=True)
    root = Path(output)
    root.mkdir(parents=True, exist_ok=True)
    copied = 0
    with tarfile.open(fileobj=io.BytesIO(result.stdout), mode='r:') as archive:
        for member in archive:
            if member.isdir():
                continue
            parts = PurePosixPath(member.name).parts
            if not member.isfile() or len(parts) != 3 or parts[:2] != ('files', 'paddle-comparisons') or not re.fullmatch(r'[A-Za-z0-9_.-]+\.jsonl', parts[2]):
                raise ValueError(f'Unexpected archive member: {member.name}')
            if member.size > 4 * 1024 * 1024:
                raise ValueError(f'Record file too large: {member.name}; archive this research run separately')
            payload = archive.extractfile(member).read()
            if baseline is not None and (Path(baseline) / parts[2]).exists():
                if (Path(baseline) / parts[2]).read_bytes() != payload:
                    raise ValueError(f'Baseline record changed: {parts[2]}; wait for scan completion')
                continue
            target = root / parts[2]
            if target.exists() and target.read_bytes() != payload:
                raise ValueError(f'Local snapshot differs: {target}; collect into a fresh run directory')
            target.write_bytes(payload); copied += 1
    if not copied:
        raise ValueError('No record files collected; a blank/rejected scan may not have produced lines')
    return copied


def write_json(output, data):
    with Path(output).open('x', encoding='utf-8') as stream:
        json.dump(data, stream, indent=2, ensure_ascii=False, allow_nan=False)
        stream.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='operation', required=True)
    c = commands.add_parser('collect'); c.add_argument('--output', required=True); c.add_argument('--adb', default='adb'); c.add_argument('--serial'); c.add_argument('--baseline', help='Exclude unchanged files already in this local snapshot')
    t = commands.add_parser('template'); t.add_argument('--records', required=True); t.add_argument('--output', required=True)
    s = commands.add_parser('score'); s.add_argument('--records', required=True); s.add_argument('--labels', required=True); s.add_argument('--output', required=True)
    p = commands.add_parser('perf'); p.add_argument('--log', required=True); p.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.operation == 'collect':
        print(f'Collected {collect(args.output, args.adb, args.serial, args.baseline)} files; no device files modified')
    elif args.operation == 'template':
        template(read_records(args.records), args.output)
    elif args.operation == 'score':
        records = read_records(args.records); write_json(args.output, score(records, read_labels(args.labels, records)))
    else:
        write_json(args.output, perf_report(args.log))


if __name__ == '__main__':
    main()
