"""Build the fixed OFL Chinese PDF asset in CI, never on the small VM."""
import hashlib
import io
import json
import pathlib
import urllib.request
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = pathlib.Path(__file__).resolve().parents[3]


def main():
    manifest = json.loads((ROOT / 'server/ops/assets/font-source.json').read_text())
    with urllib.request.urlopen(manifest['sourceUrl'], timeout=90) as response:
        source = response.read(32 * 1024 * 1024)
    if hashlib.sha256(source).hexdigest() != manifest['sourceSha256']:
        raise ValueError('Font source digest mismatch')
    font = TTFont(io.BytesIO(source), recalcTimestamp=False)
    instantiateVariableFont(font, {'wght': manifest['instanceWeight']}, inplace=True)
    output = io.BytesIO()
    font.save(output)
    data = output.getvalue()
    if hashlib.sha256(data).hexdigest() != manifest['fontSha256'] or len(data) != manifest['fontBytes']:
        raise ValueError('Static font identity mismatch')
    target = ROOT / 'vm-assets/quote-font.ttf'
    target.parent.mkdir(exist_ok=True)
    with target.open('xb') as stream:
        stream.write(data)
    print('QUOTE_FONT_BUILT: fixed SHA-256 verified; SIL OFL 1.1')


if __name__ == '__main__':
    main()
