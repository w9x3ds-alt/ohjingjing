#!/usr/bin/env python3
# 纯 Python PNG 抠图：白底转透明 + 边缘羽化 + 组件分离
# 不依赖 PIL / ImageMagick
import struct, zlib, sys
from collections import deque

SRC = sys.argv[1] if len(sys.argv) > 1 else 'src.png'
OUTDIR = sys.argv[2] if len(sys.argv) > 2 else 'assets'
WHITE_MIN = 240      # 判定为"白底"的最低通道值
RING_DARK = 90.0     # 羽化参考：越接近黑 alpha 越接近 1

# ---------- 解码 ----------
def load_png(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n'
    i, idat, plte, trns = 8, b'', None, None
    W = H = BD = CT = IL = 0
    while i < len(d):
        ln = struct.unpack('>I', d[i:i+4])[0]
        typ = d[i+4:i+8]
        data = d[i+8:i+8+ln]
        if typ == b'IHDR':
            W, H, BD, CT, cm, fm, IL = struct.unpack('>IIBBBBB', data)
        elif typ == b'IDAT':
            idat += data
        elif typ == b'PLTE':
            plte = data
        elif typ == b'tRNS':
            trns = data
        i += 12 + ln
    assert IL == 0, "不支持隔行扫描"
    assert BD == 8, "暂时只支持 8bit"
    raw = zlib.decompress(idat)
    nch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[CT]
    stride = W * nch
    prev = bytearray(stride)
    rows = []
    p = 0
    for y in range(H):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for x in range(nch, stride):
                line[x] = (line[x] + line[x-nch]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x-nch] if x >= nch else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x-nch] if x >= nch else 0
                b = prev[x]
                c = prev[x-nch] if x >= nch else 0
                pa, pb, pc = abs(b-c), abs(a-c), abs(a+b-2*c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        rows.append(bytes(line))
        prev = line
    # 转成 RGB
    rgb = bytearray(W*H*3)
    if CT == 3:
        for y in range(H):
            line = rows[y]
            base = y*W*3
            for x in range(W):
                idx = line[x]
                rgb[base+x*3] = plte[idx*3]
                rgb[base+x*3+1] = plte[idx*3+1]
                rgb[base+x*3+2] = plte[idx*3+2]
    elif CT == 2:
        rgb = bytearray(b''.join(rows))
    elif CT == 6:
        for y in range(H):
            line = rows[y]
            base = y*W*3
            for x in range(W):
                rgb[base+x*3:base+x*3+3] = line[x*4:x*4+3]
    elif CT == 0:
        for y in range(H):
            line = rows[y]; base = y*W*3
            for x in range(W):
                v = line[x]
                rgb[base+x*3] = rgb[base+x*3+1] = rgb[base+x*3+2] = v
    else:
        raise SystemExit("未支持的色彩类型 %d" % CT)
    return W, H, rgb

# ---------- 编码 RGBA ----------
def save_png(path, W, H, rgba):
    raw = bytearray()
    stride = W*4
    for y in range(H):
        raw.append(0)
        raw += rgba[y*stride:(y+1)*stride]
    def chunk(t, data):
        c = struct.pack('>I', len(data)) + t + data
        return c + struct.pack('>I', zlib.crc32(t+data) & 0xffffffff)
    out = b'\x89PNG\r\n\x1a\n'
    out += chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
    out += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    out += chunk(b'IEND', b'')
    open(path, 'wb').write(out)
    print("  写出", path, f"{W}x{H}", len(out), "bytes")

# ---------- 主流程 ----------
print("1) 解码")
W, H, rgb = load_png(SRC)
print(f"   {W}x{H}")

print("2) 从四边泛洪，标记外部白底")
is_white = bytearray(W*H)
for i in range(W*H):
    r, g, b = rgb[i*3], rgb[i*3+1], rgb[i*3+2]
    if r >= WHITE_MIN and g >= WHITE_MIN and b >= WHITE_MIN:
        is_white[i] = 1

bg = bytearray(W*H)
dq = deque()
for x in range(W):
    for y in (0, H-1):
        i = y*W+x
        if is_white[i] and not bg[i]:
            bg[i] = 1; dq.append(i)
for y in range(H):
    for x in (0, W-1):
        i = y*W+x
        if is_white[i] and not bg[i]:
            bg[i] = 1; dq.append(i)
while dq:
    i = dq.popleft()
    x, y = i % W, i // W
    for nx, ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
        if 0 <= nx < W and 0 <= ny < H:
            j = ny*W+nx
            if not bg[j] and is_white[j]:
                bg[j] = 1; dq.append(j)
print("   背景像素:", sum(bg), "/", W*H)

print("3) 生成 alpha + 边缘羽化")
alpha = bytearray(W*H)
for i in range(W*H):
    alpha[i] = 0 if bg[i] else 255
# 环状羽化：紧邻透明区的实心像素，按暗度给半透明
for y in range(H):
    for x in range(W):
        i = y*W+x
        if bg[i]:
            continue
        nb = False
        for nx, ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
            if 0 <= nx < W and 0 <= ny < H and bg[ny*W+nx]:
                nb = True; break
        if nb:
            r, g, b = rgb[i*3], rgb[i*3+1], rgb[i*3+2]
            dark = 255 - min(r, g, b)
            a = dark / (255 - RING_DARK) * 255
            alpha[i] = max(40, min(255, int(a)))

rgba = bytearray(W*H*4)
for i in range(W*H):
    rgba[i*4:i*4+3] = rgb[i*3:i*3+3]
    rgba[i*4+3] = alpha[i]

print("4) 连通组件分离（气泡 vs 角色）")
label = [-1]*(W*H)
comps = []
for start in range(W*H):
    if alpha[start] > 0 and label[start] < 0:
        cid = len(comps)
        dq = deque([start]); label[start] = cid
        minx = maxx = start % W; miny = maxy = start // W; n = 0
        while dq:
            i = dq.popleft(); n += 1
            x, y = i % W, i // W
            if x < minx: minx = x
            if x > maxx: maxx = x
            if y < miny: miny = y
            if y > maxy: maxy = y
            for nx, ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
                if 0 <= nx < W and 0 <= ny < H:
                    j = ny*W+nx
                    if alpha[j] > 0 and label[j] < 0:
                        label[j] = cid; dq.append(j)
        comps.append((n, cid, minx, miny, maxx, maxy))
comps.sort(reverse=True)
for k, c in enumerate(comps[:8]):
    print(f"   组件{k}: cid={c[1]} 像素{c[0]:>7}  bbox=({c[2]},{c[3]})-({c[4]},{c[5]})")
CHAR_CID = comps[0][1] if comps else 0          # 最大连通块 = 角色
print("   角色 cid =", CHAR_CID)

# 裁剪辅助
def crop(maskfn, name):
    minx, miny, maxx, maxy = W, H, 0, 0
    for y in range(H):
        for x in range(W):
            i = y*W+x
            if alpha[i] > 0 and maskfn(i, label):
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
    pad = 4
    minx = max(0, minx-pad); miny = max(0, miny-pad)
    maxx = min(W-1, maxx+pad); maxy = min(H-1, maxy+pad)
    w, h = maxx-minx+1, maxy-miny+1
    out = bytearray(w*h*4)
    for y in range(h):
        for x in range(w):
            si = (y+miny)*W + (x+minx)
            di = (y*w+x)*4
            if alpha[si] > 0 and maskfn(si, label):
                out[di:di+4] = rgba[si*4:si*4+4]
    save_png(f"{OUTDIR}/{name}.png", w, h, out)
    return w, h

import os
os.makedirs(OUTDIR, exist_ok=True)

print("5) 输出")
save_png(f"{OUTDIR}/whale_full.png", W, H, rgba)
crop(lambda i, lb: True, "whale_all")                          # 气泡 + 角色
crop(lambda i, lb: lb[i] == CHAR_CID, "whale_char")            # 只留角色
print("完成")
