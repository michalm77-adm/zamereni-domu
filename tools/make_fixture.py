"""
Vytvoří syntetický „snímek fasády“ s terčem T1 ve známé perspektivě, detekuje ho
v OpenCV a uloží testovací data pro jádro v Kotlinu (core/src/test/resources).

Použití:  python3 tools/make_fixture.py
Potřebuje: opencv-contrib-python-headless >= 4.7, numpy
"""
import json
from pathlib import Path

import cv2
import numpy as np

OUT = Path(__file__).resolve().parents[1] / "core/src/test/resources/fixture_t1_fasada.json"

D = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_100)
COLS, ROWS, SQ, MK = 5, 5, 0.180, 0.135
board = cv2.aruco.CharucoBoard((COLS, ROWS), SQ, MK, D, np.arange(0, 12))

# Kontrola, že číslování rohů odpovídá TargetSpec.cornerPosition v Kotlinu
for cid, p in enumerate(board.getChessboardCorners()):
    c, r = cid % (COLS - 1), cid // (COLS - 1)
    assert np.allclose(p[:2], [(c + 1) * SQ, (r + 1) * SQ]), (cid, p)

# Terč vykreslený v rozlišení 2000 px/m (okraj desky = počátek roviny)
RES = 2000
side = int(COLS * SQ * RES)
tex = board.generateImage((side, side), marginSize=0, borderBits=1)
tex_to_plane = np.array([[1 / RES, 0, 0], [0, 1 / RES, 0], [0, 0, 1]])

# Známá perspektiva: rovina fasády [m] -> snímek 4000×3000 px.
# Fasáda 10 × 6 m focená šikmo; terč leží u levého dolního rohu (posunutý o 0,3 m).
plane_quad = np.float32([[-0.3, -4.5], [9.7, -4.5], [9.7, 1.2], [-0.3, 1.2]])
img_quad = np.float32([[300, 350], [3700, 900], [3650, 2500], [350, 2850]])
plane_to_img = cv2.getPerspectiveTransform(plane_quad, img_quad)

photo = np.full((3000, 4000), 170, np.uint8)
warped = cv2.warpPerspective(tex, plane_to_img @ tex_to_plane, (4000, 3000),
                             flags=cv2.INTER_AREA, borderValue=255)
mask = cv2.warpPerspective(np.full_like(tex, 255), plane_to_img @ tex_to_plane, (4000, 3000))
# bílá deska s okrajem 5 cm kolem šachovnice
pad = int(0.05 * RES)
board_bg = cv2.warpPerspective(np.full((side + 2 * pad, side + 2 * pad), 255, np.uint8),
                               plane_to_img @ tex_to_plane @ np.array([[1, 0, -pad], [0, 1, -pad], [0, 0, 1]]),
                               (4000, 3000))
photo = np.where(board_bg > 0, 255, photo).astype(np.uint8)
photo = np.where(mask > 0, warped, photo).astype(np.uint8)
rng = np.random.default_rng(3)
photo = cv2.GaussianBlur(photo, (3, 3), 0.7)
photo = np.clip(photo + rng.normal(0, 3, photo.shape), 0, 255).astype(np.uint8)

corners, ids, _, _ = cv2.aruco.CharucoDetector(board).detectBoard(photo)
assert ids is not None and len(ids) == 16, ids


def project(p):
    v = plane_to_img @ np.array([p[0], p[1], 1.0])
    return [float(v[0] / v[2]), float(v[1] / v[2])]


# Měřené úseky v rovině fasády [m] -> pixely
segments = {
    "pres_terc": ([0.18, 0.18], [0.72, 0.72]),        # uvnitř terče
    "delka_fasady": ([-0.3, 1.1], [9.7, 1.1]),        # 10 m, velká extrapolace
    "vyska_u_terce": ([0.9, 1.2], [0.9, -4.5]),        # 5,7 m svisle
}
data = {
    "target": "T1",
    "image_size": [4000, 3000],
    "charuco_ids": [int(i) for i in ids.ravel()],
    "image_points": [[float(x), float(y)] for x, y in corners.reshape(-1, 2)],
    "segments": [
        {"name": k, "a_px": project(a), "b_px": project(b),
         "true_m": float(np.linalg.norm(np.subtract(a, b)))}
        for k, (a, b) in segments.items()
    ],
}
OUT.write_text(json.dumps(data, indent=1))
cv2.imwrite(str(OUT.with_suffix(".jpg")), photo, [cv2.IMWRITE_JPEG_QUALITY, 85])
print("zapsáno", OUT)
