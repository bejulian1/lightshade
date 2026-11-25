#!/usr/bin/env python3
"""Draws Lightshade's icons as PNG files with nothing but the standard library. Deterministic: the same
bytes every run. Every icon is painted with 6x6 supersampling and box-filtered down, so edges stay clean.

    python3 tools/icons.py        # writes packs/*/pack.png and the mod/GUI icons

    src/main/resources/assets/lightshade/icon.png                    64x64  mod icon (Mod Menu, launcher)
    src/main/resources/assets/lightshade/textures/gui/config-icon.png 32x32  Sodium sidebar
    src/main/resources/assets/lightshade/textures/gui/default_pack.png 64x64 packs without a pack.png
    packs/daylight/pack.png                                          64x64  Lightshade Daylight
    packs/aurora/pack.png                                            64x64  Lightshade Aurora
"""
from pathlib import Path
import math
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
SS = 6


def png(width, height, pixels):
    """pixels: list of rows of (r, g, b, a) tuples, 0..255."""
    raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in pixels)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(len(a)))


def smooth(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


def hash2(x, y, seed=0):
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xffffffff
    h = ((h ^ (h >> 13)) * 1274126177) & 0xffffffff
    return ((h ^ (h >> 16)) & 0xffff) / 65535.0


def render(size, shade):
    """shade(u, v) -> (r, g, b, a) in 0..1, u/v in 0..1 from the top left."""
    big = size * SS
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sy in range(SS):
                for sx in range(SS):
                    r, g, b, a = shade((x * SS + sx + 0.5) / big, (y * SS + sy + 0.5) / big)
                    acc[0] += r * a
                    acc[1] += g * a
                    acc[2] += b * a
                    acc[3] += a
            a = acc[3] / (SS * SS)
            if a > 0:
                px = tuple(int(round(max(0, min(1, c / acc[3])) * 255)) for c in acc[:3]) + (int(round(a * 255)),)
            else:
                px = (0, 0, 0, 0)
            row.append(px)
        rows.append(row)
    return rows


def rounded(u, v, radius=0.13):
    """Coverage of a rounded square tile with a 1/32 margin."""
    m = 1 / 32
    x, y = min(u - m, 1 - m - u), min(v - m, 1 - m - v)
    if x < 0 or y < 0:
        return 0.0
    if x < radius and y < radius:
        return 1.0 if math.hypot(radius - x, radius - y) <= radius else 0.0
    return 1.0


def rim(color, u, v, strength=0.20):
    """A thin light rim inside the tile edge: a crisp outline on dark and on light backgrounds."""
    m = 1 / 32
    x, y = min(u - m, 1 - m - u), min(v - m, 1 - m - v)
    r = 0.13
    edge = (r - math.hypot(r - x, r - y)) if (x < r and y < r) else min(x, y)
    return mix(color, (1, 1, 1), strength) if edge < 1 / 64 else color


def segment(px, py, ax, ay, bx, by):
    """Distance from p to the segment a-b, and the position 0..1 along it."""
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - ax - dx * t, py - ay - dy * t), t


def hill(u, base, amp, freq, phase):
    return base + amp * math.sin(u * freq + phase) + amp * 0.45 * math.sin(u * freq * 2.3 + phase * 1.7)


# ---- the Lightshade emblem: a sunbeam strikes a two-faceted prism. The face toward the sun is lit gold,
# the other lies in cool teal shade; a cyan beam leaves it and the prism casts its shadow on the ground.
APEX, LEFT, RIGHT = (0.47, 0.15), (0.20, 0.72), (0.74, 0.72)
RIDGE = (0.47, 0.72)
ENTRY = (APEX[0] + (LEFT[0] - APEX[0]) * 0.50, APEX[1] + (LEFT[1] - APEX[1]) * 0.50)
EXIT = (APEX[0] + (RIGHT[0] - APEX[0]) * 0.56, APEX[1] + (RIGHT[1] - APEX[1]) * 0.56)


def inside(u, v, a, b, c):
    def side(p, q):
        return (q[0] - p[0]) * (v - p[1]) - (q[1] - p[1]) * (u - p[0])
    s1, s2, s3 = side(a, b), side(b, c), side(c, a)
    return (s1 <= 0 and s2 <= 0 and s3 <= 0) or (s1 >= 0 and s2 >= 0 and s3 >= 0)


