#!/bin/bash
# 手工构建「鲸鲸余额表」debug APK
# 复用 BlackPocket 那套不需要 aapt2 的流水线（本容器 arm64，Google 只发 x86_64 的 aapt2）
set -e
PROJ=/workspace/ds-hud/app
OUT=/workspace/ds-hud/build
JAR=/opt/sdk34/android.jar
TC=/opt/tc
LB=$(cat $TC/ldpath.txt)
AAPT="$TC/usr/bin/aapt"
ZIPALIGN="$TC/usr/bin/zipalign"
APKSIGNER="java -cp $TC/usr/share/java/apksigner-31.0.2.jar:$TC/usr/share/java/apksig-0.9.jar com.android.apksigner.ApkSignerTool"
R8=/opt/dl/r8.jar
PKG=com.example.whalehud
PKGPATH=$(echo $PKG | tr '.' '/')

# ---- 0. 备份上一版产物 ----
# 注意：必须在 rm -rf $OUT 之前执行，且备份目录不能放在 $OUT 内部（否则会被一起删掉）
HIST=/workspace/ds-hud/_history
mkdir -p $HIST
if [ -f "$OUT/WhaleHud-debug.apk" ]; then
    cp "$OUT/WhaleHud-debug.apk" "$HIST/WhaleHud-$(date +%Y%m%d-%H%M%S).apk"
    echo "== 0. 上一版产物已备份到 _history/ =="
fi

rm -rf $OUT; mkdir -p $OUT/gen $OUT/classes $OUT/dex
cd $OUT

echo "== 1. 生成构建用清单（补 package / uses-sdk）=="
python3 - "$PROJ/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" "$PKG" <<'PY'
import sys
src, dst, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
x = open(src, encoding='utf-8').read()
x = x.replace(
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s"\n'
    '    android:versionCode="5" android:versionName="2.1.2">\n'
    '    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34" />' % pkg)
open(dst, 'w', encoding='utf-8').write(x)
print("   manifest ok:", pkg)
PY

echo "== 2. aapt: 生成 R.java =="
LD_LIBRARY_PATH=$LB $AAPT package -f -m -J $OUT/gen -M $OUT/AndroidManifest.xml \
    -S $PROJ/src/main/res -I $JAR

echo "== 3. aapt: 打包资源 -> app.ap_ =="
# -A 必须带上，否则 assets/ 目录（WebView 的 settings.html）不会进包
LD_LIBRARY_PATH=$LB $AAPT package -f -M $OUT/AndroidManifest.xml \
    -S $PROJ/src/main/res -A $PROJ/src/main/assets -I $JAR -F $OUT/app.ap_

echo "== 4. javac 编译 =="
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $JAR -d $OUT/classes \
      $OUT/gen/$PKGPATH/R.java $(find $PROJ/src/main/java -name '*.java') 2>$OUT/javac.log || {
    echo "   (bootclasspath 方式失败，改用 --release 8)"; cat $OUT/javac.log
    javac -encoding UTF-8 --release 8 -nowarn -cp $JAR -d $OUT/classes \
          $OUT/gen/$PKGPATH/R.java $(find $PROJ/src/main/java -name '*.java')
}
echo "   编译产物:"; find $OUT/classes -name '*.class' | sed 's/^/     /'

echo "== 5. D8 -> classes.dex =="
java -cp $R8 com.android.tools.r8.D8 --min-api 29 --lib $JAR --output $OUT/dex \
     $(find $OUT/classes -name '*.class') 2>&1 | tail -3
ls -la $OUT/dex/

echo "== 6. classes.dex 塞进 apk =="
cd $OUT/dex && LD_LIBRARY_PATH=$LB $AAPT add $OUT/app.ap_ classes.dex

echo "== 7. zipalign =="
LD_LIBRARY_PATH=$LB $ZIPALIGN -f 4 $OUT/app.ap_ $OUT/app-aligned.apk && echo "   aligned"

echo "== 8. 签名 =="
KS=/workspace/build/debug.keystore
[ -f $KS ] || { echo "缺少 debug.keystore"; exit 1; }
# 注：apksigner 在 minSdk>=28 时默认只签 v3（v3 已覆盖 v2 的能力）；
# Android 9+ 均支持 v3 校验，本例目标机 API 29 可直接安装。
$APKSIGNER sign --ks $KS --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out $OUT/WhaleHud-debug.apk $OUT/app-aligned.apk

echo "== 9. 校验 =="
$APKSIGNER verify --verbose $OUT/WhaleHud-debug.apk | head -6
ls -la $OUT/WhaleHud-debug.apk
echo "== 完成 =="
