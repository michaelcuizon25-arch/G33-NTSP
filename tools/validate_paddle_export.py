"""Development-only validation of the user-supplied official Paddle PIR model.
Requires pinned Paddle CPU 3.2.2, Paddle2ONNX 2.1.0, ONNX 1.17.0,
ONNX Runtime 1.22.0, OpenCV 4.12.0 and numpy. No downloads in this script.
Use a pinned PaddleOCR checkout (see docs/PADDLE_OCR_COMPARISON.md).
"""
from pathlib import Path
import os,ast,types,math,json,time,hashlib,importlib.util
os.environ['OMP_NUM_THREADS']='1'
import numpy as np,cv2,onnx,onnxruntime as ort,paddle
from paddle.inference import Config,create_predictor
import argparse, subprocess
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--work-dir',type=Path,required=True,help='Contains en_PP-OCRv5_mobile_rec_infer directory')
parser.add_argument('--source-dir',type=Path,required=True,help='Pinned official PaddleOCR checkout')
parser.add_argument('--image-dir',type=Path,required=True,help='Images from compare_opencv_enhancement.py prepare')
parser.add_argument('--export',action='store_true',help='Export before inference validation')
args=parser.parse_args()
root=args.work_dir; source=args.source_dir
source_commit=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
assert source_commit == 'dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf', 'Unexpected reference source revision'
if args.export:
 subprocess.run(['paddle2onnx','--model_dir',str(root/'en_PP-OCRv5_mobile_rec_infer'),
  '--model_filename','inference.json','--params_filename','inference.pdiparams',
  '--save_file',str(root/'en_PP-OCRv5_mobile_rec.onnx'),'--opset_version','17',
  '--enable_auto_update_opset','False','--enable_onnx_checker','True','--optimize_tool','None'],check=True)
# Execute the actual pinned reference method, without initializing the full OCR pipeline.
tree=ast.parse((source/'tools/infer/predict_rec.py').read_text())
cls=next(n for n in tree.body if isinstance(n,ast.ClassDef) and n.name=='TextRecognizer')
f=next(n for n in cls.body if isinstance(n,ast.FunctionDef) and n.name=='resize_norm_img')
ns={'np':np,'cv2':cv2,'math':math};exec(compile(ast.Module(body=[f],type_ignores=[]),'official_resize_norm_img','exec'),ns)
ref=types.SimpleNamespace(rec_image_shape=[3,48,320],rec_algorithm='SVTR_LCNet',use_onnx=False)
def prepare(img):
 ratio=max(320/48,img.shape[1]/img.shape[0]);return ns['resize_norm_img'](ref,img,ratio)[None]
spec=importlib.util.spec_from_file_location('official_rec_postprocess',source/'ppocr/postprocess/rec_postprocess.py');decoder_mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(decoder_mod)
decoder=decoder_mod.CTCLabelDecode(str(source/'ppocr/utils/dict/ppocrv5_en_dict.txt'),use_space_char=True)
model=root/'en_PP-OCRv5_mobile_rec.onnx';onnx.checker.check_model(str(model));graph=onnx.load(model)
opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1
start=time.perf_counter();session=ort.InferenceSession(str(model),opts,providers=['CPUExecutionProvider']);init=(time.perf_counter()-start)*1000
print('ONNX inputs',[(x.name,x.shape,x.type) for x in session.get_inputs()],flush=True);print('outputs',[(x.name,x.shape,x.type) for x in session.get_outputs()],flush=True)
p=root/'en_PP-OCRv5_mobile_rec_infer';cfg=Config(str(p/'inference.json'),str(p/'inference.pdiparams'));cfg.disable_gpu();cfg.set_cpu_math_library_num_threads(1);cfg.disable_mkldnn();cfg.switch_ir_optim(False)
predictor=create_predictor(cfg);print('Paddle inputs',predictor.get_input_names(),flush=True)
results=[]
images=[('blank',np.full((48,320,3),255,np.uint8))]
for name in ['clear','faint','uneven-lighting','half-resolution']:
 for variant in ['original','enhanced']:
  file=args.image_dir/f'title-{name}-{variant}.png'
  images.append((f'title-{name}-{variant}',cv2.imread(str(file))))
images.extend([('narrow',np.random.default_rng(42).integers(0,256,(71,17,3),dtype=np.uint8)),('wide',np.full((48,1280,3),255,np.uint8))])
for name,img in images:
 t=time.perf_counter();x=prepare(img);prep=(time.perf_counter()-t)*1000
 h=predictor.get_input_handle(predictor.get_input_names()[0]);h.reshape(x.shape);h.copy_from_cpu(x);predictor.run();py=predictor.get_output_handle(predictor.get_output_names()[0]).copy_to_cpu()
 t=time.perf_counter();oy=session.run(None,{session.get_inputs()[0].name:x})[0];ms=(time.perf_counter()-t)*1000
 a=decoder(py)[0];b=decoder(oy)[0]
 delta=float(np.max(np.abs(py-oy)));same=a[0]==b[0];numeric=bool(np.allclose(py,oy,rtol=1e-3,atol=1e-4))
 row={'name':name,'input_shape':list(x.shape),'output_shape':list(oy.shape),'paddle_text':a[0],'onnx_text':b[0],'max_abs_difference':delta,'numeric_parity':numeric,'text_parity':same,'preprocessing_ms':prep,'onnx_first_invoke_ms':ms}
 print(json.dumps(row),flush=True);results.append(row)
 if not same or not numeric:
  (root/'parity-results.json').write_text(json.dumps({'passed':False,'results':results},indent=2));raise RuntimeError('Paddle/ONNX parity failed for '+name)
report={'passed':True,'paddle':paddle.__version__,'onnxruntime':ort.__version__,'source_commit':source_commit,'onnx_sha256':hashlib.sha256(model.read_bytes()).hexdigest(),'onnx_bytes':model.stat().st_size,'onnx_init_ms':init,'results':results}
(root/'parity-results.json').write_text(json.dumps(report,indent=2)+'\n')
