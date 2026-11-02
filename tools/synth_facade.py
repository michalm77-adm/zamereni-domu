"""
Syntetická fotka fasády se známými rozměry (okna, dveře, terč T1) ve známé perspektivě.
Slouží k ověření automatického kótování. Výstup: obrázek + JSON se skutečnými rozměry.

Použití: python3 tools/synth_facade.py VYSTUP_PREFIX
"""
import json, sys
import cv2
import numpy as np

out = sys.argv[1]
RES = 200  # px/m textury fasády
# Rovina fasády v metrech: x doprava, y dolů, počátek levý horní roh zdi
W, H = 10.0, 6.0
M = 1.5  # okolí kolem zdi (obloha, terén)
tex = np.full((int((H + 2 * M) * RES), int((W + 2 * M) * RES)), 200, np.uint8)  # obloha
T = lambda x, y: (int((x + M) * RES), int((y + M) * RES))
cv2.rectangle(tex, T(-M, H), T(W + M, H + M), 90, -1)          # terén
cv2.rectangle(tex, T(0, 0), T(W, H), 225, -1)                    # omítka
rng = np.random.default_rng(5)
noise = rng.normal(0, 6, tex.shape)
openings = [  # (název, x, y, w, h) v metrech
    ("okno 1", 1.0, 1.2, 1.2, 1.5), ("okno 2", 3.4, 1.2, 1.2, 1.5),
    ("dveře", 5.6, 3.8, 1.0, 2.2), ("okno 3", 7.6, 1.2, 1.5, 1.5),
    ("okno 4", 7.6, 3.9, 1.5, 1.2),
]
for _, x, y, w, h in openings:
    cv2.rectangle(tex, T(x, y), T(x + w, y + h), 60, -1)
    cv2.rectangle(tex, T(x, y), T(x + w, y + h), 245, int(0.06 * RES))  # rám
tex = np.clip(tex + noise, 0, 255).astype(np.uint8)

# Terč T1 opřený o fasádu vpravo dole od dveří (levý horní roh desky v (2.2, 4.8) m)
D = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_100)
board = cv2.aruco.CharucoBoard((5, 5), 0.18, 0.135, D, np.arange(0, 12))
bx, by = 2.2, 4.8
side = int(0.9 * RES)
pad = int(0.05 * RES)
img = board.generateImage((side, side), marginSize=0, borderBits=1)
x0, y0 = T(bx, by)
tex[y0 - pad:y0 + side + pad, x0 - pad:x0 + side + pad] = 255
tex[y0:y0 + side, x0:x0 + side] = img

# Perspektiva: rovina [m] -> snímek 4000×3000
plane_quad = np.float32([[-1.0, -1.0], [11.0, -1.0], [11.0, 6.8], [-1.0, 6.8]])
img_quad = np.float32([[250, 300], [3750, 650], [3700, 2700], [300, 2850]])
P = cv2.getPerspectiveTransform(plane_quad, img_quad)
tex_to_plane = np.array([[1 / RES, 0, -M], [0, 1 / RES, -M], [0, 0, 1]])
photo = cv2.warpPerspective(tex, P @ tex_to_plane, (4000, 3000), flags=cv2.INTER_AREA, borderValue=200)
photo = cv2.GaussianBlur(photo, (3, 3), 0.8)
cv2.imwrite(out + ".jpg", photo, [cv2.IMWRITE_JPEG_QUALITY, 90])
truth = {"facade_m": [W, H], "openings": [dict(name=n, x=x, y=y, w=w, h=h) for n, x, y, w, h in openings],
         "target_origin_m": [bx, by]}
json.dump(truth, open(out + ".json", "w"), ensure_ascii=False, indent=1)
print("ok", out)
