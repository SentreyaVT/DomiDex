"""Offline verify retained candidate bytes; no image editing, network or app changes."""
import hashlib
import json
from pathlib import Path
import sys
from PIL import Image


def main():
    root = Path(sys.argv[1]).resolve()
    if root.parent != Path('C:/Users/Dominik/DomiDex-audits') or not root.name.startswith('card-artwork-verified-20261003-'):
        raise ValueError('Owned candidate evidence directory required')
    plan = json.loads((root / 'plan.json').read_text(encoding='utf-8'))
    summary = json.loads((root / 'summary.json').read_text(encoding='utf-8'))
    if summary['sourceSha256'] != plan['sourceSha256']:
        raise ValueError('Source receipt mismatch')
    results = [json.loads(line) for line in (root / 'results.jsonl').read_text(encoding='utf-8').splitlines()]
    expected = {item['url'] for item in plan['urls']}
    if len({item['url'] for item in results}) != len(results) or any(item['url'] not in expected for item in results):
        raise ValueError('Unexpected/duplicate fetched image')
    by_url = {}
    for item in results:
        row = dict(item)
        row['decoded'] = False
        if row['status'] == 'FETCHED_IMAGE_NOT_DECODED':
            try:
                file = (root / row['file']).resolve()
                if file.parent != root / 'images' or not file.is_file() or file.is_symlink():
                    raise ValueError('Unexpected image path')
                raw = file.read_bytes()
                if len(raw) != row['bytes'] or hashlib.sha256(raw).hexdigest() != row['sha256']:
                    raise ValueError('Image bytes changed')
                with Image.open(file) as image:
                    image.verify()
                with Image.open(file) as image:
                    image.load()
                    row.update(width=image.width, height=image.height, format=image.format)
                    if not (100 <= image.width <= 4000 and 100 <= image.height <= 6000 and .5 <= image.width / image.height <= .9):
                        raise ValueError('Unexpected card dimensions')
                    if all(high - low < 4 for low, high in image.convert('RGB').getextrema()):
                        raise ValueError('Near-uniform image is not usable card artwork')
                row['decoded'] = True
            except Exception as error:
                row['decodeError'] = str(error)
        by_url[row['url']] = row
    cards = []
    for card in plan['cards']:
        candidate = card['candidate']
        low = by_url.get(candidate['lowUrl'], {})
        high = by_url.get(candidate['highUrl'], {})
        cards.append({**card, 'smallDecoded': low.get('decoded', False), 'largeDecoded': high.get('decoded', False),
                      'usable': low.get('decoded', False) and high.get('decoded', False)})
    report = {'sourceSha256': plan['sourceSha256'], 'plannedUrls': len(expected), 'decodedUrls': sum(r['decoded'] for r in by_url.values()),
              'plannedCards': len(cards), 'usableCards': sum(r['usable'] for r in cards), 'cards': cards, 'images': list(by_url.values()),
              'limitations': ['Decodable exact-source image metadata; not an assertion of manual visual review of every artwork.',
                              'English fallback remains English; no stamp/finish equivalence inferred.']}
    with (root / 'decoded.json').open('x', encoding='utf-8') as stream:
        json.dump(report, stream, indent=2, ensure_ascii=False)
    print(json.dumps({k: report[k] for k in ('plannedUrls', 'decodedUrls', 'plannedCards', 'usableCards')}))


if __name__ == '__main__':
    main()
