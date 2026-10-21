#!/usr/bin/env python3
"""Theme store tool for the repo's themes/ folder (served from the public repo as the in-app theme store).

  tools/theme_index.py previews   draw themes/previews/<id>.png for every theme that has none (a schematic of its
                                  colours: no screenshots, so no personal watch history ends up in the repo)
  tools/theme_index.py write      fill size and sha256 (theme and preview) in themes/index.json from the files
  tools/theme_index.py check      fail when index.json and the files disagree (CI; the theme validator itself runs
                                  in tools/themetest/run.sh)
"""
import hashlib, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR = os.path.join(ROOT, "themes")
INDEX = os.path.join(DIR, "index.json")


def sha(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def load():
    with open(INDEX) as f:
        return json.load(f)


def write():
    idx = load()
    for e in idx["themes"]:
        t, p = os.path.join(DIR, e["file"]), os.path.join(DIR, e["preview"])
        e["size"], e["sha256"] = os.path.getsize(t), sha(t)
        e["previewSize"], e["previewSha256"] = os.path.getsize(p), sha(p)
    with open(INDEX, "w") as f:
        json.dump(idx, f, indent=2)
        f.write("\n")


def check():
    bad = 0
    idx = load()
    files = set()
    for e in idx["themes"]:
        for key, size, h in (("file", "size", "sha256"), ("preview", "previewSize", "previewSha256")):
            path = os.path.join(DIR, e[key])
            files.add(os.path.normpath(path))
            if not os.path.isfile(path):
                print(f"{e['id']}: missing {e[key]}"); bad += 1; continue
            if os.path.getsize(path) != e[size] or sha(path) != e[h]:
                print(f"{e['id']}: {e[key]} does not match its {size}/{h} (run tools/theme_index.py write)"); bad += 1
        with open(os.path.join(DIR, e["file"])) as f:
            if json.load(f).get("id") != e["id"]:
                print(f"{e['id']}: the theme file's id differs from the index"); bad += 1
    for n in os.listdir(DIR):
        if n.endswith(".json") and n != "index.json" and os.path.normpath(os.path.join(DIR, n)) not in files:
            print(f"{n}: not listed in index.json"); bad += 1
    print("theme index:", "OK" if not bad else f"{bad} problem(s)")
    return bad == 0


def previews():
    from PIL import Image, ImageDraw
    os.makedirs(os.path.join(DIR, "previews"), exist_ok=True)
    for e in load()["themes"]:
        out = os.path.join(DIR, e["preview"])
        if os.path.exists(out):
            continue
        with open(os.path.join(DIR, e["file"])) as f:
            t = json.load(f)
        c = t.get("colors", {})
        hx = lambda k, d: tuple(int(c.get(k, d)[i:i + 2], 16) for i in (1, 3, 5))
        bg, deep, panel, surf = hx("background", "#0B0D0F"), hx("backgroundDeep", "#060809"), hx("panel", "#15181B"), hx("surface", "#22272C")
        text, soft, acc = hx("text", "#F3F5F7"), hx("textSoft", "#858B90"), hx("accent", "#CBD1D8")
        W, H = 960, 540
        im = Image.new("RGB", (W, H), bg)
        d = ImageDraw.Draw(im)
        for y in range(H):  # soft vertical fade from the background to the deep tone
            k = y / H
            d.line([(0, y), (W, y)], fill=tuple(int(bg[i] * (1 - k) + deep[i] * k) for i in range(3)))
        nav = json.dumps(t.get("screens", {}))
        side = '"sidebar"' in nav
        x0 = 230 if side else 48
        if side:
            d.rectangle([0, 0, 200, H], fill=panel)
            for i in range(7):
                d.rounded_rectangle([28, 70 + i * 44, 170, 96 + i * 44], 8, fill=surf if i else acc)
        else:
            for i in range(5):
                d.rounded_rectangle([x0 + i * 110, 30, x0 + 90 + i * 110, 52], 11, fill=surf if i else acc)
        d.rectangle([x0, 90, x0 + 300, 112], fill=text)
        d.rectangle([x0, 124, x0 + 200, 136], fill=soft)
        cw, ch, gap = (W - x0 - 48 - 3 * 20) // 4, 120, 20
        for r, y in enumerate((190, 360)):
            d.rectangle([x0, y - 26, x0 + 140, y - 16], fill=soft)
            for i in range(5):
                x = x0 + i * (cw + gap)
                d.rounded_rectangle([x, y, x + cw, y + ch], 10, fill=surf)
                if r == 0 and i == 0:
                    d.rounded_rectangle([x - 5, y - 5, x + cw + 5, y + ch + 5], 13, outline=acc, width=4)
        im.save(out, optimize=True)
        print("drew", out)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    if cmd == "write": write()
    elif cmd == "previews": previews()
    elif cmd == "check": sys.exit(0 if check() else 1)
    else: print(__doc__); sys.exit(2)
