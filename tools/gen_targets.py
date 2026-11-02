"""Generate ChArUco reference targets as vector PDFs at 1:1 scale + JSON spec."""
import json, sys, os
import cv2, numpy as np
from reportlab.pdfgen import canvas
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
FONT = "Helvetica"
for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]:
    if os.path.exists(p):
        pdfmetrics.registerFont(TTFont("DejaVu", p)); FONT = "DejaVu"

DICT_NAME = "DICT_4X4_100"
D = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_100)

TARGETS = [
  # name, page (w,h mm), squares (cols,rows), square mm, marker mm, board origin (x,y from top-left), first id
  dict(name="T1", kind="1x1m", page=(1000, 1000), sq=(5, 5), s=180.0, m=135.0, origin=(50, 50), id0=0),
  dict(name="T2", kind="1x1m", page=(1000, 1000), sq=(5, 5), s=180.0, m=135.0, origin=(50, 50), id0=12),
  dict(name="T3", kind="1x1m", page=(1000, 1000), sq=(5, 5), s=180.0, m=135.0, origin=(50, 50), id0=24),
  dict(name="A1", kind="A4", page=(210, 297), sq=(4, 6), s=42.0, m=31.5, origin=(21, 15), id0=36),
  dict(name="A2", kind="A4", page=(210, 297), sq=(4, 6), s=42.0, m=31.5, origin=(21, 15), id0=48),
  dict(name="A3", kind="A4", page=(210, 297), sq=(4, 6), s=42.0, m=31.5, origin=(21, 15), id0=60),
]

def board_for(t):
    n = (t["sq"][0] * t["sq"][1]) // 2
    ids = np.arange(t["id0"], t["id0"] + n, dtype=np.int32)
    return cv2.aruco.CharucoBoard(t["sq"], t["s"] / 1000, t["m"] / 1000, D, ids), ids

def draw(t):
    b, ids = board_for(t)
    W, H = t["page"]; ox, oy = t["origin"]; s = t["s"]; cols, rows = t["sq"]
    path = os.path.join(OUT, f"terc_{t['name']}_{t['kind']}.pdf")
    c = canvas.Canvas(path, pagesize=(W * mm, H * mm))
    c.setTitle(f"ChArUco terč {t['name']} ({t['kind']})")
    Y = lambda y: (H - y) * mm  # top-left mm -> PDF
    c.setFillColorRGB(0, 0, 0); c.setStrokeColorRGB(0, 0, 0)
    # chessboard: black where (r+c) even (OpenCV non-legacy pattern, verified)
    for r in range(rows):
        for cc in range(cols):
            if (r + cc) % 2 == 0:
                c.rect((ox + cc * s) * mm, Y(oy + (r + 1) * s), s * mm, s * mm, stroke=0, fill=1)
    # markers from board object points
    objp = b.getObjPoints()
    for mid, pts in zip(b.getIds().ravel(), objp):
        pts = np.array(pts).reshape(-1, 3) * 1000  # mm, board coords (x right, y down)
        x0, y0 = pts[:, 0].min(), pts[:, 1].min(); ms = pts[:, 0].max() - x0
        bits = cv2.aruco.generateImageMarker(D, int(mid), 6, borderBits=1)
        cell = ms / 6
        for i in range(6):
            for j in range(6):
                if bits[i, j] < 128:
                    c.rect((ox + x0 + j * cell) * mm, Y(oy + y0 + (i + 1) * cell), cell * mm + 0.02, cell * mm + 0.02, stroke=0, fill=1)
    # labels
    big = t["kind"] == "1x1m"
    fs = 9 if not big else 22
    by = oy + rows * s
    c.setFont(FONT, fs)
    line1 = (f"Terč {t['name']} · ChArUco {cols}×{rows} · pole {s:g} mm · marker {t['m']:g} mm · "
             f"{DICT_NAME} · ID {ids[0]}–{ids[-1]}")
    line2 = "TISK 1:1 – NEZMĚNŠOVAT · matný povrch · tuhá rovná deska" if big else "Tisk 1:1 (100 %, bez přizpůsobení stránce) · nalepit na tuhou desku"
    gap = 12 if big else 5
    c.drawString(ox * mm, Y(by + gap + fs * 0.35), line1)
    c.drawString(ox * mm, Y(by + gap + fs * 0.35 * 2.6), line2)
    # 100 mm control scale bar (bottom right)
    bar = 100.0
    bx1 = ox + cols * s; bx0 = bx1 - bar; byy = H - (8 if not big else 20)
    c.setLineWidth(0.4 if not big else 1.2)
    c.line(bx0 * mm, Y(byy), bx1 * mm, Y(byy))
    for x in (bx0, bx1):
        c.line(x * mm, Y(byy - (2 if not big else 5)), x * mm, Y(byy + (2 if not big else 5)))
    c.setFont(FONT, fs * 0.8)
    c.drawCentredString((bx0 + bar / 2) * mm, Y(byy - (1.5 if not big else 4)), "kontrola tisku: 100 mm")
    # corner crop marks of the board
    c.setLineWidth(0.25 if not big else 0.8)
    L = 4 if not big else 15
    for (x, y) in [(ox, oy), (ox + cols * s, oy), (ox, oy + rows * s), (ox + cols * s, oy + rows * s)]:
        c.line((x - L - 2) * mm, Y(y), (x - 2) * mm, Y(y)) if x == ox else c.line((x + 2) * mm, Y(y), (x + L + 2) * mm, Y(y))
    c.showPage(); c.save()
    return path, b, ids

spec = {"dictionary": DICT_NAME, "pattern": "OpenCV CharucoBoard, legacyPattern=False", "targets": []}
for t in TARGETS:
    path, b, ids = draw(t)
    spec["targets"].append(dict(name=t["name"], format=t["kind"], page_mm=t["page"], squares=t["sq"],
        square_mm=t["s"], marker_mm=t["m"], board_origin_mm=t["origin"], marker_ids=[int(ids[0]), int(ids[-1])],
        inner_corners=[(t["sq"][0]-1)*(t["sq"][1]-1)], file=os.path.basename(path)))
    print(path)
json.dump(spec, open(os.path.join(OUT, "terce_spec.json"), "w"), indent=2, ensure_ascii=False)
