/** Offline research only. Does not select an OCR engine or change Android text. */
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { errors } from './ocr_scoring.mjs';

export function scoreCropRefinement(records) {
  const seen = new Set();
  const rows = records.filter(r=>r.status==='completed').flatMap(record=>{
    if (record.declaredSampleCount !== record.samples.length) throw Error('Incomplete declared corpus');
    return record.samples.map(s=>{
      const key=s.kind+':'+s.id;
      if(seen.has(key)) throw Error('Duplicate sample: choose one run explicitly: '+key);
      seen.add(key);
      const off=s.trials.filter(t=>!t.enabled), on=s.trials.filter(t=>t.enabled);
      if(off.length!==2 || on.length!==2) throw Error('Expected two A/B iterations: '+key);
      const before=errors(s.groundTruth,off[0].rawText), after=errors(s.groundTruth,on[0].rawText);
      const dc=after.characterEdits-before.characterEdits, dw=after.wordEdits-before.wordEdits;
      const outcome=dc===0&&dw===0?'unchanged':dc<=0&&dw<=0?'improved':dc>=0&&dw>=0?'regressed':'tradeoff';
      const ns=t=>t.preprocessingNs+t.inferenceNs+t.decodingNs;
      return {id:s.id,kind:s.kind,category:s.category,groundTruth:s.groundTruth,
        beforeText:off[0].rawText,afterText:on[0].rawText,before,after,outcome,
        applied:on[0].applied,reason:on[0].reason,
        repeatable:off.every(t=>t.rawText===off[0].rawText)&&on.every(t=>t.rawText===on[0].rawText),
        sourceBox:s.sourceBox,sourceBoxPreserved:s.sourceBoxPreserved,
        refinementNs:on.map(t=>t.refinementNs),
        beforeRecognitionNs:off.map(ns),afterRecognitionNs:on.map(ns),
        scratchBytes:Math.max(...on.map(t=>t.scratchBytes)),
        temporaryCropPixelBytes:Math.max(...on.map(t=>t.temporaryCropBytes)),
        originalInputWidth:off[0].inputWidth,refinedInputWidth:on[0].inputWidth,
        refinedInputHeight:on[0].inputHeight,originalHeight:s.originalHeight};
    });
  });
  const aggregate=group=>{
    const sum=key=>{
      const t=group.reduce((a,r)=>{
        for(const [k,v]of Object.entries(r[key]))a[k]=(a[k]??0)+v;
        return a;
      },{});
      return {...t,cer:t.characterEdits/t.referenceCharacters,wer:t.wordEdits/t.referenceWords};
    };
    return {count:group.length,applied:group.filter(r=>r.applied).length,
      outcomes:Object.fromEntries(['improved','regressed','unchanged','tradeoff'].map(o=>[o,group.filter(r=>r.outcome===o).length])),
      before:sum('before'),after:sum('after')};
  };
  const kinds=[...new Set(rows.map(r=>r.kind))];
  return {metric:'Micro CER/WER; case/punctuation-sensitive; whitespace normalized; no correction',
    warning:'Rendered controls are separate from photographed handwriting; no untested category is inferred',
    byKind:Object.fromEntries(kinds.map(k=>[k,aggregate(rows.filter(r=>r.kind===k))])),
    byCategory:Object.fromEntries([...new Set(rows.map(r=>r.kind+':'+r.category))].map(k=>[k,aggregate(rows.filter(r=>r.kind+':'+r.category===k))])),
    rows};
}
function cropRecordsUnder(folder) {
  return readdirSync(folder,{withFileTypes:true}).flatMap(e=>{
    const p=join(folder,e.name);
    return e.isDirectory()?cropRecordsUnder(p):e.name==='record.json'?[JSON.parse(readFileSync(p,'utf8'))]:[];
  });
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  if(!process.argv[2])throw Error('Supply an exported refinement directory');
  console.log(JSON.stringify(scoreCropRefinement(cropRecordsUnder(resolve(process.argv[2]))),null,2));
}
