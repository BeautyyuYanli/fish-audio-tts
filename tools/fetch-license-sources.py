"""Restore the reviewed upstream texts; checksums prevent silent source changes.

Only needed when maintaining licenses, never during ordinary builds. Update
licenses/catalog.json after reviewing a dependency and its official license.
"""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
catalog = json.loads((ROOT / "licenses/catalog.json").read_text())
for path, metadata in catalog["sources"].items():
    request = urllib.request.Request(metadata["url"], headers={"User-Agent": "Fish-Audio-TTS-license-bundler"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
    if "archiveEntry" in metadata:
        with ZipFile(io.BytesIO(data)) as archive:
            data = archive.read(metadata["archiveEntry"])
    if hashlib.sha256(data).hexdigest() != metadata["sha256"]:
        raise ValueError(f"License checksum mismatch: {path}")
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    print(path)
