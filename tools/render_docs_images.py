#!/usr/bin/env python3
"""Renders the README images in docs/images/. Reproducible; never starts Minecraft.

    python3 tools/render_docs_images.py            # all four images
    python3 tools/render_docs_images.py sky ui     # only some: banner, sky, ui

    docs/images/banner.png            1280x420  hero banner
    docs/images/sky-comparison.png    1280x520  offline sky render, Daylight vs Aurora
    docs/images/ui-pack-selector.png  1200x680  UI preview of LightshadePackScreen
    docs/images/ui-settings.png       1200x680  UI preview of LightshadeSettingsScreen

The sky images are a CPU port of the packs' own sky code (packs/common/shaders/include/atmospheric.glsl:
skyRadiance, aurora and starField; display.fsh: shoulder, saturation, vibrance, contrast and vignette)
evaluated with each pack's default options from its lightshade.json. They show the sky dome only: no
terrain, clouds, bloom or light shafts, so they are offline renders, not in-game screenshots.

The UI previews redo the layout arithmetic of LightshadePackScreen and LightshadeSettingsScreen at a
600x340 GUI (scale 2) with the real colours, texts from en_us.json and the real pack icons. The menu
background is a blurred offline sky; the text uses a small pixel font drawn for this script.

Needs Pillow (pip install pillow). The banner uses Lato or DejaVu Sans when installed.
"""
from pathlib import Path
from multiprocessing import Pool
import json
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import icons  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/images'
LANG = json.loads((ROOT / 'src/main/resources/assets/lightshade/lang/en_us.json').read_text(encoding='utf-8'))


def manifest(pack):
    return json.loads((ROOT / f'packs/{pack}/lightshade.json').read_text(encoding='utf-8'))


# =====================================================================================================
# The sky: a line-by-line port of atmospheric.glsl for the overworld, in linear light.
# =====================================================================================================

def fract(x):
    return x - math.floor(x)


def sat(x):
    return 0.0 if x < 0.0 else 1.0 if x > 1.0 else x


def smoothstep(e0, e1, x):
    t = sat((x - e0) / (e1 - e0))
    return t * t * (3.0 - 2.0 * t)


def mixf(a, b, t):
    return a + (b - a) * t


