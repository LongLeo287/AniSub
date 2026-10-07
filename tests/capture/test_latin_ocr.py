"""Pinned local Vietnamese OCR synthetic fixture and artifact negative checks."""
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'providers/capture'))
from latin_ocr import LatinOcr,detect_language
from PIL import Image,ImageDraw,ImageFont

class LatinTests(unittest.TestCase):
 def test_vi_en_heuristic(self):
  self.assertEqual(detect_language('Xin chào Việt Nam'),'vi')
  self.assertEqual(detect_language('Hello world'),'en')
  self.assertEqual(detect_language('Anh di voi em'),'vi')
 def test_vietnamese_clear_fixture(self):
  image=Image.new('RGB',(1100,150),'black')
  ImageDraw.Draw(image).text((30,25),'Xin chào Việt Nam',fill='white',font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',60))
  result=LatinOcr().read(image)
  self.assertIn('Việt Nam',result)
  self.assertIn('chào',result)
 def test_verified_pack(self):
  spec=importlib.util.spec_from_file_location('ocr_install',ROOT/'model-manager/tools/install_ocr_models.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
  module.verify(ROOT/'models/easyocr-latin-v1.3')
 def test_bad_manifest_rejected(self):
  import tempfile,json
  spec=importlib.util.spec_from_file_location('ocr_install',ROOT/'model-manager/tools/install_ocr_models.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
  with tempfile.TemporaryDirectory() as directory:
   target=Path(directory);(target/'manifest.json').write_text(json.dumps({'id':'wrong','artifacts':[]}),encoding='utf-8')
   with self.assertRaises(ValueError):module.verify(target)

if __name__=='__main__':unittest.main()
