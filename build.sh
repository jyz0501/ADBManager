




set -euo pipefail


: "${ANDROID_HOME:=/Users/alun/Library/Android/sdk}"
: "${BUILD_TOOLS_VERSION:=36.0.0}"
: "${COMPILE_SDK_VERSION:=36}"

: "${JAVAC:=/usr/bin/javac}"

ROOT="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"

AAPT="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt"
D8="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/d8"
ZIPALIGN="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/zipalign"
ANDROID_JAR="$ANDROID_HOME/platforms/android-$COMPILE_SDK_VERSION/android.jar"

SIGN_DIR="$ROOT/sign"
APKSIGNER="$ROOT/tools/apksigner.jar"


SRC_DIR="$ROOT/app/src/main/java"
GEN_DIR="$ROOT/app/src/main/gen"
RES_DIR="$ROOT/app/src/main/res"
ASSETS_DIR="$ROOT/app/src/main/assets"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"


for tool in "$AAPT" "$D8" "$ZIPALIGN" "$ANDROID_JAR" "$APKSIGNER" "$SIGN_DIR/platform.pk8" "$SIGN_DIR/platform.x509.pem"; do
    if [ ! -e "$tool" ]; then
        echo "错误: 缺少必要文件/工具: $tool" >&2
        echo "请检查 ANDROID_HOME / BUILD_TOOLS_VERSION / COMPILE_SDK_VERSION 是否正确。" >&2
        exit 1
    fi
done

rm -rf "$ROOT/bin" "$GEN_DIR"
mkdir -p "$ROOT/bin/classes" "$ROOT/bin/apk" "$GEN_DIR" "$ASSETS_DIR"

echo "Step 1: 生成 R.java -> $GEN_DIR ..."
"$AAPT" package -f -M "$MANIFEST" \
    -I "$ANDROID_JAR" \
    -S "$RES_DIR" \
    -J "$GEN_DIR"

echo "Step 2: 编译 Java（含 R.java）..."
"$JAVAC" -d "$ROOT/bin/classes" --release 11 \
    -cp "$ANDROID_JAR" \
    "$SRC_DIR"/com/vendor/adbmanager/*.java \
    "$GEN_DIR"/R.java

echo "Step 3: 转换为 dex..."
cd "$ROOT/bin/apk"
"$D8" --lib "$ANDROID_JAR" --output . "$ROOT/bin/classes/com/vendor/adbmanager"/*.class
cd "$ROOT"

echo "Step 4: 打包 APK（resources.arsc 不压缩）..."
"$AAPT" package -f -M "$MANIFEST" \
    -I "$ANDROID_JAR" \
    -S "$RES_DIR" \
    -F "$ROOT/bin/apk/unsigned.apk" \
    -A "$ASSETS_DIR" \
    -0 arsc \
    -0 res \
    -0 AndroidManifest.xml

echo "Step 5: 加入 classes.dex..."
cd "$ROOT/bin/apk"
"$AAPT" add unsigned.apk classes.dex
cd "$ROOT"

echo "Step 6: Zipalign（4 字节对齐）..."
"$ZIPALIGN" -f 4 "$ROOT/bin/apk/unsigned.apk" "$ROOT/bin/apk/aligned.apk"

echo "Step 7: 用 platform 密钥签名..."
java -jar "$APKSIGNER" sign \
    --key "$SIGN_DIR/platform.pk8" \
    --cert "$SIGN_DIR/platform.x509.pem" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled false \
    --v4-signing-enabled false \
    --out "$ROOT/bin/apk/ADBManager_signed.apk" \
    "$ROOT/bin/apk/aligned.apk"

echo "Unsigned APK: $ROOT/bin/apk/unsigned.apk"
echo "Aligned  APK: $ROOT/bin/apk/aligned.apk"
echo "Signed   APK: $ROOT/bin/apk/ADBManager_signed.apk"


rm -f "$ROOT/bin/apk/unsigned.apk" \
      "$ROOT/bin/apk/aligned.apk" \
      "$ROOT/bin/apk/classes.dex"
echo "已清理中间产物，最终产物: $ROOT/bin/apk/ADBManager_signed.apk"