def emblem(u, v, mono=False):
    a = rounded(u, v)
    if a == 0:
        return (0, 0, 0, 0)

    def tone(c):
        if not mono:
            return c
        g = 0.30 * c[0] + 0.55 * c[1] + 0.15 * c[2]
        return (g * 0.92, g * 0.95, g * 1.02)
    # Deep slate, lighter behind the prism.
    color = mix((0.17, 0.21, 0.29), (0.055, 0.07, 0.10), smooth(0.05, 0.80, math.hypot(u - 0.42, v - 0.36)))
    # The ground: a darker band, and the prism's long shadow away from the sun.
    ground = 0.745
    if v > ground:
        color = mix((0.13, 0.16, 0.22), (0.08, 0.10, 0.14), smooth(ground, 0.97, v))
        color = mix(color, (0.24, 0.28, 0.36), smooth(ground + 0.014, ground, v))
        # The prism's shadow, long and soft, away from the sun.
        sd = math.hypot((u - 0.64) / 0.30, (v - 0.775) / 0.042)
        color = mix(color, (0.01, 0.012, 0.025), 0.85 * smooth(1.0, 0.40, sd))
    # The sunbeam, down from the upper left onto the lit face.
    d, t = segment(u, v, -0.08, 0.02, ENTRY[0], ENTRY[1])
    if not inside(u, v, APEX, LEFT, RIGHT):
        color = mix(color, tone((1.0, 0.70, 0.26)), 0.50 * smooth(0.10, 0.0, d) * (0.4 + 0.6 * t))
        color = mix(color, tone((1.0, 0.93, 0.70)), smooth(0.030, 0.014, d))
    # The beam that leaves the shaded face: cyan, widening gently toward the edge.
    if not inside(u, v, APEX, LEFT, RIGHT) and u > EXIT[0]:
        dx, dy = 1.0, 0.42
        n = math.hypot(dx, dy)
        along = ((u - EXIT[0]) * dx + (v - EXIT[1]) * dy) / n
        across = abs(-(u - EXIT[0]) * dy + (v - EXIT[1]) * dx) / n
        width = 0.020 + along * 0.11
        body = smooth(width, width * 0.35, across) * smooth(-0.01, 0.04, along)
        color = mix(color, tone((0.20, 0.62, 0.86)), 0.55 * smooth(width * 2.2, 0.0, across) * smooth(-0.01, 0.04, along))
        color = mix(color, tone((0.62, 0.94, 1.0)), body * (1.0 - 0.25 * smooth(0.1, 0.4, along)))
    # The prism: the sunward face gold, the far face in teal shade.
    if inside(u, v, APEX, LEFT, RIDGE):
        lit = mix((1.0, 0.86, 0.46), (0.86, 0.52, 0.18), smooth(0.15, 0.72, v) * 0.8 + smooth(0.30, 0.47, u) * 0.2)
        color = tone(lit)
    elif inside(u, v, APEX, RIDGE, RIGHT):
        shade = mix((0.16, 0.44, 0.54), (0.05, 0.17, 0.25), smooth(0.15, 0.72, v) * 0.7 + smooth(0.47, 0.70, u) * 0.3)
        color = tone(shade)
        d, t = segment(u, v, ENTRY[0] + 0.04, ENTRY[1], EXIT[0], EXIT[1])
        color = mix(color, tone((0.55, 0.90, 1.0)), 0.30 * smooth(0.06, 0.0, d))
    # Crisp edges: a warm highlight on the sun side, a cool one in the shade.
    e_left = segment(u, v, *APEX, *LEFT)[0]
    e_right = segment(u, v, *APEX, *RIGHT)[0]
    e_ridge = segment(u, v, *APEX, *RIDGE)[0]
    color = mix(color, tone((1.0, 0.96, 0.82)), smooth(0.016, 0.006, e_left))
    color = mix(color, tone((0.55, 0.90, 1.0)), smooth(0.014, 0.005, e_right) * 0.9)
    color = mix(color, tone((1.0, 0.93, 0.74)), smooth(0.010, 0.003, e_ridge) * 0.55)
    return rim(color, u, v, 0.16) + (a,)