def mixv(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def scale(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def mul(a, b):
    return (a[0] * b[0], a[1] * b[1], a[2] * b[2])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def normalize(a):
    n = math.sqrt(dot(a, a)) or 1.0
    return (a[0] / n, a[1] / n, a[2] / n)


def luminance(c):
    return c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722


def to_linear(c):
    return tuple(x / 12.92 if x < 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c)


def to_display(c):
    return tuple(x * 12.92 if x < 0.0031308 else 1.055 * x ** (1 / 2.4) - 0.055 for x in (max(v, 0.0) for v in c))


def hash_value(x, y, z):
    x, y, z = fract(x * 0.1031), fract(y * 0.1099), fract(z * 0.0973)
    d = x * (z + 19.19) + y * (x + 19.19) + z * (y + 19.19)
    x, y, z = x + d, y + d, z + d
    return fract((x + y) * z)


def smooth_noise(p):
    ix, iy, iz = math.floor(p[0]), math.floor(p[1]), math.floor(p[2])
    fx, fy, fz = p[0] - ix, p[1] - iy, p[2] - iz
    fx, fy, fz = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy), fz * fz * (3 - 2 * fz)

    def h(a, b, c):
        return hash_value(ix + a, iy + b, iz + c)
    return mixf(mixf(mixf(h(0, 0, 0), h(1, 0, 0), fx), mixf(h(0, 1, 0), h(1, 1, 0), fx), fy),
                mixf(mixf(h(0, 0, 1), h(1, 0, 1), fx), mixf(h(0, 1, 1), h(1, 1, 1), fx), fy), fz)


class Sky:
    """One pack, one moment: the frame uniforms and the pack's options."""

    def __init__(self, pack, sun, time=0.0, native_sky=(0x78 / 255, 0xA7 / 255, 0xFF / 255), moon_phase=0.0):
        data = manifest(pack)
        self.o = {k: v['default'] for k, v in data['options'].items()}
        self.aurora_pack = pack == 'aurora'
        self.sun = normalize(sun)
        self.moon = scale(self.sun, -1.0)
        self.time = time
        self.native = native_sky
        self.moon_phase = moon_phase

    def opt(self, key, fallback):
        return float(self.o.get(key, fallback))

    def daylight(self):
        return smoothstep(-0.14, 0.16, self.sun[1])

    def twilight(self):
        a = self.sun[1]
        return smoothstep(-0.20, -0.025, a) * (1.0 - smoothstep(0.10, 0.40, a))

    def sunlight_color(self):
        return mixv((1.0, 0.60, 0.30), (1.0, 0.905, 0.716), smoothstep(0.02, 0.40, self.sun[1]))

    def aurora(self, ray):
        north = smoothstep(0.02, 0.35, -ray[2])
        clear = (1.0 - self.daylight()) * smoothstep(0.04, 0.16, ray[1]) * north
        if clear <= 0.001:
            return (0.0, 0.0, 0.0)
        s = (0.0, 0.0, 0.0)
        t = self.time
        for layer in (0, 1):
            height = 17000.0 if layer == 0 else 21000.0
            px = ray[0] * (height / max(ray[1] + 0.18, 0.10))
            u = px * 0.00011 + t * (0.0040 if layer == 0 else -0.0028) + layer * 3.1
            base = 0.42 + 0.10 * math.sin(u * 1.7 + layer) + 0.05 * math.sin(u * 4.3 - t * 0.006)
            h = ray[1] * (1.0 if layer == 0 else 0.86) - base * 0.55
            curtain = smoothstep(-0.035, 0.0, h) * math.exp(-max(h, 0.0) * (9.0 if layer == 0 else 12.0))
            rays = 0.55 + 0.45 * (0.5 + 0.5 * math.sin(u * 23.0 + math.sin(u * 5.0 + t * 0.05) * 2.4)) ** 2
            shimmer = 0.85 + 0.15 * math.sin(t * 0.7 + u * 40.0)
            tint = mixv((0.015, 0.20, 0.085), (0.010, 0.13, 0.15), sat(h * 14.0))
            s = add(s, scale(tint, curtain * rays * shimmer * (1.0 if layer == 0 else 0.55)))
            s = add(s, scale((0.035, 0.006, 0.050), math.exp(-abs(h + 0.012) * 70.0) * 0.35))
        return scale(s, clear * 0.55)

    def radiance(self, ray, celestial=True):
        day, altitude = self.daylight(), self.sun[1]
        h = max(ray[1], 0.0)
        cos_sun = dot(ray, self.sun)
        night = self.opt('LIGHT_NIGHT', 1.0)
        if self.aurora_pack:
            zenith_day, horizon_day = (0.070, 0.190, 0.500), (0.440, 0.600, 0.780)
        else:
            biome = to_linear(self.native)
            zenith_day = mixv(mul(biome, (0.55, 0.70, 0.98)), (0.075, 0.200, 0.510), 0.40)
            horizon_day = mixv(add(scale(biome, 1.10), (0.03, 0.03, 0.03)), (0.460, 0.610, 0.780), 0.55)
        zenith = mixv(scale((0.0009, 0.0017, 0.0050), night), zenith_day, day)
        horizon = mixv(scale((0.0055, 0.0095, 0.021), night), horizon_day, day)
        c = mixv(horizon, zenith, h ** 0.52)
        c = add(c, scale((0.30, 0.29, 0.25), sat(cos_sun * 0.5 + 0.5) ** 8.0 * 0.22 * day * (1.0 - 0.5 * h)))
        tw = self.twilight() * sat(self.opt('SKY_GOLDEN', 0.0))
        if tw > 0.0:
            sunward = sat(cos_sun * 0.5 + 0.5) ** 2.2
            low, mid = math.exp(-h * 7.5), math.exp(-h * 4.2)
            lit = smoothstep(-0.20, 0.05, altitude)
            overhead = mixv((0.008, 0.014, 0.055), (0.045, 0.085, 0.300), lit)
            apricot = scale((0.92, 0.52, 0.36), lit)
            amber = scale((1.00, 0.56, 0.21), lit)
            antisolar = scale((0.13, 0.16, 0.31), lit)
            dusk = mixv(overhead, mixv(antisolar, apricot, sunward), mid * 0.80)
            dusk = mixv(dusk, mixv(scale(antisolar, 1.25), amber, sunward), low * mixf(0.30, 1.0, sunward))
            c = mixv(c, dusk, tw)
        c = scale(c, mixf(1.0, 0.78, sat(-ray[1] * 4.0)))
        glow_height = smoothstep(-0.06, 0.03, altitude)
        halo = mixv((1.0, 0.93, 0.82), (1.0, 0.66, 0.34), self.twilight())
        around = sat(cos_sun)
        sun_glow = self.opt('SUN_GLOW', 1.0)
        c = add(c, scale(halo, (around ** 10.0 * mixf(0.030, 0.075, tw) + around ** 120.0 * mixf(0.10, 0.24, tw)) * glow_height * sun_glow))
        if celestial and self.opt('SKY_AURORA', 0.0) > 0.0:
            c = add(c, scale(self.aurora(ray), self.opt('SKY_AURORA', 0.0) * night))
        if not celestial:
            return c
        md = dot(ray, self.moon)
        sun_size = min(max(self.opt('SUN_SIZE', 1.0), 0.25), 4.0)
        if self.aurora_pack:
            grown = sun_size * mixf(1.0, 1.30, self.twilight())
            disc = smoothstep(math.cos(0.0175 * grown), math.cos(0.0145 * grown), cos_sun)
            aureole = around ** (700.0 / grown) * 0.30 + around ** (5000.0 / (grown * grown)) * 0.9
            hot = mixv((1.0, 0.94, 0.82), (1.0, 0.68, 0.36), self.twilight())
            c = add(c, scale(hot, (disc * 12.0 + aureole) * sun_glow * smoothstep(-0.045, 0.005, altitude)))
        else:
            disc = smoothstep(math.cos(0.0175 * sun_size), math.cos(0.0145 * sun_size), cos_sun)
            aureole = around ** 64.0 * 0.04 + around ** (640.0 / sun_size) * 0.16
            c = add(c, scale(self.sunlight_color(), (disc * 3.5 + aureole) * smoothstep(-0.045, 0.005, altitude)))
        moon_size = min(max(self.opt('MOON_SIZE', 1.0), 0.25), 4.0)
        if smoothstep(math.cos(0.0145 * moon_size), math.cos(0.0125 * moon_size), md) > 0.0 and day < 1.0:
            c = add(c, scale((0.36, 0.41, 0.50), (1.0 - day)))
        if self.aurora_pack:
            moon_up = smoothstep(-0.02, 0.08, self.moon[1])
            c = add(c, scale((0.56, 0.70, 1.0), (max(md, 0.0) ** (160.0 / moon_size) * 0.012 + max(md, 0.0) ** 24.0 * 0.0035) * (1.0 - day) * moon_up * night))
        return add(c, self.stars(ray))

    def stars(self, ray):
        visible = (1.0 - self.daylight()) * smoothstep(0.0, 0.20, ray[1])
        if visible <= 0.001:
            return (0.0, 0.0, 0.0)
        if not self.aurora_pack:
            cx, cy, cz = math.floor(ray[0] * 320.0), math.floor(ray[1] * 320.0), math.floor(ray[2] * 320.0)
            q = (fract(ray[0] * 320.0) - 0.5, fract(ray[1] * 320.0) - 0.5, fract(ray[2] * 320.0) - 0.5)
            intensity = math.exp(-dot(q, q) * 58.0) * (1.0 if hash_value(cx, cy, cz) >= 0.995 else 0.0)
            return scale(mixv((0.40, 0.61, 0.92), (0.85, 0.64, 0.37), hash_value(cx + 41, cy + 41, cz + 41)), intensity * visible)
        s = (0.0, 0.0, 0.0)
        for layer in (0, 1):
            k = 230.0 if layer == 0 else 520.0
            cx, cy, cz = math.floor(ray[0] * k), math.floor(ray[1] * k), math.floor(ray[2] * k)
            chance = hash_value(cx, cy, cz)
            threshold = 0.9935 if layer == 0 else 0.982
            if chance < threshold:
                continue
            q = (fract(ray[0] * k) - 0.5, fract(ray[1] * k) - 0.5, fract(ray[2] * k) - 0.5)
            off = ((hash_value(cx + 3, cy + 3, cz + 3) - 0.5) * 0.55, (hash_value(cx + 7, cy + 7, cz + 7) - 0.5) * 0.55,
                   (hash_value(cx + 11, cy + 11, cz + 11) - 0.5) * 0.55)
            size = 44.0 if layer == 0 else 80.0
            power = (chance - threshold) / (1.0 - threshold)
            tone = mixv((0.46, 0.64, 1.0), (1.0, 0.74, 0.46), hash_value(cx + 41, cy + 41, cz + 41))
            tone = mixv((0.85, 0.85, 0.85), tone, 0.7)
            d = (q[0] - off[0]) ** 2 + (q[1] - off[1]) ** 2 + (q[2] - off[2]) ** 2
            s = add(s, scale(tone, math.exp(-d * size) * mixf(0.10, 1.9 if layer == 0 else 0.34, power * power)))
        s = scale(s, visible)
        if self.opt('SKY_MILKY_WAY', 0.0) > 0.0:
            pole = normalize((0.42, 0.34, 0.84))
            latitude = dot(ray, pole) + (smooth_noise(add(scale(ray, 3.0), (5, 5, 5))) - 0.5) * 0.10
            band, core = math.exp(-latitude * latitude * 11.0), math.exp(-latitude * latitude * 70.0)
            cloud = smooth_noise(scale(ray, 5.0)) * 0.50 + smooth_noise(add(scale(ray, 11.0), (2, 2, 2))) * 0.30 + smooth_noise(add(scale(ray, 23.0), (4, 4, 4))) * 0.20
            dust = smoothstep(0.42, 0.64, smooth_noise(add(scale(ray, 7.0), (9, 9, 9))) * 0.62 + smooth_noise(add(scale(ray, 17.0), (1, 1, 1))) * 0.38)
            glow = mixv((0.11, 0.15, 0.30), (0.40, 0.32, 0.28), core)
            s = add(s, scale(glow, band * (0.22 + 1.25 * cloud * cloud) * (1.0 - dust * core * 0.85) * 0.11 * visible * self.opt('SKY_MILKY_WAY', 0.0)))
        return s

    def display(self, c, uv=(0.5, 0.5)):
        """display.fsh without bloom, light shafts and dither."""
        c = scale(c, self.opt('EXPOSURE', 1.0))
        if self.aurora_pack:
            burn = max(c)
            c = mixv(c, (burn, burn, burn), smoothstep(3.5, 13.0, burn))
        peak = max(c)
        if peak > 0.72:
            mapped = 0.72 + 0.28 * (1.0 - math.exp(-(peak - 0.72) / 0.28))
            c = scale(c, mapped / peak)
        lum, peak = luminance(c), max(c)
        chroma = (peak - min(c)) / peak if peak > 1e-5 else 0.0
        c = mixv((lum, lum, lum), c, self.opt('SATURATION', 1.0) * (1.0 + self.opt('VIBRANCE', 0.0) * (1.0 - chroma)))
        d = to_display(tuple(min(max(v, 0.0), 1.0) for v in c))
        k = self.opt('CONTRAST', 0.0)
        d = tuple(v + (v * v * (3 - 2 * v) - v) * k for v in d)
        if self.aurora_pack:
            qx, qy = uv[0] - 0.5, uv[1] - 0.5
            d = scale(d, 1.0 - self.opt('VIGNETTE', 0.0) * (qx * qx + qy * qy) * 1.6)
        return tuple(min(max(v, 0.0), 1.0) for v in d)


def sun_at(altitude_deg, azimuth_deg):
    """Azimuth 0 = north (-Z), 90 = east (+X)."""
    a, z = math.radians(altitude_deg), math.radians(azimuth_deg)
    return (math.cos(a) * math.sin(z), math.sin(a), -math.cos(a) * math.cos(z))


def camera_ray(px, py, width, height, yaw_deg, pitch_deg, hfov_deg):
    t = math.tan(math.radians(hfov_deg) / 2)
    x = (2 * (px + 0.5) / width - 1) * t
    y = (1 - 2 * (py + 0.5) / height) * t * height / width
    d = normalize((x, y, -1.0))
    p = math.radians(pitch_deg)
    d = (d[0], d[1] * math.cos(p) - d[2] * math.sin(p), d[1] * math.sin(p) + d[2] * math.cos(p))
    w = math.radians(yaw_deg)
    return normalize((d[0] * math.cos(w) - d[2] * math.sin(w), d[1], d[0] * math.sin(w) + d[2] * math.cos(w)))


def ridge(yaw, base, amp, seed):
    """An illustrative horizon silhouette, in radians of elevation, as a function of azimuth."""
    return (base + amp * (0.55 * math.sin(yaw * 3.1 + seed) + 0.30 * math.sin(yaw * 7.3 + seed * 2.1)
                          + 0.15 * math.sin(yaw * 17.0 + seed * 0.7)))


def render_row(job):
    kind, args, y = job
    sky = Sky(**args['sky'])
    w, h = args['size']
    row = []
    for x in range(w):
        ray = camera_ray(x, y, w, h, args['yaw'], args['pitch'], args['fov'])
        color = sky.radiance(ray)
        if args.get('ground'):
            # Illustrative hills: a far ridge tinted by the sky's own horizon haze, a darker near one.
            yaw = math.atan2(ray[0], -ray[2])
            elevation = math.asin(max(-1.0, min(1.0, ray[1])))
            base, amp = args['ground']
            far = ridge(yaw, base, amp, 1.3)
            near = ridge(yaw * 1.7, base * 0.35, amp * 0.9, 4.2)
            haze = sky.radiance(normalize((ray[0], 0.02, ray[2])), celestial=False)
            if elevation < far:
                land = mixv(scale(haze, 0.62), scale(haze, 0.42), smoothstep(far, far - 0.06, elevation))
                color = mixv(color, land, smoothstep(far + 0.0015, far - 0.0015, elevation))
            if elevation < near:
                land = mixv(scale(haze, 0.26), scale(haze, 0.14), smoothstep(near, near - 0.08, elevation))
                color = mixv(color, land, smoothstep(near + 0.0015, near - 0.0015, elevation))
            if args.get('green') and elevation < far:
                land = mixv((0.30, 0.42, 0.24), (0.16, 0.26, 0.12), smoothstep(far, far - 0.25, elevation))
                color = mixv(color, land, 0.75 if elevation < near else 0.35)
        row.append(sky.display(color, (x / w, y / h)))
    return y, row


def render_view(size, **args):
    args['size'] = size
    with Pool(os.cpu_count() or 4) as pool:
        rows = dict(pool.map(render_row, [('view', args, y) for y in range(size[1])], chunksize=4))
    image = Image.new('RGB', size)
    image.putdata([tuple(int(round(v * 255)) for v in px) for y in range(size[1]) for px in rows[y]])
    return image


# =====================================================================================================
# Drawing helpers: TrueType text for the banner and the captions, and a pixel font for the GUI.
# =====================================================================================================

FONT_DIRS = ['/usr/share/fonts', '/usr/local/share/fonts', str(Path.home() / '.local/share/fonts'),
             str(Path.home() / '.fonts'), '/Library/Fonts', 'C:/Windows/Fonts']


def ttf(names, size):
    for directory in FONT_DIRS:
        for name in names:
            for path in Path(directory).rglob(name) if Path(directory).is_dir() else []:
                return ImageFont.truetype(str(path), size)
    return ImageFont.load_default(size)


def heavy(size):
    return ttf(['Lato-Heavy.ttf', 'Lato-Black.ttf', 'DejaVuSans-Bold.ttf'], size)


def bold(size):
    return ttf(['Lato-Bold.ttf', 'DejaVuSans-Bold.ttf'], size)


def regular(size):
    return ttf(['Lato-Regular.ttf', 'DejaVuSans.ttf'], size)


def medium(size):
    return ttf(['Lato-Medium.ttf', 'Lato-Semibold.ttf', 'Lato-Regular.ttf', 'DejaVuSans.ttf'], size)


def rounded_mask(size, radius):
    mask = Image.new('L', size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
    return mask


def finish(image, radius, path):
    out = Image.new('RGBA', image.size, (0, 0, 0, 0))
    out.paste(image.convert('RGBA'), (0, 0), rounded_mask(image.size, radius))
    OUT.mkdir(parents=True, exist_ok=True)
    out.save(path, optimize=True)
    print(path.relative_to(ROOT))


def pill(draw, xy, text, font, fill, outline, color, pad=(12, 5)):
    x, y = xy
    box = draw.textbbox((0, 0), text, font=font)
    w, h = box[2] - box[0] + 2 * pad[0], box[3] - box[1] + 2 * pad[1]
    draw.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=fill, outline=outline, width=2)
    draw.text((x + pad[0] - box[0], y + pad[1] - box[1]), text, font=font, fill=color)
    return w, h


# A small 5x7 pixel font with Minecraft-like metrics (advance = glyph width + 1, line height 9), drawn for
# this script. '#' is ink. Rows 0-6 sit on the baseline; row 7 is the descender.
GLYPHS = {
    'A': ['.###.', '#...#', '#...#', '#####', '#...#', '#...#', '#...#'],
    'B': ['####.', '#...#', '####.', '#...#', '#...#', '#...#', '####.'],
    'C': ['.###.', '#...#', '#....', '#....', '#....', '#...#', '.###.'],
    'D': ['####.', '#...#', '#...#', '#...#', '#...#', '#...#', '####.'],
    'E': ['#####', '#....', '###..', '#....', '#....', '#....', '#####'],
    'F': ['#####', '#....', '###..', '#....', '#....', '#....', '#....'],
    'G': ['.####', '#....', '#..##', '#...#', '#...#', '#...#', '.###.'],
    'H': ['#...#', '#...#', '#####', '#...#', '#...#', '#...#', '#...#'],
    'I': ['###', '.#.', '.#.', '.#.', '.#.', '.#.', '###'],
    'J': ['....#', '....#', '....#', '....#', '....#', '#...#', '.###.'],
    'K': ['#...#', '#..#.', '###..', '#..#.', '#...#', '#...#', '#...#'],
    'L': ['#....', '#....', '#....', '#....', '#....', '#....', '#####'],
    'M': ['#...#', '##.##', '#.#.#', '#...#', '#...#', '#...#', '#...#'],
    'N': ['#...#', '##..#', '#.#.#', '#..##', '#...#', '#...#', '#...#'],
    'O': ['.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
    'P': ['####.', '#...#', '####.', '#....', '#....', '#....', '#....'],
    'Q': ['.###.', '#...#', '#...#', '#...#', '#...#', '#..#.', '.##.#'],
    'R': ['####.', '#...#', '####.', '#...#', '#...#', '#...#', '#...#'],
    'S': ['.####', '#....', '.###.', '....#', '....#', '#...#', '.###.'],
    'T': ['#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..'],
    'U': ['#...#', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
    'V': ['#...#', '#...#', '#...#', '#...#', '.#.#.', '.#.#.', '..#..'],
    'W': ['#...#', '#...#', '#...#', '#...#', '#.#.#', '##.##', '#...#'],
    'X': ['#...#', '.#.#.', '..#..', '.#.#.', '#...#', '#...#', '#...#'],
    'Y': ['#...#', '.#.#.', '..#..', '..#..', '..#..', '..#..', '..#..'],
    'Z': ['#####', '....#', '...#.', '..#..', '.#...', '#....', '#####'],
    'a': ['.....', '.....', '.###.', '....#', '.####', '#...#', '.####'],
    'b': ['#....', '#....', '#.##.', '##..#', '#...#', '#...#', '####.'],
    'c': ['.....', '.....', '.###.', '#...#', '#....', '#...#', '.###.'],
    'd': ['....#', '....#', '.##.#', '#..##', '#...#', '#...#', '.####'],
    'e': ['.....', '.....', '.###.', '#...#', '#####', '#....', '.####'],
    'f': ['..##', '.#..', '####', '.#..', '.#..', '.#..', '.#..'],
    'g': ['.....', '.....', '.####', '#...#', '#...#', '.####', '....#', '####.'],
    'h': ['#....', '#....', '#.##.', '##..#', '#...#', '#...#', '#...#'],
    'i': ['#', '.', '#', '#', '#', '#', '#'],
    'j': ['..#', '...', '..#', '..#', '..#', '..#', '#.#', '.#.'],
    'k': ['#...', '#...', '#..#', '#.#.', '##..', '#.#.', '#..#'],
    'l': ['#.', '#.', '#.', '#.', '#.', '#.', '.#'],
    'm': ['.....', '.....', '##.#.', '#.#.#', '#.#.#', '#...#', '#...#'],
    'n': ['.....', '.....', '####.', '#...#', '#...#', '#...#', '#...#'],
    'o': ['.....', '.....', '.###.', '#...#', '#...#', '#...#', '.###.'],
    'p': ['.....', '.....', '#.##.', '##..#', '#...#', '####.', '#....', '#....'],
    'q': ['.....', '.....', '.##.#', '#..##', '#...#', '.####', '....#', '....#'],
    'r': ['.....', '.....', '#.##.', '##..#', '#....', '#....', '#....'],
    's': ['.....', '.....', '.####', '#....', '.###.', '....#', '####.'],
    't': ['.#.', '.#.', '###', '.#.', '.#.', '.#.', '..#'],
    'u': ['.....', '.....', '#...#', '#...#', '#...#', '#...#', '.####'],
    'v': ['.....', '.....', '#...#', '#...#', '#...#', '.#.#.', '..#..'],
    'w': ['.....', '.....', '#...#', '#...#', '#.#.#', '#.#.#', '.####'],
    'x': ['.....', '.....', '#...#', '.#.#.', '..#..', '.#.#.', '#...#'],
    'y': ['.....', '.....', '#...#', '#...#', '#...#', '.####', '....#', '####.'],
    'z': ['.....', '.....', '#####', '...#.', '..#..', '.#...', '#####'],
    '0': ['.###.', '#...#', '#..##', '#.#.#', '##..#', '#...#', '.###.'],
    '1': ['..#..', '.##..', '..#..', '..#..', '..#..', '..#..', '#####'],
    '2': ['.###.', '#...#', '....#', '..##.', '.#...', '#...#', '#####'],
    '3': ['.###.', '#...#', '....#', '..##.', '....#', '#...#', '.###.'],
    '4': ['...##', '..#.#', '.#..#', '#...#', '#####', '....#', '....#'],
    '5': ['#####', '#....', '####.', '....#', '....#', '#...#', '.###.'],
    '6': ['..##.', '.#...', '#....', '####.', '#...#', '#...#', '.###.'],
    '7': ['#####', '#...#', '....#', '...#.', '..#..', '..#..', '..#..'],
    '8': ['.###.', '#...#', '#...#', '.###.', '#...#', '#...#', '.###.'],
    '9': ['.###.', '#...#', '#...#', '.####', '....#', '...#.', '.##..'],
    ' ': ['...', '...', '...', '...', '...', '...', '...'],
    '.': ['.', '.', '.', '.', '.', '.', '#'],
    ',': ['.', '.', '.', '.', '.', '.', '#', '#'],
    ':': ['.', '.', '#', '.', '.', '.', '#'],
    ';': ['.', '.', '#', '.', '.', '.', '#', '#'],
    '!': ['#', '#', '#', '#', '#', '.', '#'],
    '?': ['.###.', '#...#', '....#', '...#.', '..#..', '.....', '..#..'],
    "'": ['#', '#', '.', '.', '.', '.', '.'],
    '"': ['#.#', '#.#', '...', '...', '...', '...', '...'],
    '(': ['..#', '.#.', '#..', '#..', '#..', '.#.', '..#'],
    ')': ['#..', '.#.', '..#', '..#', '..#', '.#.', '#..'],
    '-': ['.....', '.....', '.....', '#####', '.....', '.....', '.....'],
    '−': ['.....', '.....', '.....', '#####', '.....', '.....', '.....'],
    '+': ['.....', '..#..', '..#..', '#####', '..#..', '..#..', '.....'],
    '=': ['.....', '.....', '#####', '.....', '#####', '.....', '.....'],
    '/': ['....#', '...#.', '...#.', '..#..', '.#...', '.#...', '#....'],
    '%': ['##..#', '##.#.', '...#.', '..#..', '.#...', '.#.##', '#..##'],
    '°': ['.#.', '#.#', '.#.', '...', '...', '...', '...'],
    '×': ['.....', '#...#', '.#.#.', '..#..', '.#.#.', '#...#', '.....'],
    '·': ['.', '.', '.', '#', '.', '.', '.'],
    '…': ['.....', '.....', '.....', '.....', '.....', '.....', '#.#.#'],
    '_': ['.....', '.....', '.....', '.....', '.....', '.....', '.....', '#####'],
    '▶': ['#...', '##..', '###.', '####', '###.', '##..', '#...'],
    '◀': ['...#', '..##', '.###', '####', '.###', '..##', '...#'],
}


def glyph(ch):
    return GLYPHS.get(ch) or GLYPHS['?']


def text_width(text, bold=False):
    return sum(len(glyph(ch)[0]) + 1 + (1 if bold and ch != ' ' else 0) for ch in text)


def rgba(argb):
    return ((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >> 24) & 255)


class Gui:
    """A Minecraft-like GUI canvas: logical coordinates, every logical pixel drawn as SxS."""
    S = 2

    def __init__(self, width, height, background):
        self.w, self.h = width, height
        self.image = background.convert('RGBA').resize((width * self.S, height * self.S))
        self.clip = None

    def _box(self, x0, y0, x1, y1):
        if self.clip:
            cx0, cy0, cx1, cy1 = self.clip
            x0, y0, x1, y1 = max(x0, cx0), max(y0, cy0), min(x1, cx1), min(y1, cy1)
        return x0, y0, x1, y1

    def fill(self, x0, y0, x1, y1, argb):
        x0, y0, x1, y1 = self._box(x0, y0, x1, y1)
        if x1 <= x0 or y1 <= y0:
            return
        layer = Image.new('RGBA', ((x1 - x0) * self.S, (y1 - y0) * self.S), rgba(argb))
        region = self.image.crop((x0 * self.S, y0 * self.S, x1 * self.S, y1 * self.S))
        self.image.paste(Image.alpha_composite(region, layer), (x0 * self.S, y0 * self.S))

    def outline(self, x, y, w, h, argb):
        self.fill(x, y, x + w, y + 1, argb)
        self.fill(x, y + h - 1, x + w, y + h, argb)
        self.fill(x, y + 1, x + 1, y + h - 1, argb)
        self.fill(x + w - 1, y + 1, x + w, y + h - 1, argb)

    def blit(self, image, x, y, w, h):
        self.image.alpha_composite(image.convert('RGBA').resize((w * self.S, h * self.S), Image.NEAREST), (x * self.S, y * self.S))

    def _glyphs(self, text, x, y, argb, bold, italic):
        draw = ImageDraw.Draw(self.image)
        color = rgba(argb)
        for ch in text:
            rows = glyph(ch)
            for r, row in enumerate(rows):
                shift = 1 if italic and r < 4 else 0
                for c, cell in enumerate(row):
                    if cell != '#':
                        continue
                    for b in ((0, 1) if bold else (0,)):
                        px, py = (x + c + shift + b) * self.S, (y + r) * self.S
                        if self.clip and not (self.clip[0] * self.S <= px < self.clip[2] * self.S and self.clip[1] * self.S <= py < self.clip[3] * self.S):
                            continue
                        draw.rectangle((px, py, px + self.S - 1, py + self.S - 1), fill=color)
            x += len(rows[0]) + 1 + (1 if bold and ch != ' ' else 0)

    def text(self, text, x, y, argb, shadow=False, bold=False, italic=False):
        if shadow:
            r, g, b, a = rgba(argb)
            self._glyphs(text, x + 1, y + 1, (a << 24) | ((r >> 2) << 16) | ((g >> 2) << 8) | (b >> 2), bold, italic)
        self._glyphs(text, x, y, argb, bold, italic)

    def centered(self, text, cx, y, argb, shadow=True, bold=False, italic=False):
        self.text(text, cx - text_width(text, bold) // 2, y, argb, shadow, bold, italic)

    def button(self, x, y, w, h, label, active=True, hovered=False):
        """Drawn to match the look of Minecraft's widget/button sprite (bevelled grey), not copied from it."""
        border = 0xFFFFFFFF if hovered else 0xFF000000
        self.fill(x, y, x + w, y + h, border)
        if active:
            self.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF757575 if hovered else 0xFF6F6F6F)
            self.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFAFAFAF if hovered else 0xFFAAAAAA)
            self.fill(x + 1, y + 1, x + 2, y + h - 3, 0xFFAFAFAF if hovered else 0xFFAAAAAA)
            self.fill(x + 1, y + h - 3, x + w - 1, y + h - 2, 0xFF555555 if hovered else 0xFF505050)
            self.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF646464 if hovered else 0xFF5E5E5E)
        else:
            self.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2C2C2C)
        label = fit(label, w - 8)
        self.centered(label, x + w // 2, y + (h - 8) // 2, 0xFFFFFFFF if active else 0xFFA0A0A0, shadow=active)

    def separator(self, y, header):
        rows = [0x33FFFFFF, 0xBF000000] if header else [0xBF000000, 0x33FFFFFF]
        self.fill(0, y, self.w, y + 1, rows[0])
        self.fill(0, y + 1, self.w, y + 2, rows[1])

    def tooltip(self, lines, mx, my):
        """Minecraft's tooltip: near-black violet box, a violet border fading down, lines 10 apart."""
        width = max(text_width(line) for line in lines)
        height = 8 if len(lines) == 1 else 8 + 2 + (len(lines) - 1) * 10
        x, y = mx + 12, my - 12
        if x + width + 4 > self.w:
            x = max(4, mx - 16 - width)
        y = max(4, min(y, self.h - height - 4))
        self.fill(x - 3, y - 4, x + width + 3, y + height + 4, 0xF0100010)
        self.fill(x - 4, y - 3, x - 3, y + height + 3, 0xF0100010)
        self.fill(x + width + 3, y - 3, x + width + 4, y + height + 3, 0xF0100010)
        top, bottom = (0x50, 0x50, 0x00, 0xFF), (0x50, 0x28, 0x00, 0x7F)
        rows = height + 6
        for i in range(rows):
            t = i / max(1, rows - 1)
            a, r, g, b = (int(top[k] + (bottom[k] - top[k]) * t) for k in range(4))
            c = (a << 24) | (r << 16) | (g << 8) | b
            self.fill(x - 3, y - 3 + i, x - 2, y - 2 + i, c)
            self.fill(x + width + 2, y - 3 + i, x + width + 3, y - 2 + i, c)
        self.fill(x - 2, y - 3, x + width + 2, y - 2, 0x505000FF)
        self.fill(x - 2, y + height + 2, x + width + 2, y + height + 3, 0x5028007F)
        ty = y
        for i, line in enumerate(lines):
            self.text(line, x, ty, 0xFFFFFFFF, shadow=True)
            ty += 12 if i == 0 else 10

    def save(self, path, watermark):
        self.text(watermark, 6, 5, 0xFF8A93A1, shadow=True)
        finish(self.image, 14, path)


def fit(text, width):
    if text_width(text) <= width:
        return text
    out = ''
    for ch in text:
        if text_width(out + ch) > max(1, width - text_width('…')):
            break
        out += ch
    return out + '…'


def wrap(text, width, max_lines):
    """LightshadeScreen.wrap, word for word."""
    lines, line, overflow = [], '', False
    if not text or not text.strip():
        return lines
    for word in text.strip().split():
        nxt = word if not line else line + ' ' + word
        if text_width(nxt) <= width:
            line = nxt
            continue
        if line:
            lines.append(line)
            line = ''
        if len(lines) == max_lines:
            overflow = True
            break
        if text_width(word) > width:
            lines.append(fit(word, width))
            if len(lines) == max_lines:
                overflow = True
                break
        else:
            line = word
    if not overflow and line:
        if len(lines) < max_lines:
            lines.append(line)
        else:
            overflow = True
    if overflow and lines:
        end = lines[-1]
        while end and text_width(end + '…') > width:
            end = end[:-1]
        lines[-1] = end.rstrip() + '…'
    return lines


def split(text, width):
    """Tooltip splitting: by newline, then words."""
    out = []
    for paragraph in text.split('\n'):
        if not paragraph:
            out.append('')
            continue
        line = ''
        for word in paragraph.split(' '):
            nxt = word if not line else line + ' ' + word
            if text_width(nxt) <= width:
                line = nxt
            else:
                out.append(line)
                line = word
        out.append(line)
    return out


def tr(key, *args):
    value = LANG[key]
    return value % args if args else value


def java_hash(text):
    h = 0
    for ch in text:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= 1 << 31 else h


# Colours from LightshadeScreen.
TEXT, MUTED, DIM, GOLD, ACCENT = 0xFFFFFFFF, 0xFFA9B2BF, 0xFF6F7886, 0xFFFFCB47, 0xFF5CB8EC
OK, ERROR, PANEL, CARD, CARD_HOVER, EDGE = 0xFF72DD8F, 0xFFFF6E6E, 0x9A0C0E13, 0xB0101319, 0xC0222731, 0x33FFFFFF
BADGES = [0xFF2C7FB8, 0xFF7B52C9, 0xFF2E9A6A, 0xFFB8743A]
HEADER = 34
GUI_W, GUI_H = 600, 340


def menu_background():
    """Minecraft blurs the world behind in-world menus. Here: a blurred offline Daylight midday view."""
    view = render_view((300, 170), sky={'pack': 'daylight', 'sun': sun_at(55, 150)}, yaw=165, pitch=4, fov=95, ground=(0.06, 0.05), green=True)
    view = view.resize((GUI_W * 2, GUI_H * 2), Image.BICUBIC).filter(ImageFilter.GaussianBlur(18))
    return Image.blend(view, Image.new('RGB', view.size, (0, 0, 0)), 0.30)


def arrow(gui, x, y, right=True, hovered=True):
    """A stepped select/unselect arrow in the spirit of Minecraft's transferable_list sprites (drawn, not copied)."""
    fill, edge = (0xFFFFFFFF, 0xFF4A4A4A) if hovered else (0xFFD0D0D0, 0xFF3A3A3A)

    def span(r):
        r = max(0, min(23, r))
        return 2 * (min(r, 23 - r) // 2) + 2
    for color, grow in ((edge, 1), (fill, 0)):
        for r in range(-grow, 24 + grow):
            length = span(r) + 2 * grow
            yy = y + 4 + r
            if right:
                gui.fill(x + 11 - grow, yy, x + 11 - grow + length, yy + 1, color)
            else:
                gui.fill(x + 21 + grow - length, yy, x + 21 + grow, yy + 1, color)


def pack_card(gui, entry, x, y, w, hovered, available, icon, focus=False, on_icon=False):
    CARD_H, ICON = 42, 32
    gui.fill(x, y, x + w, y + CARD_H, CARD_HOVER if hovered else CARD)
    if entry['problem']:
        gui.fill(x, y, x + 2, y + CARD_H, ERROR)
    elif not available:
        gui.fill(x, y, x + 2, y + CARD_H, ACCENT)
    gui.outline(x, y, w, CARD_H, 0xA0FFFFFF if focus else EDGE)
    ix, iy = x + 5, y + 5
    gui.blit(icon, ix, iy, ICON, ICON)
    if hovered and not entry['problem']:
        gui.fill(ix, iy, ix + ICON, iy + ICON, 0xA0909090)
        arrow(gui, ix, iy, right=available, hovered=on_icon)
    text_x, text_w = x + 42, w - 46
    tag = entry['tag']
    badge_w = 0 if not tag else min(text_width(tag) + 8, text_w // 2)
    title = fit(entry['name'], text_w - (badge_w + 4 if badge_w else 0))
    gui.text(title, text_x, y + 5, TEXT if not entry['problem'] else 0xFFFFB3B3, shadow=True, bold=True)
    if badge_w:
        bx = x + w - 4 - badge_w
        color = BADGES[java_hash(tag) % 4] if not entry['problem'] else 0xFF9A2F35
        gui.fill(bx, y + 4, bx + badge_w, y + 15, (color & 0x00FFFFFF) | 0x70000000)
        gui.outline(bx, y + 4, badge_w, 11, color)
        gui.centered(fit(tag, badge_w - 4), bx + badge_w // 2, y + 6, 0xFFEFF6FF)
    for i, line in enumerate(wrap(entry['description'], text_w, 2)):
        gui.text(line, text_x, y + 18 + i * 10, MUTED if not entry['problem'] else 0xFFE0A0A0)


def pill_widget(gui, x, y, w, h, label, on, hover=False, active=True):
    fill = 0xFF2D6E96 if on else 0xD0303642 if hover else 0xC0181B22
    gui.fill(x + 1, y, x + w - 1, y + h, fill)
    gui.fill(x, y + 1, x + 1, y + h - 1, fill)
    gui.fill(x + w - 1, y + 1, x + w, y + h - 1, fill)
    if on:
        gui.fill(x + 1, y, x + w - 1, y + 1, 0xFF8FD3FF)
    elif hover:
        gui.fill(x + 1, y + h - 1, x + w - 1, y + h, 0x60FFFFFF)
    color = DIM if not active else TEXT if on else MUTED
    if text_width(label, on) > w - 4:
        label = fit(label, w - 6)
    gui.centered(label, x + w // 2, y + (h - 8) // 2, color, bold=on)


def ui_pack_selector(background):
    gui = Gui(GUI_W, GUI_H, background)
    width, height, FOOTER, CARD_H, CARD_GAP = GUI_W, GUI_H, 58, 42, 3
    gap = 10
    column = min(250, (width - 3 * gap) // 2)
    left_x = (width - 2 * column - gap) // 2
    right_x = left_x + column + gap
    list_top, list_bottom = HEADER + 16, height - FOOTER - 22
    icons_by_id = {'daylight': Image.open(ROOT / 'packs/daylight/pack.png'), 'aurora': Image.open(ROOT / 'packs/aurora/pack.png'),
                   'default': Image.open(ROOT / 'src/main/resources/assets/lightshade/textures/gui/default_pack.png')}
    active = {'name': tr('lightshade.pack.daylight'), 'tag': tr('lightshade.pack.daylight.tag'),
              'description': tr('lightshade.pack.daylight.desc'), 'problem': None}
    available = [
        {'name': tr('lightshade.pack.aurora'), 'tag': tr('lightshade.pack.aurora.tag'),
         'description': tr('lightshade.pack.aurora.desc'), 'problem': None, 'icon': 'aurora'},
        {'name': 'Old OptiFine Pack', 'tag': tr('lightshade.menu.broken'), 'icon': 'default',
         'description': 'This is an Iris/OptiFine or Sulkan pack. Lightshade needs a pack made for Lightshade (with lightshade.json).',
         'problem': True},
    ]

    # extractFrame
    gui.separator(HEADER - 2, True)
    gui.separator(height - FOOTER, False)
    gui.centered(tr('lightshade.menu.packs'), width // 2, 8, TEXT)
    gui.centered(tr('lightshade.menu.subtitle'), width // 2, 20, MUTED, italic=True)
    gui.text(tr('lightshade.menu.available'), left_x + 2, HEADER + 4, TEXT, shadow=True, bold=True)
    gui.text(tr('lightshade.menu.active'), right_x + 2, HEADER + 4, TEXT, shadow=True, bold=True)

    # Left column: Aurora hovered with the pointer on its icon, then a pack Lightshade cannot read.
    gui.fill(left_x - 2, list_top - 2, left_x + column + 2, list_bottom + 2, PANEL)
    gui.clip = (left_x - 2, list_top - 1, left_x + column + 2, list_bottom + 1)
    for i, entry in enumerate(available):
        y = list_top + i * (CARD_H + CARD_GAP)
        pack_card(gui, entry, left_x, y, column, hovered=i == 0, available=True, icon=icons_by_id[entry['icon']], on_icon=i == 0)
    hint_y = list_top + len(available) * (CARD_H + CARD_GAP) + 4
    gui.centered(fit(tr('lightshade.menu.drop_hint'), column - 8), left_x + column // 2, hint_y, DIM, italic=True)
    gui.clip = None

    # Right column: the active pack, its quality presets, the settings button and the status line.
    gui.fill(right_x - 2, list_top - 2, right_x + column + 2, list_bottom + 2, PANEL)
    pack_card(gui, active, right_x, list_top, column, hovered=False, available=False, icon=icons_by_id['daylight'])
    gui.text(tr('lightshade.menu.quality'), right_x + 2, list_top + CARD_H + 7, MUTED)
    y = list_top + CARD_H + 18
    presets = [p['name'] for p in manifest('daylight')['presets']]
    count = len(presets)
    each = (column - 3 * (count - 1)) // count
    for i, name in enumerate(presets):
        w = column - i * (each + 3) if i == count - 1 else each
        pill_widget(gui, right_x + i * (each + 3), y, w, 18, tr('lightshade.preset.' + name.lower()), on=name == 'High')
    y += 24
    gui.button(right_x, y, column, 20, tr('lightshade.menu.pack_settings'))
    info_y = y + 26
    for line in wrap(tr('lightshade.menu.running'), column - 4, 3):
        gui.text(line, right_x + 2, info_y, DIM)
        info_y += 10

    # The banner after a successful Apply.
    bottom = height - FOOTER - 4
    top = bottom - 16
    gui.fill(left_x - 2, top, right_x + column + 2, bottom, 0xE0133524)
    gui.fill(left_x - 2, top, left_x, bottom, OK)
    gui.text(fit(tr('lightshade.menu.applied', tr('lightshade.pack.daylight')), right_x + column - left_x - 8), left_x + 6, top + 4, TEXT)

    # Footer buttons.
    row1, row2 = height - FOOTER + 8, height - FOOTER + 32
    third = (2 * column + gap - 8) // 3
    gui.button(left_x, row1, column, 20, tr('lightshade.menu.open_folder'))
    gui.button(right_x, row1, column, 20, tr('lightshade.menu.refresh'))
    gui.button(left_x, row2, third, 20, tr('lightshade.menu.cancel'))
    gui.button(left_x + third + 4, row2, third, 20, tr('lightshade.menu.apply'), active=False)
    gui.button(left_x + 2 * (third + 4), row2, 2 * column + gap - 2 * (third + 4), 20, tr('lightshade.menu.done'), hovered=True)
    gui.save(OUT / 'ui-pack-selector.png', 'UI preview')


OPTION_LEVELS = {'WATER_REFLECTION_QUALITY', 'CLOUD_QUALITY', 'GODRAYS', 'VOLUMETRIC_LIGHT', 'AMBIENT_OCCLUSION'}


def option_value(key, option, value):
    """OptionText.value"""
    values = sorted(option['values'])
    if len(values) == 2 and 0.0 in values and 1.0 in values:
        return tr('lightshade.level.on' if value > 0 else 'lightshade.level.off')
    if key in OPTION_LEVELS:
        names = {2: ['off', 'on'], 3: ['off', 'low', 'high'], 4: ['off', 'low', 'medium', 'high']}.get(len(values), ['off', 'low', 'medium', 'high', 'ultra'])
        index = values.index(value)
        if index < len(names):
            return tr('lightshade.level.' + names[index])
    if key == 'SHADOW_RESOLUTION':
        return f'{value:.0f} px'
    if key == 'SHADOW_DISTANCE':
        return tr('lightshade.menu.blocks', f'{value:.0f}')
    if key == 'SHADOW_CASCADES':
        return f'{value:.0f}'
    if key == 'SUN_TILT':
        return f'{value:.0f}°'
    if key in ('SUN_SIZE', 'MOON_SIZE'):
        return '×' + (f'{value:.0f}' if value == round(value) else f'{value:.2f}'.rstrip('0'))
    return f'{value * 100:.0f}%'


def ui_settings(background):
    gui = Gui(GUI_W, GUI_H, background)
    width, height, FOOTER, ROW, STEP = GUI_W, GUI_H, 34, 24, 14
    data = manifest('aurora')
    options = data['options']
    draft = {k: v['default'] for k, v in options.items()}
    draft['SKY_AURORA'] = 1.5
    draft['SUN_SIZE'] = 1.25
    changed = sum(1 for k in options if draft[k] != options[k]['default'])
    body = min(width - 20, 520)
    left = (width - body) // 2
    gui.separator(HEADER - 2, True)
    gui.separator(height - FOOTER, False)
    gui.centered(tr('lightshade.pack.aurora'), width // 2, 8, TEXT)
    gui.centered(fit(tr('lightshade.menu.settings_changed', changed), width - 16), width // 2, 20, MUTED, italic=True)

    y = HEADER + 6
    label_w = text_width(tr('lightshade.menu.quality')) + 10
    presets = data['presets']
    count = len(presets)
    each = (body - label_w - 3 * (count - 1)) // count
    active_preset = next(p['name'] for p in presets if all(draft[k] == v for k, v in p['values'].items()))
    gui.text(tr('lightshade.menu.quality'), left, HEADER + 11, MUTED)
    for i, preset in enumerate(presets):
        x = left + label_w + i * (each + 3)
        w = body - label_w - i * (each + 3) if i == count - 1 else each
        pill_widget(gui, x, y, w, 18, tr('lightshade.preset.' + preset['name'].lower()), on=preset['name'] == active_preset)
    y += 24

    categories = ['Lighting', 'Shadows', 'Water', 'Sky', 'Nature', 'Atmosphere', 'Advanced']
    tabs = [c for c in categories if any(o['category'] == c for o in options.values())]
    per_row, widest = len(tabs), max(text_width(tr('lightshade.category.' + t.lower())) + 12 for t in tabs)
    while per_row > 1 and (body - 3 * (per_row - 1)) // per_row < widest:
        per_row = (per_row + 1) // 2
    each = (body - 3 * (per_row - 1)) // per_row
    current = 'Sky'
    for i, tab in enumerate(tabs):
        column = i % per_row
        x = left + column * (each + 3)
        w = body - column * (each + 3) if column == per_row - 1 else each
        pill_widget(gui, x, y + (i // per_row) * 21, w, 18, tr('lightshade.category.' + tab.lower()), on=tab == current,
                    hover=tab == 'Nature')
    y += ((len(tabs) + per_row - 1) // per_row) * 21 + 6

    top, bottom = y, height - FOOTER - 22
    visible = [(k, o) for k, o in options.items() if o['category'] == current]
    columns = 2 if body >= 340 else 1
    col_w = (body - 6 * (columns - 1)) // columns
    gui.fill(left - 3, top - 3, left + body + 3, bottom + 3, PANEL)
    hovered_key = 'SKY_SHOOTING_STARS'
    mouse = None
    for i, (key, option) in enumerate(visible):
        x, oy, w, h = left + (i % columns) * (col_w + 6), top + (i // columns) * ROW, col_w, 20
        hover = key == hovered_key
        if hover:
            mouse = (x + w - 6, oy + 16)
        value = draft[key]
        modified = value != option['default']
        gui.fill(x, oy, x + w, oy + h, 0xD0262B35 if hover else 0xC0141720)
        gui.outline(x, oy, w, h, 0x50FFFFFF if hover else 0x24FFFFFF)
        name, text = tr('lightshade.option.' + key), option_value(key, option, value)
        choices = sorted(option['values'])
        if len(choices) == 2 and 0.0 in choices and 1.0 in choices:
            on = value > 0
            label = tr('lightshade.level.pill_on' if on else 'lightshade.level.pill_off')
            pw = max(26, text_width(label, True) + 10)
            px, py = x + w - pw - 4, oy + 4
            gui.fill(px, py, px + pw, py + h - 8, 0xFF2F8A55 if on else 0xFF3A3F49)
            gui.fill(px + (pw - 3 if on else 0), py, px + (pw if on else 3), py + h - 8, 0xFF7BE3A0 if on else 0xFF6B7280)
            gui.centered(label, px + pw // 2, oy + (h - 8) // 2, GOLD if modified else TEXT, bold=True)
            gui.text(fit(name, w - pw - 14), x + 6, oy + (h - 8) // 2, TEXT)
            continue
        inner0, inner1 = x + STEP, x + w - STEP
        fraction = choices.index(value) / (len(choices) - 1) if len(choices) > 1 else 1.0
        filled = inner0 + round((inner1 - inner0) * fraction)
        gui.fill(inner0, oy + 2, filled, oy + h - 2, 0x40FFC640 if modified else 0x402D86C0)
        gui.fill(inner0, oy + h - 4, filled, oy + h - 2, 0xFFE0A92E if modified else 0xFF4DA8E0)
        for t in range(len(choices)):
            tx = inner0 + round((inner1 - inner0 - 1) * (1.0 if len(choices) < 2 else t / (len(choices) - 1)))
            gui.fill(tx, oy + h - 6, tx + 1, oy + h - 4, 0xA0FFFFFF if tx <= filled else 0x50FFFFFF)
        on_plus = hover
        gui.centered('−', x + STEP // 2, oy + (h - 8) // 2, MUTED)
        gui.centered('+', x + w - STEP // 2, oy + (h - 8) // 2, TEXT if on_plus else MUTED)
        value_w = text_width(text)
        gui.text(fit(name, inner1 - inner0 - value_w - 10), inner0 + 3, oy + (h - 8) // 2, TEXT, shadow=True)
        gui.text(text, inner1 - 3 - value_w, oy + (h - 8) // 2, GOLD if modified else TEXT, shadow=True)

    footer = height - FOOTER + 8
    each = (body - 12) // 4
    gui.button(left, footer, each, 20, tr('lightshade.menu.defaults'))
    gui.button(left + each + 4, footer, each, 20, tr('lightshade.menu.cancel'))
    gui.button(left + 2 * (each + 4), footer, each, 20, tr('lightshade.menu.apply'))
    gui.button(left + 3 * (each + 4), footer, body - 3 * (each + 4), 20, tr('lightshade.menu.done'))

    # The row tooltip, as LightshadeSettingsScreen builds it.
    option = options[hovered_key]
    help_text = (tr('lightshade.option.' + hovered_key + '.desc') + '\n\n'
                 + tr('lightshade.menu.default', option_value(hovered_key, option, option['default'])) + '\n'
                 + tr('lightshade.menu.how_slider'))
    gui.tooltip(split(help_text, 240), *mouse)
    gui.save(OUT / 'ui-settings.png', 'UI preview')


# =====================================================================================================
# The sky comparison and the banner.
# =====================================================================================================

def sky_comparison():
    W, H = 1280, 520
    image = Image.new('RGB', (W, H), (13, 17, 23))
    draw = ImageDraw.Draw(image)
    for y in range(H):
        t = y / H
        draw.line((0, y, W, y), fill=(int(15 + 6 * t), int(19 + 7 * t), int(27 + 10 * t)))
    margin, gap = 24, 20
    pw = (W - 2 * margin - gap) // 2
    main_h, strip_h = 290, 84
    top = 66
    views = {
        'daylight': [
            ('Midday · facing the sun', render_view((pw, main_h), sky={'pack': 'daylight', 'sun': sun_at(33, 214)},
                                                   yaw=200, pitch=19, fov=78, ground=(0.030, 0.026))),
            ('Sunset', render_view((pw, strip_h), sky={'pack': 'daylight', 'sun': sun_at(1.6, 270),
                                                       'native_sky': tuple(v * 0.55 for v in (0x78 / 255, 0xA7 / 255, 1.0))},
                                   yaw=270, pitch=4.2, fov=78, ground=(0.006, 0.006))),
        ],
        'aurora': [
            ('Clear night · facing north', render_view((pw, main_h), sky={'pack': 'aurora', 'sun': sun_at(-28, 250), 'time': 120.0},
                                                       yaw=-8, pitch=17, fov=78, ground=(0.045, 0.040))),
            ('Sunset', render_view((pw, strip_h), sky={'pack': 'aurora', 'sun': sun_at(0.9, 270)},
                                   yaw=270, pitch=4.2, fov=78, ground=(0.006, 0.006))),
        ],
    }
    title_font, tag_font, label_font, caption_font = bold(30), medium(17), medium(16), regular(17)
    for i, pack in enumerate(('daylight', 'aurora')):
        x = margin + i * (pw + gap)
        name, tag = tr(f'lightshade.pack.{pack}'), tr(f'lightshade.pack.{pack}.tag')
        draw.text((x + 2, 20), name, font=title_font, fill=(240, 244, 250))
        tw = draw.textlength(name, font=title_font)
        badge = (0x2C, 0x7F, 0xB8) if pack == 'daylight' else (0x7B, 0x52, 0xC9)
        pill(draw, (int(x + tw + 16), 26), tag, tag_font, tuple(int(c * 0.35) for c in badge), badge, (235, 242, 255), pad=(10, 4))
        y = top
        for j, (label, view) in enumerate(views[pack]):
            framed = Image.new('RGBA', view.size, (0, 0, 0, 0))
            framed.paste(view, (0, 0), rounded_mask(view.size, 12))
            image.paste(framed, (x, y), framed)
            ld = ImageDraw.Draw(image, 'RGBA')
            box = ld.textbbox((0, 0), label, font=label_font)
            lw, lh = box[2] - box[0] + 20, box[3] - box[1] + 12
            ld.rounded_rectangle((x + 12, y + 12, x + 12 + lw, y + 12 + lh), radius=lh // 2, fill=(8, 12, 20, 165))
            ld.text((x + 22 - box[0], y + 18 - box[1]), label, font=label_font, fill=(232, 238, 246))
            y += view.size[1] + 10
    caption = ('Offline atmospheric render of the packs\u2019 own sky code (atmospheric.glsl + display.fsh) at default settings. '
               'Sky dome only, no clouds, bloom or terrain; the horizon silhouettes are illustrative. Not an in-game screenshot.')
    lines = [caption[:caption.index('Sky dome')].strip(), caption[caption.index('Sky dome'):].strip()]
    cy = top + main_h + 10 + strip_h + 18
    for line in lines:
        lw = draw.textlength(line, font=caption_font)
        draw.text(((W - lw) / 2, cy), line, font=caption_font, fill=(150, 160, 175))
        cy += 24
    finish(image, 18, OUT / 'sky-comparison.png')


def banner():
    W, H = 1280, 420
    image = Image.new('RGB', (W, H))
    draw = ImageDraw.Draw(image)
    for y in range(H):
        t = y / H
        draw.line((0, y, W, y), fill=(int(14 + 8 * t), int(18 + 9 * t), int(26 + 12 * t)))
    # The atmospheric band: a panorama facing north while the sun goes down in the west, from Daylight's
    # afternoon on the left, through sunset, to Aurora's night on the right.
    band_h = 150
    band = render_band((W, band_h))
    fade = Image.new('L', (W, band_h))
    fd = ImageDraw.Draw(fade)
    for y in range(band_h):
        fd.line((0, y, W, y), fill=int(255 * min(1.0, (y / band_h) ** 0.65 * 1.25)))
    image.paste(band, (0, H - band_h), fade)
    # A soft vignette so the type sits on calm ground.
    glow = Image.new('L', (W, H), 0)
    ImageDraw.Draw(glow).ellipse((-160, -220, 1000, 300), fill=120)
    glow = glow.filter(ImageFilter.GaussianBlur(90))
    image.paste(Image.new('RGB', (W, H), (8, 10, 15)), (0, 0), glow)
    # The emblem.
    size = 196
    old = icons.SS
    icons.SS = 3
    emblem = Image.frombytes('RGBA', (size, size), bytes(v for row in icons.render(size, icons.emblem) for p in row for v in p))
    icons.SS = old
    shadow = Image.new('RGBA', (size + 60, size + 60), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((30, 40, size + 30, size + 40), radius=30, fill=(0, 0, 0, 150))
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    image.paste(shadow, (72 - 30, 62 - 30), shadow)
    image.paste(emblem, (72, 62), emblem)
    # Type.
    title = heavy(92)
    x = 312
    draw.text((x, 58), 'Lightshade', font=title, fill=(246, 248, 252))
    draw.text((x + 4, 166), 'Shader Loader', font=ttf(['Lato-Light.ttf', 'Lato-Regular.ttf', 'DejaVuSans.ttf'], 46), fill=(255, 205, 110))
    draw.text((x + 4, 226), 'Vulkan Shader Loader for Minecraft 26.3  ·  Fabric + Sodium', font=regular(25), fill=(176, 186, 200))
    py = 274
    px = x + 4
    for label, fill, edge in (('v0.0.1', (24, 42, 58), (92, 184, 236)), ('MIT', (22, 44, 34), (114, 221, 143)),
                              ('Daylight + Aurora built in', (40, 34, 20), (255, 203, 71))):
        w, _ = pill(draw, (px, py), label, medium(18), fill, edge, (236, 242, 250), pad=(12, 5))
        px += w + 10
    finish(image, 22, OUT / 'banner.png')


def band_column(job):
    x, w, h = job
    t = x / (w - 1)
    altitude = mixf(30.0, -24.0, smoothstep(0.05, 0.95, t))
    sun = sun_at(altitude, 0)
    day = Sky('daylight', sun)
    night = Sky('aurora', sun, time=120.0)
    blend = smoothstep(0.40, 0.62, t)
    col = []
    for y in range(h):
        azimuth = math.radians(mixf(-58.0, 58.0, t))
        elevation = math.radians(mixf(26.0, -1.5, y / (h - 1)))
        ray = normalize((math.sin(azimuth) * math.cos(elevation), math.sin(elevation), -math.cos(azimuth) * math.cos(elevation)))
        a = day.display(day.radiance(ray))
        b = night.display(night.radiance(ray))
        c = mixv(a, b, blend)
        yaw = azimuth
        far = ridge(yaw * 2.2, 0.030, 0.026, 1.3)
        near = ridge(yaw * 2.2, 0.010, 0.020, 4.2)
        if elevation < far:
            c = mixv(c, (0.07, 0.09, 0.13), 0.78)
        if elevation < near:
            c = (0.035, 0.045, 0.07)
        col.append(c)
    return x, col


def render_band(size):
    w, h = size
    with Pool(os.cpu_count() or 4) as pool:
        cols = dict(pool.map(band_column, [(x, w, h) for x in range(w)], chunksize=8))
    image = Image.new('RGB', size)
    image.putdata([tuple(int(round(v * 255)) for v in cols[x][y]) for y in range(h) for x in range(w)])
    return image


def main(which):
    which = set(which or ('banner', 'sky', 'ui'))
    if 'banner' in which:
        banner()
    if 'sky' in which:
        sky_comparison()
    if 'ui' in which:
        background = menu_background()
        ui_pack_selector(background)
        ui_settings(background)


if __name__ == '__main__':
    main(sys.argv[1:])
