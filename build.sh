#!/usr/bin/env bash
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
# 本应用是 platform 签名的系统应用（manifest 里 sharedUserId=android.uid.system），
# APK 签名必须与车机 ROM 的 platform 密钥一致，否则安装时报
# INSTALL_FAILED_SHARED_USER_INCOMPATIBLE（界面显示"应用未安装 / 签名错误"）。
# 统一使用 AOSP 公开的 platform 测试密钥（公签），首次构建时自动下载到 $SIGN_DIR 缓存。
# 可用环境变量覆盖默认路径，方便把密钥放在仓库外：
#   PLATFORM_PK8 / PLATFORM_PEM                      pk8 + x509.pem 组合
AOSP_DIR="$SIGN_DIR/aosp"
PK8="${PLATFORM_PK8:-$AOSP_DIR/platform.pk8}"
PEM="${PLATFORM_PEM:-$AOSP_DIR/platform.x509.pem}"

# AOSP 公签证书的下载源（想换源：设置环境变量 AOSP_KEY_BASE，备用源 AOSP_KEY_BASE_MIRROR）。
AOSP_KEY_BASE="${AOSP_KEY_BASE:-https://raw.githubusercontent.com/aosp-mirror/platform_build/main/target/product/security}"
AOSP_KEY_BASE_MIRROR="${AOSP_KEY_BASE_MIRROR:-https://raw.githubusercontent.com/aosp-mirror/platform_build/android-14.0.0_r1/target/product/security}"

# AOSP 公开测试密钥的证书指纹：检测到用它时会给出提示（量产 ROM 装不上）。
AOSP_TESTKEY_SHA256="C8A2E9BCCF597C2FB6DC66BEE293FC13F2FC47EC77BC6B2B0D52C11F51192AB8"

# 签名证书 SHA-256（构建后核对用）。
AOSP_FPR=""

# 指纹统一格式：大写、无冒号，便于字符串比较。
normFpr() { tr -d ' :' | tr 'a-f' 'A-F'; }
# openssl dgst 在不同版本下输出形如 "SHA2-256(stdin)= hex" / "(stdin)= hex"，取末段即可。
dgstHex() { openssl dgst -sha256 2>/dev/null | awk -F'= ' '{print $NF}' | normFpr; }

pemSha256() { openssl x509 -in "$1" -outform DER 2>/dev/null | dgstHex; }

# 从 AOSP 仓库下载公开 platform 测试密钥到指定目录。
downloadAospKeys() {
    local dir="$1"
    for base in "$AOSP_KEY_BASE" "$AOSP_KEY_BASE_MIRROR"; do
        [ -n "$base" ] || continue
        rm -f "$dir/platform.pk8" "$dir/platform.x509.pem"
        if curl -fsSL --connect-timeout 15 "$base/platform.pk8" -o "$dir/platform.pk8" \
            && curl -fsSL --connect-timeout 15 "$base/platform.x509.pem" -o "$dir/platform.x509.pem"; then
            return 0
        fi
        echo "警告：从 $base 下载失败，尝试下一个源..." >&2
    done
    rm -f "$dir/platform.pk8" "$dir/platform.x509.pem"
    return 1
}

# 确保 AOSP 公签密钥就位，缺失时自动下载。
ensureAospKeys() {
    mkdir -p "$AOSP_DIR"
    if [ "$(pemSha256 "$PEM")" != "$AOSP_TESTKEY_SHA256" ] || [ ! -s "$PK8" ]; then
        echo "正在下载 AOSP 公签证书 -> $AOSP_DIR ..."
        downloadAospKeys "$AOSP_DIR" || {
            echo "错误: 无法获取 AOSP 公签证书(网络不可达或源已变更)。" >&2
            exit 1
        }
    fi
    AOSP_FPR="$(pemSha256 "$PEM")"
    echo "签名密钥: AOSP 公签证书 ($AOSP_DIR)"
    echo "签名证书 SHA-256: $AOSP_FPR"
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

ensureAospKeys

rm -rf "$ROOT/bin" "$GEN_DIR"
mkdir -p "$ROOT/bin/classes" "$ROOT/bin/apk" "$GEN_DIR" "$ASSETS_DIR"

# ---------- 产物命名：应用名_版本号_日期_AOSP-Platform ----------
# 版本取自 AndroidManifest，日期为构建当天（YYYYMMDD），便于按包回溯版本与构建时间。
VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
: "${VERSION_NAME:=0.0.0}"
BUILD_DATE="$(date +%Y%m%d)"
VERSION_SUFFIX="AOSP-Platform"
APK_NAME="ADBManager_v${VERSION_NAME}_${BUILD_DATE}_${VERSION_SUFFIX}.apk"
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

# ---------- 签名与校验（启用 v1+v2 签名方案） ----------
SIGN_COMMON_ARGS=(--v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false)

# verifyApk <APK> <期望指纹> <标签>
# 注意：不用 head -1 / grep -q 提前关闭管道，set -o pipefail 下会触发 SIGPIPE 静默退出。
verifyApk() {
    local apk="$1" want="$2" label="$3"
    local verify_out apk_fpr keytool_out
    verify_out="$(java -jar "$APKSIGNER" verify -v --print-certs "$apk" 2>&1 || true)"
    apk_fpr="$(printf '%s\n' "$verify_out" \
        | awk '/certificate SHA-256 digest:/ {sub(/.*certificate SHA-256 digest: */,""); print; exit}' | normFpr)"
    if [ -z "$apk_fpr" ]; then
        # apksigner 拒绝校验时，回退用 keytool 直接读取 v1(JAR) 签名证书。
        keytool_out="$(keytool -printcert -jarfile "$apk" 2>/dev/null)" || true
        apk_fpr="$(printf '%s\n' "$keytool_out" \
            | awk '/SHA256:/ {sub(/.*SHA256: */,""); print; exit}' | normFpr)"
    fi
    [ -n "$apk_fpr" ] || {
        echo "错误: $label 无法读取签名证书指纹。" >&2
        exit 1
    }
    if [ "$apk_fpr" != "$want" ]; then
        echo "错误: $label 产物指纹($apk_fpr)与签名密钥指纹($want)不一致，请检查密钥是否被替换。" >&2
        exit 1
    fi
    echo "$label 签名校验通过，指纹: $apk_fpr"
}

# Step 7: 签名（AOSP platform 公签，v1+v2）
echo "Step 7: 签名（AOSP platform 公签，v1+v2）..."
java -jar "$APKSIGNER" sign \
    --key "$PK8" \
    --cert "$PEM" \
    "${SIGN_COMMON_ARGS[@]}" \
    --out "$ROOT/bin/apk/$APK_NAME" "$ROOT/bin/apk/aligned.apk"
verifyApk "$ROOT/bin/apk/$APK_NAME" "$AOSP_FPR" "AOSP-Platform"
echo "产物: $ROOT/bin/apk/$APK_NAME"
echo "提示: 公签版只能装在使用 test-keys 的 ROM 上，量产 ROM(release-keys) 车机会因签名不匹配装不上。"

rm -f "$ROOT/bin/apk/unsigned.apk" \
      "$ROOT/bin/apk/aligned.apk" \
      "$ROOT/bin/apk/classes.dex"
echo "已清理中间产物。"
