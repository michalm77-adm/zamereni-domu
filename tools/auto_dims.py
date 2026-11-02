"""
Referenční implementace automatického kótování (režim A) v Pythonu.
Stejný postup je přenesený do Android aplikace (AutoDimensions.kt).

1. Homografie snímek -> rovina terče [m]
2. Narovnaný pohled na fasádu (ortofoto) v měřítku S px/m
3. Otvory (okna, dveře): tmavé obdélníky ve světlé zdi -> kontury -> obdélníky
4. Obrys fasády: nejdelší svislé a vodorovné hrany (Hough)

Použití: python3 tools/auto_dims.py SNIMEK.jpg [PRAVDA.json]
"""
import json, sys
import cv2
import numpy as np

S = 100.0          # px na metr v narovnaném pohledu
MAX_HALF = 15.0    # max. vzdálenost od terče [m], dál se nenarovnává

D = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_100)
board = cv2.aruco.CharucoBoard((5, 5), 0.18, 0.135, D, np.arange(0, 12))


def rectify(gray):
    c, ids, _, _ = cv2.aruco.CharucoDetector(board).detectBoard(gray)
    obj = board.getChessboardCorners()[ids.ravel()][:, :2]
    Hm, _ = cv2.findHomography(c.reshape(-1, 2), obj, 0)
    h, w = gray.shape
    corners = cv2.perspectiveTransform(np.float32([[[0, 0]], [[w, 0]], [[w, h]], [[0, h]]]), Hm).reshape(-1, 2)
    lo = np.maximum(corners.min(0), [-MAX_HALF, -MAX_HALF])
    hi = np.minimum(corners.max(0), [MAX_HALF, MAX_HALF])
    size = np.ceil((hi - lo) * S).astype(int)
    A = np.array([[S, 0, -lo[0] * S], [0, S, -lo[1] * S], [0, 0, 1]])
    ortho = cv2.warpPerspective(gray, A @ Hm, tuple(size), flags=cv2.INTER_LINEAR, borderValue=0)
    valid = cv2.warpPerspective(np.full_like(gray, 255), A @ Hm, tuple(size), flags=cv2.INTER_NEAREST)
    return ortho, valid, lo  # pixel (u,v) -> metr (lo + (u,v)/S)


