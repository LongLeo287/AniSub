"""Vietnamese ROI recognition with verified local EasyOCR weights; no implicit download."""
import importlib.util
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
def detect_language(text):
 import re
 # Conservative VI/EN routing heuristic, not a universal language detector.
 lower=text.lower()
 if any(c in lower for c in 'ăâđêôơưàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ'):return 'vi'
 words=set(re.findall(r'[a-z]+',lower))
 return 'vi' if len(words&{'anh','em','toi','khong','ban','vay','mot','nhung','voi','duoc','nay','chu','day'})>=2 else 'en'
class LatinOcr:
 def __init__(self):
  spec=importlib.util.spec_from_file_location('ocr_integrity',ROOT/'model-manager/tools/install_ocr_models.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
  directory=ROOT/'models/easyocr-latin-v1.3';module.verify(directory)
  import torch
  import easyocr
  if easyocr.__version__!='1.7.2':raise ValueError('Unsupported OCR SDK')
  torch.set_num_threads(2)
  original=torch.load
  def restricted(*args,**kwargs):kwargs['weights_only']=True;return original(*args,**kwargs)
  torch.load=restricted
  try:self.reader=easyocr.Reader(['vi','en'],gpu=False,model_storage_directory=str(directory),user_network_directory=str(ROOT/'work/easyocr-user-network'),download_enabled=False,verbose=False)
  finally:torch.load=original
 def read(self,image):
  import numpy as np
  if image.width>1280:image=image.resize((1280,max(1,int(image.height*1280/image.width))))
  rows=self.reader.readtext(np.asarray(image),detail=1,paragraph=False,batch_size=1,workers=0,canvas_size=1280)
  return ' '.join(row[1] for row in rows if row[2]>=.35)