def daylight(u, v):
    a = rounded(u, v)
    if a == 0:
        return (0, 0, 0, 0)
    # Clear midday sky: deep blue overhead to a pale warm horizon.
    color = mix((0.16, 0.45, 0.86), (0.66, 0.84, 0.98), smooth(0.04, 0.60, v))
    color = mix(color, (1.0, 0.94, 0.80), smooth(0.44, 0.62, v) * 0.45)
    # Sun with a soft warm halo.
    sx, sy = 0.70, 0.24
    d = math.hypot(u - sx, v - sy)
    color = mix(color, (1.0, 0.93, 0.72), smooth(0.34, 0.0, d) * 0.55)
    color = mix(color, (1.0, 0.98, 0.90), smooth(0.088, 0.078, d))
    # Two soft clouds: bright tops, cool grey-blue bellies.
    for cx, cy, s in ((0.27, 0.30, 1.0), (0.52, 0.45, 0.72)):
        lobes = max(smooth(0.085 * s, 0.065 * s, math.hypot(u - cx, (v - cy) * 1.5)),
                    smooth(0.070 * s, 0.050 * s, math.hypot(u - cx - 0.075 * s, (v - cy + 0.022 * s) * 1.4)),
                    smooth(0.060 * s, 0.040 * s, math.hypot(u - cx + 0.075 * s, (v - cy - 0.008 * s) * 1.6)))
        flat = smooth(cy + 0.040 * s, cy + 0.028 * s, v)
        tone = mix((1.0, 1.0, 1.0), (0.76, 0.84, 0.95), smooth(cy - 0.03 * s, cy + 0.04 * s, v))
        color = mix(color, tone, lobes * flat)
    # Far blue hills, then sunlit grass.
    far = hill(u, 0.60, 0.030, 7.0, 0.6)
    if v > far:
        color = mix((0.44, 0.62, 0.76), (0.36, 0.54, 0.66), smooth(far, far + 0.08, v))
    near = hill(u, 0.69, 0.040, 5.0, 2.1)
    if v > near:
        color = mix((0.50, 0.80, 0.32), (0.30, 0.60, 0.22), smooth(near, near + 0.05, v))
        if int(u * 16) % 3 == 0 and v < near + 0.045:
            color = mix(color, (0.60, 0.86, 0.36), 0.45)
    # Turquoise water with a sun glint.
    if v > 0.80:
        w = mix((0.20, 0.72, 0.78), (0.08, 0.44, 0.62), smooth(0.80, 0.97, v))
        glint = smooth(0.07, 0.0, abs(u - sx)) * (0.5 + 0.5 * math.sin(v * 120))
        color = mix(w, (1.0, 0.97, 0.86), glint * 0.75)
        if v < 0.815:
            color = mix(color, (0.92, 0.97, 0.98), 0.6)
    return rim(color, u, v) + (a,)


def aurora_sky(u, v, stars=True):
    color = mix((0.025, 0.035, 0.12), (0.10, 0.09, 0.30), smooth(0.0, 0.72, v))
    color = mix(color, (0.30, 0.20, 0.36), smooth(0.50, 0.70, v) * 0.40)  # last of the twilight
    if stars and v < 0.60:
        sx, sy = int(u * 64), int(v * 64)
        if hash2(sx, sy, 3) > 0.972:
            color = mix(color, (0.92, 0.95, 1.0), 0.45 + 0.55 * hash2(sx, sy, 9))
    # Two curtains of emerald and teal light with a faint violet hem.
    for k, (base, amp, freq, phase, tint) in enumerate([
            (0.38, 0.065, 5.5, 0.3, (0.18, 0.96, 0.52)),
            (0.28, 0.050, 7.5, 2.0, (0.18, 0.82, 0.86))]):
        h = v - (base + amp * math.sin(u * freq + phase))
        body = smooth(-0.22, 0.0, h) * smooth(0.035, -0.005, h)
        rays = 0.62 + 0.38 * math.sin(u * 46 + k * 1.3) ** 2
        color = mix(color, tint, body * rays * (0.85 if k == 0 else 0.70))
        color = mix(color, (0.50, 0.30, 0.80), smooth(0.04, 0.0, abs(h - 0.02)) * 0.16)
    return color


def aurora(u, v):
    a = rounded(u, v)
    if a == 0:
        return (0, 0, 0, 0)
    color = aurora_sky(u, v)
    # Dark peaks with snow caps against the glow.
    ridge = 0.68 - 0.17 * max(0.0, 1 - abs(u - 0.32) / 0.28) - 0.11 * max(0.0, 1 - abs(u - 0.78) / 0.22)
    if v > ridge:
        color = (0.04, 0.05, 0.10)
        cap = max(0.06 * (1 - abs(u - 0.32) / 0.11), 0.045 * (1 - abs(u - 0.78) / 0.08))
        if v < ridge + cap:
            color = mix((0.58, 0.68, 0.88), (0.80, 0.87, 0.98), smooth(0.36, 0.26, u))
    # Still water mirrors the lights.
    if v > 0.83:
        refl = aurora_sky(u, 0.83 - (v - 0.83) * 2.2, stars=False)
        color = mix((0.02, 0.03, 0.08), refl, 0.70 * (0.75 + 0.25 * math.sin(v * 140)))
    return rim(color, u, v) + (a,)


def write(path, size, shade):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = png(size, size, render(size, shade))
    if not path.exists() or path.read_bytes() != data:
        path.write_bytes(data)
    return path


def main():
    gui = ROOT / 'src/main/resources/assets/lightshade'
    out = [
        write(ROOT / 'packs/daylight/pack.png', 64, daylight),
        write(ROOT / 'packs/aurora/pack.png', 64, aurora),
        write(gui / 'textures/gui/default_pack.png', 64, lambda u, v: emblem(u, v, mono=True)),
        write(gui / 'textures/gui/config-icon.png', 32, emblem),
        write(gui / 'icon.png', 64, emblem),
    ]
    for path in out:
        print(path.relative_to(ROOT))


if __name__ == '__main__':
    main()
