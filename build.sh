




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

# ---------- 签名密钥 ----------
# 默认使用 AOSP 公开的 platform 测试密钥，首次构建时自动下载到 $SIGN_DIR 缓存。
# 若目标 ROM 用的是厂商私有 platform key，把自己的 platform.pk8 / platform.x509.pem
# 放进 $SIGN_DIR 即可覆盖（本地密钥存在且非空时优先使用）。
PK8="$SIGN_DIR/platform.pk8"
PEM="$SIGN_DIR/platform.x509.pem"
AOSP_KEY_BASE="${AOSP_KEY_BASE:-https://raw.githubusercontent.com/aosp-mirror/platform_build/main/target/product/security}"
AOSP_KEY_BASE_MIRROR="${AOSP_KEY_BASE_MIRROR:-https://raw.githubusercontent.com/aosp-mirror/platform_build/android-14.0.0_r1/target/product/security}"

# 确保签名密钥就位：本地缺失时从 AOSP 仓库下载公开 platform 密钥。
ensureSigningKeys() {
    mkdir -p "$SIGN_DIR"
    if [ -s "$PK8" ] && [ -s "$PEM" ]; then
        echo "签名密钥: 使用本地已有的 platform 密钥 ($SIGN_DIR)"
        return 0
    fi
    echo "未发现本地 platform 密钥，正在下载 AOSP 公开 platform 测试密钥..."
    for base in "$AOSP_KEY_BASE" "$AOSP_KEY_BASE_MIRROR"; do
        [ -n "$base" ] || continue
        rm -f "$PK8" "$PEM"
        if curl -fsSL --connect-timeout 15 "$base/platform.pk8" -o "$PK8" \
            && curl -fsSL --connect-timeout 15 "$base/platform.x509.pem" -o "$PEM"; then
            echo "签名密钥: 已下载 AOSP 公开 platform 密钥 -> $SIGN_DIR"
            return 0
        fi
        echo "警告：从 $base 下载失败，尝试下一个源..." >&2
    done
    rm -f "$PK8" "$PEM"
    echo "错误: 无法获取 platform 签名密钥(网络不可达或源已变更)。" >&2
    echo "可手动放置 platform.pk8 / platform.x509.pem 到 $SIGN_DIR, 或用 AOSP_KEY_BASE 指定其它源。" >&2
    exit 1
}


SRC_DIR="$ROOT/app/src/main/java"
GEN_DIR="$ROOT/app/src/main/gen"
RES_DIR="$ROOT/app/src/main/res"
ASSETS_DIR="$ROOT/app/src/main/assets"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"


for tool in "$AAPT" "$D8" "$ZIPALIGN" "$ANDROID_JAR" "$APKSIGNER" "$ZXING_JAR"; do
    if [ ! -e "$tool" ]; then
        echo "错误: 缺少必要文件/工具: $tool" >&2
        echo "请检查 ANDROID_HOME / BUILD_TOOLS_VERSION / COMPILE_SDK_VERSION 是否正确。" >&2
        exit 1
    fi
done

ensureSigningKeys

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
    "$SRC_DIR"/com/qianxian/adbmanager/*.java \
    "$GEN_DIR"/R.java

echo "Step 3: 转换为 dex..."
cd "$ROOT/bin/apk"
"$D8" --lib "$ANDROID_JAR" --output . \
    "$ROOT/bin/classes/com/qianxian/adbmanager"/*.class \
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
    --key "$PK8" \
    --cert "$PEM" \
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
