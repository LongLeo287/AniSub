"""Explicit small OCR pack; reuse verified existing detector, no runtime installation."""
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[2]
FILES={
 'craft_mlt_25k.pth':('https://github.com/JaidedAI/EasyOCR/releases/download/pre-v1.1.6/craft_mlt_25k.zip','2f8227d2def4037cdb3b34389dcf9ec1',90*1024**2),
 'latin_g2.pth':('https://github.com/JaidedAI/EasyOCR/releases/download/v1.3/latin_g2.zip','469869130aad1a34e8f9086f4262bc59',25*1024**2),
}
def hash_file(path,algorithm):
 h=hashlib.new(algorithm)
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def verify(directory):
 manifest=json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
 if manifest.get('id')!='easyocr-latin-v1.3' or len(manifest['artifacts'])!=2 or {x['file'] for x in manifest['artifacts']}!=set(FILES):raise ValueError('OCR identity mismatch')
 for item in manifest['artifacts']:
  path=directory/item['file'];expected=FILES[item['file']]
  if path.is_symlink() or path.resolve().parent!=directory.resolve() or path.stat().st_size!=item['bytes'] or path.stat().st_size>expected[2] or hash_file(path,'md5')!=expected[1] or hash_file(path,'sha256')!=item['sha256']:raise ValueError('OCR artifact invalid')
def install():
 final=ROOT/'models/easyocr-latin-v1.3'
 if final.exists():verify(final);print('OCR_ALREADY_VERIFIED');return
 if shutil.disk_usage(ROOT).free<300*1024**2:raise OSError('OCR staging space unavailable')
 stage=ROOT/'work/model-staging'/('easyocr-'+uuid.uuid4().hex);stage.mkdir(parents=True)
 rows=[]
 for filename,(url,md5,limit) in FILES.items():
  target=stage/filename;cached=Path.home()/'.EasyOCR'/'model'/filename
  if cached.is_file() and cached.stat().st_size<=limit and hash_file(cached,'md5')==md5:
   shutil.copyfile(cached,target)
  else:
   archive=stage/(filename+'.zip');total=0
   with urllib.request.urlopen(url,timeout=60) as response,archive.open('wb') as output:
    while True:
     block=response.read(1024*1024)
     if not block:break
     total+=len(block)
     if total>limit:raise ValueError('OCR download exceeds budget')
     output.write(block)
   with zipfile.ZipFile(archive) as data:
    entry=data.getinfo(filename)
    if entry.file_size>limit:raise ValueError('OCR extraction exceeds budget')
    with data.open(entry) as source,target.open('wb') as output:shutil.copyfileobj(source,output)
   archive.unlink()
  if hash_file(target,'md5')!=md5:raise ValueError('Upstream OCR digest mismatch')
  rows.append({'file':filename,'bytes':target.stat().st_size,'sha256':hash_file(target,'sha256')})
 (stage/'manifest.json').write_text(json.dumps({'id':'easyocr-latin-v1.3','sdk':'1.7.2','source':'JaidedAI/EasyOCR pinned release artifacts','artifacts':rows},indent=2),encoding='utf-8')
 verify(stage);stage.rename(final);print(json.dumps({'status':'OCR_VERIFIED_INSTALLED','bytes':sum(x['bytes'] for x in rows)}))
if __name__=='__main__':install()
