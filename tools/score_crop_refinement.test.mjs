import test from 'node:test';
import assert from 'node:assert/strict';
import { errors } from './ocr_scoring.mjs';
import { scoreCropRefinement } from './score_crop_refinement.mjs';
function sample(id,before,after,kind='photographed_handwriting'){
  const trial=(enabled,rawText,iteration)=>({enabled,rawText,iteration,applied:enabled,reason:enabled?'verified_flat_margin':'disabled',
    preprocessingNs:1,inferenceNs:2,decodingNs:3,refinementNs:enabled?4:0,scratchBytes:100,temporaryCropBytes:200,inputWidth:320,inputHeight:80});
  return {id,kind,category:'mixed_case',groundTruth:'The quick fox.',sourceBox:[1400,100,1800,200],
    sourceBoxPreserved:true,originalHeight:100,trials:[trial(false,before,0),trial(true,after,0),trial(true,after,1),trial(false,before,1)]};
}
const record=samples=>({status:'completed',declaredSampleCount:samples.length,samples});
test('reports improvement regression and unchanged without dropping samples',()=>{
  const result=scoreCropRefinement([record([sample('a','The fox.','The quick fox.'),sample('b','The quick fox.','fox'),sample('c','The quick fox.','The quick fox.')])]);
  assert.equal(result.rows.length,3);
  assert.deepEqual(result.byKind.photographed_handwriting.outcomes,{improved:1,regressed:1,unchanged:1,tradeoff:0});
});
test('keeps printed controls separate from handwriting',()=>{
  const r=scoreCropRefinement([record([sample('same','bad','The quick fox.'),sample('same','bad','bad','synthetic_rendered_control')])]);
  assert.equal(r.byKind.photographed_handwriting.count,1);
  assert.equal(r.byKind.synthetic_rendered_control.count,1);
});
test('does not hide missing punctuation or capitalization errors',()=>{
  assert.equal(errors('SQL HTTP.','sql HTTP').characterEdits,4);
});
test('preserves source geometry in report',()=>{
  assert.deepEqual(scoreCropRefinement([record([sample('a','x','x')])]).rows[0].sourceBox,[1400,100,1800,200]);
});
test('rejects duplicate runs and incomplete declared samples',()=>{
  const r=record([sample('a','x','x')]);
  assert.throws(()=>scoreCropRefinement([r,r]),/Duplicate/);
  assert.throws(()=>scoreCropRefinement([{...r,declaredSampleCount:2}]),/Incomplete/);
});
test('partial exports never count as completed accuracy evidence',()=>{
  assert.equal(scoreCropRefinement([{status:'started',samples:[]}]).rows.length,0);
});
