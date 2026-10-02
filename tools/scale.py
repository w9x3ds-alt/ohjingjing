#!/usr/bin/env python3
# 纯 Python 图片缩放（2x 降采样，使用预乘 alpha 避免边缘发黑）
import struct, zlib, sys

def load_png(path):
    d = open(path, 'rb').read()
    i, idat, plte = 8, b'', None
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
        i += 12 + ln
    raw = zlib.decompress(idat)
    nch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[CT]
    stride = W*nch
    prev = bytearray(stride)
    rows = []
    p = 0
    for y in range(H):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for x in range(nch, stride): line[x] = (line[x]+line[x-nch]) & 255
        elif f == 2:
            for x in range(stride): line[x] = (line[x]+prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x-nch] if x >= nch else 0
                line[x] = (line[x]+((a+prev[x])>>1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x-nch] if x >= nch else 0
                b = prev[x]; c = prev[x-nch] if x >= nch else 0
                pa, pb, pc = abs(b-c), abs(a-c), abs(a+b-2*c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x]+pr) & 255
        rows.append(bytes(line)); prev = line
    out = bytearray(W*H*4)
    for y in range(H):
        line = rows[y]; base = y*W*4
        if CT == 6:
            out[base:base+W*4] = line
        elif CT == 2:
            for x in range(W):
                out[base+x*4:base+x*4+3] = line[x*3:x*3+3]; out[base+x*4+3] = 255
        elif CT == 3:
            for x in range(W):
                idx = line[x]
                out[base+x*4:base+x*4+3] = plte[idx*3:idx*3+3]; out[base+x*4+3] = 255
        else:
            for x in range(W):
                v = line[x]
                out[base+x*4] = out[base+x*4+1] = out[base+x*4+2] = v; out[base+x*4+3] = 255
    return W, H, out

def save_png(path, W, H, rgba):
    raw = bytearray()
    stride = W*4
    for y in range(H):
        raw.append(0); raw += rgba[y*stride:(y+1)*stride]
    def chunk(t, data):
        return struct.pack('>I', len(data)) + t + data + struct.pack('>I', zlib.crc32(t+data) & 0xffffffff)
    out = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
    open(path, 'wb').write(out)
    print(f"  写出 {path} {W}x{H} {len(out)} bytes")

def down2(W, H, src):
    nw, nh = W//2, H//2
    out = bytearray(nw*nh*4)
    for y in range(nh):
        for x in range(nw):
            ar = ag = ab = aa = 0
            for dy in (0, 1):
                for dx in (0, 1):
                    si = ((y*2+dy)*W + (x*2+dx))*4
                    a = src[si+3]
                    ar += src[si]*a; ag += src[si+1]*a; ab += src[si+2]*a; aa += a
            di = (y*nw+x)*4
            if aa == 0:
                out[di] = out[di+1] = out[di+2] = out[di+3] = 0
            else:
                out[di] = min(255, ar//aa); out[di+1] = min(255, ag//aa); out[di+2] = min(255, ab//aa)
                out[di+3] = min(255, aa//4)
    return nw, nh, out

for name in sys.argv[1:]:
    src = f"assets/{name}.png"
    W, H, rgba = load_png(src)
    w, h, small = down2(W, H, rgba)
    save_png(f"assets/{name}_h.png", w, h, small)
