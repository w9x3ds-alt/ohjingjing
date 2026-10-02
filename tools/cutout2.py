#!/usr/bin/env python3
"""
鲸鲸抠图 v2
 1. 四角泛洪标记"外部背景"（只吃与画布边缘连通的纯白，角色内部的白色蕾丝/领结不会被误伤）
 2. 边界环 alpha 重建：用 min(R,G,B) 反推不透明度，过渡带不再是硬边
 3. 去白污染（unpremultiply）：把边缘像素里"白底渗进来的贡献"减掉，深色背景上不再有白晕
 4. 连通域取最大块 = 角色，丢掉原图的"哦鲸鲸"气泡
用法: python3 cutout2.py src.png assets/whale_char2.png
"""
from PIL import Image, ImageDraw, ImageFilter, ImageChops
import sys, os

SRC = sys.argv[1] if len(sys.argv) > 1 else 'src.png'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'assets/whale_char2.png'

SENT = (255, 0, 255)     # 哨兵色（原图里不可能出现品红）
FLOOD_THRESH = 34        # 各通道绝对差之和；越大吃得越狠，越小边留得越多
A0 = 74                  # min(RGB) ≤ A0 视为实体像素 → alpha=255
A_CUT = 36               # 重建后 alpha < A_CUT 直接判透明（掐掉肉眼不可见的白晕）
PAD = 6

im = Image.open(SRC).convert('RGB')
W, H = im.size
print(f"源图 {W}x{H}")

SHARP = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
if SHARP > 0:
    im = im.filter(ImageFilter.UnsharpMask(radius=1.0, percent=int(SHARP), threshold=2))
    print(f"轻锐化 percent={SHARP}")

# ---------- 1) 泛洪标记外部背景 ----------
work = im.copy()
for seed in ((0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)):
    ImageDraw.floodfill(work, seed, SENT, thresh=FLOOD_THRESH)

wr, wg, wb = work.split()
bgmask = ImageChops.multiply(
    ImageChops.multiply(wr.point(lambda v: 255 if v >= 255 else 0),
                        wg.point(lambda v: 255 if v <= 0 else 0)),
    wb.point(lambda v: 255 if v >= 255 else 0))
nbg = sum(1 for v in bgmask.getdata() if v)
print(f"外部背景 {nbg} px ({nbg * 100 // (W * H)}%)")
if nbg < W * H * 0.05:
    raise SystemExit("背景识别异常：泛洪没吃到东西，检查四角是不是白色")

# ---------- 2) 边界环 ----------
ring = ImageChops.subtract(bgmask.filter(ImageFilter.MaxFilter(3)), bgmask)
nring = sum(1 for v in ring.getdata() if v)
print(f"边界环 {nring} px")

# ---------- 3) alpha 重建 ----------
r0, g0, b0 = im.split()
mn = ImageChops.darker(ImageChops.darker(r0, g0), b0)
ring_alpha = mn.point(lambda v: 255 if v <= A0 else int((255 - v) * 255 / (255 - A0)))

alpha = Image.new('L', (W, H), 255)
alpha.paste(0, (0, 0), bgmask)
alpha.paste(ring_alpha, (0, 0), ring)
alpha = alpha.point(lambda v: 0 if v < A_CUT else v)

# ---------- 4) 去白污染 + 合成 RGBA ----------
px = list(im.getdata())
al = list(alpha.getdata())
buf = bytearray(W * H * 4)
for i in range(W * H):
    a = al[i]
    if a == 0:
        continue
    rr, gg, bb = px[i]
    o = i * 4
    if a == 255:
        buf[o] = rr; buf[o + 1] = gg; buf[o + 2] = bb; buf[o + 3] = 255
        continue
    af = a / 255.0
    inv = (1.0 - af) * 255.0        # 白底的贡献
    nr = int((rr - inv) / af)
    ng = int((gg - inv) / af)
    nb = int((bb - inv) / af)
    buf[o]     = 0 if nr < 0 else (255 if nr > 255 else nr)
    buf[o + 1] = 0 if ng < 0 else (255 if ng > 255 else ng)
    buf[o + 2] = 0 if nb < 0 else (255 if nb > 255 else nb)
    buf[o + 3] = a

rgba = Image.frombytes('RGBA', (W, H), bytes(buf))

# ---------- 5) 找角色（最大连通块），丢掉原图气泡 ----------
binm = rgba.getchannel('A').point(lambda v: 255 if v > 0 else 0)
probe = binm.copy()
seed = None
for y in range(H - 8, 0, -4):
    hit = False
    for x in range(W - 8, 0, -4):
        if probe.getpixel((x, y)) > 0:
            seed = (x, y); hit = True; break
    if hit:
        break
print("角色种子", seed)
ImageDraw.floodfill(probe, seed, 128, thresh=100)
charmask = probe.point(lambda v: 255 if v == 128 else 0)
bbox = charmask.getbbox()
print("角色 bbox", bbox, "区域占比 %.1f%%" % ((bbox[2]-bbox[0])*(bbox[3]-bbox[1])*100.0/(W*H)))

# ---------- 6) 裁切输出 ----------
l, t, rr_, bb_ = bbox
l = max(0, l - PAD); t = max(0, t - PAD)
rr_ = min(W, rr_ + PAD); bb_ = min(H, bb_ + PAD)
cm = Image.new('L', (W, H), 0)
cm.paste(charmask, (0, 0), charmask)
out = rgba.copy()
out.putalpha(ImageChops.multiply(rgba.getchannel('A'), cm))
out = out.crop((l, t, rr_, bb_))
os.makedirs(os.path.dirname(OUT), exist_ok=True)
out.save(OUT)
print(f"写出 {OUT} {out.size} {os.path.getsize(OUT)} bytes")
