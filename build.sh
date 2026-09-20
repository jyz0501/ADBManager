




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

# 第三方 jar：本地生成配对二维码需要 ZXing（core 为纯 Java，无其它传递依赖）。
# 不入库时会走下方存在性校验并给出明确提示，避免构建中途才失败。
ZXING_JAR="$ROOT/tools/zxing-core.jar"


SRC_DIR="$ROOT/app/src/main/java"
GEN_DIR="$ROOT/app/src/main/gen"
RES_DIR="$ROOT/app/src/main/res"
ASSETS_DIR="$ROOT/app/src/main/assets"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"


for tool in "$AAPT" "$D8" "$ZIPALIGN" "$ANDROID_JAR" "$APKSIGNER" "$ZXING_JAR" "$SIGN_DIR/platform.pk8" "$SIGN_DIR/platform.x509.pem"; do
    if [ ! -e "$tool" ]; then
        echo "错误: 缺少必要文件/工具: $tool" >&2
        echo "请检查 ANDROID_HOME / BUILD_TOOLS_VERSION / COMPILE_SDK_VERSION 是否正确。" >&2
        exit 1
    fi
done

rm -rf "$ROOT/bin" "$GEN_DIR"
mkdir -p "$ROOT/bin/classes" "$ROOT/bin/apk" "$GEN_DIR" "$ASSETS_DIR"

# ---------- 产物命名：名称_版本_日期 ----------
# 版本取自 AndroidManifest，日期为构建当天（YYYYMMDD），便于按包回溯版本与构建时间。
VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
: "${VERSION_NAME:=0.0.0}"
BUILD_DATE="$(date +%Y%m%d)"
APK_NAME="ADBManager_v${VERSION_NAME}_${BUILD_DATE}.apk"
echo "版本: $VERSION_NAME (code ${VERSION_CODE:-0})  构建日期: $BUILD_DATE"

echo "Step 1: 生成 R.java -> $GEN_DIR ..."
"$AAPT" package -f -M "$MANIFEST" \
    -I "$ANDROID_JAR" \
    -S "$RES_DIR" \
    -J "$GEN_DIR"

echo "Step 2: 编译 Java（含 R.java）..."
"$JAVAC" -d "$ROOT/bin/classes" --release 11 \
    -cp "$ANDROID_JAR:$ZXING_JAR" \
    "$SRC_DIR"/com/vendor/adbmanager/*.java \
    "$GEN_DIR"/R.java

echo "Step 3: 转换为 dex..."
cd "$ROOT/bin/apk"
"$D8" --lib "$ANDROID_JAR" --output . \
    "$ROOT/bin/classes/com/vendor/adbmanager"/*.class \
    "$ZXING_JAR"
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
    --out "$ROOT/bin/apk/$APK_NAME" \
    "$ROOT/bin/apk/aligned.apk"

echo "Unsigned APK: $ROOT/bin/apk/unsigned.apk"
echo "Aligned  APK: $ROOT/bin/apk/aligned.apk"
echo "Signed   APK: $ROOT/bin/apk/$APK_NAME"


rm -f "$ROOT/bin/apk/unsigned.apk" \
      "$ROOT/bin/apk/aligned.apk" \
      "$ROOT/bin/apk/classes.dex"
echo "已清理中间产物，最终产物: $ROOT/bin/apk/$APK_NAME"