def openings(ortho, valid, lo):
    """Tmavé obdélníky ve světlé zdi o velikosti 0,4–3,5 m."""
    blur = cv2.GaussianBlur(ortho, (5, 5), 0)
    k = int(1.5 * S) | 1
    bw = cv2.adaptiveThreshold(blur, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, k, 15)
    bw[valid == 0] = 0
    bw = cv2.morphologyEx(bw, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    cnts, _ = cv2.findContours(bw, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    out = []
    for c in cnts:
        x, y, w, h = cv2.boundingRect(c)
        wm, hm = w / S, h / S
        if not (0.4 <= wm <= 3.5 and 0.4 <= hm <= 3.5):
            continue
        fill = cv2.contourArea(c) / (w * h)
        if fill < 0.85:
            continue
        out.append((lo[0] + x / S, lo[1] + y / S, wm, hm))
    return sorted(out)


def wall_mask(ortho, valid, lo, board_m=0.9):
    """Plocha zdi: oblast spojitě navazující na okolí terče (terč je opřený o zeď).
    Plovoucí flood fill sleduje pozvolné změny jasu (stíny), zastaví se na hranách."""
    blur = cv2.GaussianBlur(ortho, (9, 9), 0)
    edges = cv2.Canny(blur, 10, 30)
    edges = cv2.dilate(edges, np.ones((5, 5), np.uint8))
    h, w = blur.shape
    # Hrany a oblast mimo snímek jsou bariéry; vyplňuje se jen souvislá plocha bez hran
    barrier = np.zeros((h + 2, w + 2), np.uint8)
    barrier[1:-1, 1:-1] = np.where((edges > 0) | (valid == 0), 1, 0)
    mask = barrier.copy()
    off = 0.2  # m za okrajem desky
    seeds = [(-off, board_m / 2), (board_m + off, board_m / 2), (board_m / 2, -off), (board_m / 2, board_m + off)]
    for sx, sy in seeds:
        u, v = int((sx - lo[0]) * S), int((sy - lo[1]) * S)
        if 0 <= u < w and 0 <= v < h and mask[v + 1, u + 1] == 0:
            cv2.floodFill(blur, mask, (u, v), 0, 255, 255, 4 | cv2.FLOODFILL_MASK_ONLY | (255 << 8))
    mask = np.where(mask == 255, 255, 0).astype(np.uint8)
    mask = cv2.dilate(mask, np.ones((5, 5), np.uint8))  # vrátit šířku bariéry
    m = mask[1:-1, 1:-1]
    return m


def wall_holes(m, lo, board_m=0.9):
    """Otvory = díry v ploše zdi (včetně rámu), velikost 0,4–3,5 m, mimo terč."""
    cnts, hier = cv2.findContours(m, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
    if hier is None:
        return None, []
    outer = max((i for i in range(len(cnts)) if hier[0][i][3] < 0), key=lambda i: cv2.contourArea(cnts[i]))
    holes = []
    for i, c in enumerate(cnts):
        if hier[0][i][3] != outer:
            continue
        x, y, w, h = cv2.boundingRect(c)
        xm, ym, wm, hm = lo[0] + x / S, lo[1] + y / S, w / S, h / S
        if not (0.4 <= wm <= 3.5 and 0.4 <= hm <= 3.5):
            continue
        if xm < board_m + 0.1 and xm + wm > -0.1 and ym < board_m + 0.1 and ym + hm > -0.1:
            continue  # terč
        holes.append((xm, ym, wm, hm))
    # otvory dotýkající se okraje zdi (dveře u terénu) nejsou díry -> doplní je openings()
    return cnts[outer], sorted(holes)


def merge_openings(holes, dark):
    """Díry ve zdi mají přednost (zahrnují rám); tmavé obdélníky doplní otvory na okraji zdi (dveře)."""
    def overlap(a, b):
        return not (a[0] + a[2] < b[0] or b[0] + b[2] < a[0] or a[1] + a[3] < b[1] or b[1] + b[3] < a[1])
    return sorted(holes + [d for d in dark if not any(overlap(d, h) for h in holes)])


def outline(wall_contour, lo):
    """Obrys zdi jako mnohoúhelník [m] (u štítu vyjde pětiúhelník) a jeho obdélníkové rozměry."""
    if wall_contour is None:
        return None, None
    poly = cv2.approxPolyDP(wall_contour, 0.05 * S, True).reshape(-1, 2)
    x, y, w, h = cv2.boundingRect(wall_contour)
    poly_m = [(lo[0] + px / S, lo[1] + py / S) for px, py in poly]
    return (lo[0] + x / S, lo[1] + y / S, w / S, h / S), poly_m


if __name__ == "__main__":
    g = cv2.imread(sys.argv[1], 0)
    ortho, valid, lo = rectify(g)
    m = wall_mask(ortho, valid, lo)
    wall, ops = wall_holes(m, lo)
    ops = merge_openings(ops, openings(ortho, valid, lo))
    ol, poly = outline(wall, lo)
    print("mnohoúhelník zdi [m]:", [(round(a, 2), round(b, 2)) for a, b in poly or []])
    print("obrys (x, y, š, v) [m]:", None if ol is None else tuple(round(v, 3) for v in ol))
    for o in ops:
        print("otvor (x, y, š, v) [m]:", tuple(round(v, 3) for v in o))
    if len(sys.argv) > 2:
        t = json.load(open(sys.argv[2]))
        ox, oy = t["target_origin_m"]
        print("skutečnost obrys:", (-ox, -oy, *t["facade_m"]))
        for o in t["openings"]:
            print("skutečnost", o["name"], (round(o["x"] - ox, 3), round(o["y"] - oy, 3), o["w"], o["h"]))
    vis = cv2.cvtColor(ortho, cv2.COLOR_GRAY2BGR)
    for x, y, w, h in ops:
        cv2.rectangle(vis, (int((x - lo[0]) * S), int((y - lo[1]) * S)), (int((x - lo[0] + w) * S), int((y - lo[1] + h) * S)), (0, 0, 255), 2)
    if ol:
        x, y, w, h = ol
        cv2.rectangle(vis, (int((x - lo[0]) * S), int((y - lo[1]) * S)), (int((x - lo[0] + w) * S), int((y - lo[1] + h) * S)), (255, 0, 0), 2)
    cv2.imwrite(sys.argv[1].replace(".jpg", "_auto.jpg"), vis)
