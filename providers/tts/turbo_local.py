"""Reviewed local-only Turbo construction; never modify vendor SDK or fetch assets."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def verify_quality(directory, name):
    catalog = json.loads((ROOT / 'model-manager/quality-catalog.json').read_text(encoding='utf-8'))
    expected = next(item for item in catalog['packs'] if item['name'] == name)
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    records = manifest.get('artifacts', [])
    if manifest.get('repo') != expected['repo'] or manifest.get('revision') != expected['revision'] or len(records) != len(expected['files']) or {x['file'] for x in records} != set(expected['files']):
        raise ValueError('Quality model identity mismatch')
    total = 0
    for item in records:
        path = directory / item['file']
        if path.is_symlink() or directory.resolve() not in path.resolve().parents:
            raise ValueError('Quality artifact path mismatch')
        size = path.stat().st_size
        total += size
        if size != item['bytes'] or total > 2 * 1024**3:
            raise ValueError('Quality artifact size mismatch')
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != item['sha256']:
            raise ValueError('Quality artifact digest mismatch')
    return catalog


def load_turbo(directory):
    catalog = verify_quality(directory, 'vieneu-turbo-61b85e3')
    codec = ROOT / 'models/moss-codec-ceff0d0'
    verify_quality(codec, 'moss-codec-ceff0d0')
    voice = ROOT / catalog['voiceAssets']['file']
    if hashlib.sha256(voice.read_bytes()).hexdigest() != catalog['voiceAssets']['sha256']:
        raise ValueError('Voice asset digest mismatch')
    from vieneu._v3_turbo_engine import onnx_runtime_lite
    from vieneu.v3turbo import V3TurboVieNeuTTS
    original = onnx_runtime_lite.OnnxV3LiteEngine

    class LocalEngine(original):
        def __init__(self, **kwargs):
            kwargs['checkpoint_path'] = str(directory)
            kwargs['onnx_dir'] = str(directory / 'onnx_update')
            kwargs['codec_dir'] = str(codec)
            super().__init__(**kwargs)

        def _resolve_root_file(self, filename):
            # Optional cloning/denoiser assets are not shipped. Do not try Hub resolution.
            path = directory / filename
            return str(path) if path.is_file() else None

    # SDK 3.8.3 does not forward codec_dir. Scoped construction adapts this omission;
    # this worker is serial, restore symbol even if construction fails.
    onnx_runtime_lite.OnnxV3LiteEngine = LocalEngine
    try:
        return V3TurboVieNeuTTS(backbone_repo=str(directory), onnx_dir=str(directory / 'onnx_update'), device='cpu', backend='onnx', threads=2, babble_retries=1)
    finally:
        onnx_runtime_lite.OnnxV3LiteEngine = original
