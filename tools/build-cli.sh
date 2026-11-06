#!/usr/bin/env bash
#
# 纯命令行构建，不依赖 Gradle / Android Studio。
#
# 依赖：
#   - JDK 11+（javac、keytool）
#   - Android SDK build-tools（aapt2、d8、zipalign、apksigner）
#   - 一个平台的 android.jar（默认 android-34）
#   - python3（只用来把 classes.dex 塞进 APK）
#
# 用法：
#   ANDROID_JAR=/path/to/android.jar \
#   BUILD_TOOLS=/path/to/build-tools/34.0.0 \
#   ./tools/build-cli.sh
#
#   # 没有 Android SDK，只有 r8.jar 时也可以：
#   R8_JAR=/path/to/r8.jar ANDROID_JAR=/path/to/android.jar ./tools/build-cli.sh
#
# 产物：build/BTFixOffline.apk（用 build/debug.keystore 签名，
#       没有就自动生成一个；发布请换成你自己的密钥）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
OUT="$ROOT/build"
KS="$OUT/debug.keystore"

for tool in javac python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少 $tool"; exit 1; }
done

# 优先用 $BUILD_TOOLS 里的工具，没有就退回 PATH
resolve_tool() {
    if [ -x "$BUILD_TOOLS/$1" ]; then
        echo "$BUILD_TOOLS/$1"
    else
        command -v "$1" || true
    fi
}

AAPT2="$(resolve_tool aapt2)"
ZIPALIGN="$(resolve_tool zipalign)"
APKSIGNER="$(resolve_tool apksigner)"

[ -n "$AAPT2" ]    || { echo "缺少 aapt2（可在 BUILD_TOOLS 或 PATH 里）"; exit 1; }
[ -n "$ZIPALIGN" ] || { echo "缺少 zipalign（可在 BUILD_TOOLS 或 PATH 里）"; exit 1; }
[ -n "$APKSIGNER" ]|| { echo "缺少 apksigner（可在 BUILD_TOOLS 或 PATH 里）"; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "缺少 android.jar: $ANDROID_JAR"; exit 1; }

# d8 一般随 build-tools 提供；若只有 r8.jar（比如在没有 SDK 的环境里），
# 就用 R8_JAR 环境变量指定它，脚本会走 java -cp ... com.android.tools.r8.D8
if [ -x "$BUILD_TOOLS/d8" ]; then
    D8=("$BUILD_TOOLS/d8")
elif [ -n "${R8_JAR:-}" ] && [ -f "${R8_JAR:-}" ]; then
    D8=(java -cp "$R8_JAR" com.android.tools.r8.D8)
else
    echo "缺少 d8：请把 BUILD_TOOLS 指向 build-tools，或设置 R8_JAR=/path/to/r8.jar"
    exit 1
fi

rm -rf "$OUT/classes" "$OUT/stub-classes" "$OUT/dex" "$OUT/base.apk" \
       "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/BTFixOffline.apk"
mkdir -p "$OUT/classes" "$OUT/stub-classes" "$OUT/dex"

echo "[1/6] 编译 Xposed API 桩类（只用于编译，不打进 APK）"
find "$ROOT/stub" -name '*.java' > "$OUT/stub-sources.txt"
javac -nowarn -d "$OUT/stub-classes" @"$OUT/stub-sources.txt"

echo "[2/6] 编译模块源码"
find "$ROOT/app/src/main/java" -name '*.java' > "$OUT/sources.txt"
# 注意：JDK 9+ 会忽略 -bootclasspath，所以直接把 android.jar 放进 classpath，
# 这对「只做语法/签名检查、运行时由设备提供 android.*」的场景足够了。
javac -source 8 -target 8 -nowarn \
    -cp "$ANDROID_JAR:$OUT/stub-classes" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/6] 生成 dex"
# 传具体 class 文件列表而不是目录：部分环境（如 fuse 挂载的 /sdcard）下
# d8 直接遍历目录会报 "Unsupported source file type"
find "$OUT/classes" -name '*.class' > "$OUT/classfiles.txt"
"${D8[@]}" --release --min-api 26 \
    --lib "$ANDROID_JAR" \
    --output "$OUT/dex" \
    @"$OUT/classfiles.txt"

echo "[4/6] 生成基础 APK（manifest + assets）"
"$AAPT2" link \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    -A "$ROOT/app/src/main/assets" \
    --auto-add-overlay \
    -o "$OUT/base.apk"

python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    data = zin.read(item.filename)
    zi = zipfile.ZipInfo(item.filename)
    zi.compress_type = (zipfile.ZIP_STORED if item.filename == 'resources.arsc'
                        else zipfile.ZIP_DEFLATED)
    zi.external_attr = 0o644 << 16
    zout.writestr(zi, data)
with open(dex, 'rb') as f:
    zout.writestr('classes.dex', f.read())
zout.close()
zin.close()
print('    merged classes.dex')
PY

echo "[5/6] zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
if [ ! -f "$KS" ]; then
    echo "    未找到调试密钥，生成一个"
    keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000
fi
"$APKSIGNER" sign \
    --ks "$KS" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/BTFixOffline.apk" "$OUT/aligned.apk"

echo
echo "完成：$OUT/BTFixOffline.apk"