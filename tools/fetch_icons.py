#!/usr/bin/env python3
"""Fetches the brand icon and brand colour of every default home tile at build time and stores it as a small WebP in
app/src/main/assets/icons/<host>.webp (112 px, the tile's icon size at xhdpi).

For each site: apple-touch-icon / icon <link>s and the web app manifest's icons are collected, the largest one
(at least 192 px when the site has one) is downloaded; sites that block scripted fetches fall back to Google's
favicon service at 256 px. Run after changing Bookmarks.defaults(); the result is committed.
"""
import io, json, os, re, sys, urllib.parse, urllib.request
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'icons')
SIZE = 112
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36'
SITES = {  # icon key (Services.java iconHost) -> page to read the icon links from
    'www.primevideo.com': 'https://www.primevideo.com/',
    'www.youtube.com': 'https://www.youtube.com/',
    'pluto.tv': 'https://pluto.tv/',
    'tubitv.com': 'https://tubitv.com/',
}


def get(url, limit=4 << 20):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': '*/*'})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read(limit), r.geturl()


def candidates(page):
    out = []
    try:
        html, base = get(page)
    except Exception as e:
        print('  page failed:', e)
        return out
    html = html.decode('utf-8', 'replace')
    for tag in re.findall(r'<link\b[^>]*>', html, re.I):
        rel = (re.search(r'rel=["\']([^"\']+)', tag, re.I) or [None, ''])[1].lower()
        href = re.search(r'href=["\']([^"\']+)', tag, re.I)
        if not href:
            continue
        url = urllib.parse.urljoin(base, href.group(1).replace('&amp;', '&'))
        if 'manifest' in rel:
            try:
                m = json.loads(get(url)[0])
                for ic in m.get('icons', []):
                    s = max([int(x) for x in re.findall(r'(\d+)x\d+', ic.get('sizes', ''))] or [0])
                    src = ic.get('src', '')
                    if 'svg' not in ic.get('type', '') and not src.endswith('.svg') and 'monochrome' not in src \
                            and 'monochrome' not in ic.get('purpose', ''):
                        out.append((s, urllib.parse.urljoin(url, ic['src'])))
            except Exception as e:
                print('  manifest failed:', e)
        elif 'icon' in rel:
            s = max([int(x) for x in re.findall(r'(\d+)x\d+', tag)] or [180 if 'apple' in rel else 32])
            if not url.endswith('.svg') and 'monochrome' not in url:
                out.append((s, url))
    # the conventional location, for pages that render their <head> with script
    out.append((180, urllib.parse.urljoin(page, '/apple-touch-icon.png')))
    return sorted(out, reverse=True)


def load(url):
    data, _ = get(url)
    im = Image.open(io.BytesIO(data))
    if getattr(im, 'n_frames', 1) > 1 or im.format == 'ICO':
        im.size = max(im.info.get('sizes', [im.size]))  # largest ICO entry
    return im.convert('RGBA')


def brand_color(im):
    """Most common clearly coloured pixel of the icon (ignoring transparent, near-white, near-black and grey),
    or the most common opaque colour when the logo has none (e.g. a black wordmark)."""
    import colorsys
    small = im.resize((48, 48))
    counts = {}
    for r, g, b, a in small.getdata():
        if a < 200:
            continue
        key = (r // 16 * 16 + 8, g // 16 * 16 + 8, b // 16 * 16 + 8)
        counts[key] = counts.get(key, 0) + 1
    def ok(c):
        h, l, sat = colorsys.rgb_to_hls(*[x / 255 for x in c])
        return sat > 0.35 and 0.12 < l < 0.88
    ranked = sorted(counts.items(), key=lambda kv: -kv[1])
    for c, _ in ranked:
        if ok(c):
            return '#%02X%02X%02X' % c
    return '#%02X%02X%02X' % ranked[0][0] if ranked else '#455A64'


def main():
    os.makedirs(OUT, exist_ok=True)
    colors = {}
    for host, page in SITES.items():
        print(host)
        im = None
        for size, url in candidates(page):
            try:
                im = load(url)
                if min(im.size) >= 64:
                    print('  %s (%dx%d)' % (url, im.size[0], im.size[1]))
                    break
                im = None
            except Exception as e:
                print('  failed', url, e)
        if im is None:
            dom = urllib.parse.urlparse(page).hostname
            url = 'https://www.google.com/s2/favicons?domain=%s&sz=256' % dom
            im = load(url)
            print('  fallback %s (%dx%d)' % (url, im.size[0], im.size[1]))
        bbox = im.getchannel('A').getbbox()
        if bbox:
            im = im.crop(bbox)
        w, h = im.size
        side = max(w, h)
        sq = Image.new('RGBA', (side, side), (0, 0, 0, 0))
        sq.paste(im, ((side - w) // 2, (side - h) // 2))
        sq = sq.resize((SIZE, SIZE), Image.LANCZOS)
        path = os.path.join(OUT, host + '.webp')
        sq.save(path, 'WEBP', quality=88, method=6)
        colors[host] = brand_color(sq)
        print('  ->', os.path.getsize(path), 'bytes, brand colour', colors[host])
    with open(os.path.join(OUT, 'colors.json'), 'w') as f:
        json.dump(colors, f, indent=1, sort_keys=True)


if __name__ == '__main__':
    sys.exit(main())
