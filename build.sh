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
# 默认使用 AOSP 公开的 platform 测试密钥（公签），首次构建时自动下载到 $SIGN_DIR 缓存。
# 想保证「一定是公签」而不是 sign/ 里的某把私有密钥：USE_AOSP_TESTKEY=1 ./build.sh
# 可用环境变量覆盖默认路径，方便把密钥放在仓库外：
#   PLATFORM_PK8 / PLATFORM_PEM                      pk8 + x509.pem 组合（优先）
#   PLATFORM_KS / PLATFORM_KS_ALIAS / _STORE_PASS / _KEY_PASS   keystore / jks 组合
# AOSP 公签证书的独立缓存目录（只在 USE_AOSP_TESTKEY=1 时使用）。
AOSP_DIR="$SIGN_DIR/aosp"
PK8="${PLATFORM_PK8:-$SIGN_DIR/platform.pk8}"
PEM="${PLATFORM_PEM:-$SIGN_DIR/platform.x509.pem}"
KS="${PLATFORM_KS:-$SIGN_DIR/platform.jks}"
KS_ALIAS="${PLATFORM_KS_ALIAS:-platform}"
KS_STORE_PASS="${PLATFORM_KS_STORE_PASS:-android}"
KS_KEY_PASS="${PLATFORM_KS_KEY_PASS:-$KS_STORE_PASS}"

# AOSP 公签证书的下载源（想换源：设置环境变量 AOSP_KEY_BASE，备用源 AOSP_KEY_BASE_MIRROR）。
AOSP_KEY_BASE="${AOSP_KEY_BASE:-https://raw.githubusercontent.com/aosp-mirror/platform_build/main/target/product/security}"
AOSP_KEY_BASE_MIRROR="${AOSP_KEY_BASE_MIRROR:-https://raw.githubusercontent.com/aosp-mirror/platform_build/android-14.0.0_r1/target/product/security}"

# AOSP 公开测试密钥的证书指纹：检测到用它时会给出提示（量产 ROM 装不上）。
AOSP_TESTKEY_SHA256="C8A2E9BCCF597C2FB6DC66BEE293FC13F2FC47EC77BC6B2B0D52C11F51192AB8"

SIGN_MODE=""   # pk8 | ks：实际使用的密钥形态
SIGN_FPR=""    # 期望的签名证书 SHA-256（构建后用来核对产物）

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

# 确保签名密钥就位：优先本地密钥，缺失时从 AOSP 仓库下载公开 platform 密钥。
ensureSigningKeys() {
    mkdir -p "$SIGN_DIR"

    # 明确要求「只用公签」时，忽略 sign/ 里可能存在的私有密钥。
    if [ "${USE_AOSP_TESTKEY:-0}" = "1" ]; then
        mkdir -p "$AOSP_DIR"
        if [ "$(pemSha256 "$AOSP_DIR/platform.x509.pem")" != "$AOSP_TESTKEY_SHA256" ]; then
            echo "正在下载 AOSP 公签证书 -> $AOSP_DIR ..."
            downloadAospKeys "$AOSP_DIR" || {
                echo "错误: 无法获取 AOSP 公签证书(网络不可达或源已变更)。" >&2
                exit 1
            }
        fi
        PK8="$AOSP_DIR/platform.pk8"
        PEM="$AOSP_DIR/platform.x509.pem"
        SIGN_MODE="pk8"
        echo "签名密钥: USE_AOSP_TESTKEY=1，强制使用 AOSP 公签证书 ($AOSP_DIR)"
        return 0
    fi

    if [ -s "$PK8" ] && [ -s "$PEM" ]; then
        SIGN_MODE="pk8"
        echo "签名密钥: 使用本地 platform 密钥 私钥=$PK8 证书=$PEM"
        return 0
    fi
    if [ -s "$KS" ]; then
        SIGN_MODE="ks"
        echo "签名密钥: 使用本地 keystore $KS (alias=$KS_ALIAS)"
        return 0
    fi
    echo "未发现本地 platform 密钥，正在下载 AOSP 公开 platform 测试密钥..."
    SIGN_MODE="pk8"
    if downloadAospKeys "$SIGN_DIR"; then
        echo "签名密钥: 已下载 AOSP 公签证书 -> $SIGN_DIR"
        return 0
    fi
    echo "错误: 无法获取 platform 签名密钥(网络不可达或源已变更)。" >&2
    echo "可手动放置 platform.pk8 / platform.x509.pem 到 $SIGN_DIR, 或用 AOSP_KEY_BASE 指定其它源。" >&2
    exit 1
}

# 读出即将使用的签名证书指纹，供构建后核对（避免"以为换了 key，产物其实还是旧的"）。
resolveSigningFingerprint() {
    if [ "$SIGN_MODE" = "ks" ]; then
        SIGN_FPR="$(keytool -list -v -keystore "$KS" -storepass "$KS_STORE_PASS" -alias "$KS_ALIAS" 2>/dev/null \
            | sed -n 's/.*SHA256: *//p' | head -1 | normFpr)"
        [ -n "$SIGN_FPR" ] || {
            echo "错误: 无法从 keystore 读取证书，请检查 PLATFORM_KS_ALIAS / PLATFORM_KS_STORE_PASS。" >&2
            exit 1
        }
    else
        # 私钥与证书必须是同一对：两者公钥指纹一致才能签名成功。
        local pubFromKey pubFromCert
        pubFromKey="$(openssl pkcs8 -in "$PK8" -inform DER -nocrypt 2>/dev/null \
            | openssl rsa -pubout -outform DER 2>/dev/null | dgstHex)"
        pubFromCert="$(openssl x509 -in "$PEM" -pubkey -noout 2>/dev/null \
            | openssl rsa -pubin -pubout -outform DER 2>/dev/null | dgstHex)"
        [ -n "$pubFromKey" ] || {
            echo "错误: 无法读取私钥 $PK8（需无口令的 DER 格式 pk8）。" >&2
            exit 1
        }
        [ -n "$pubFromCert" ] && [ "$pubFromKey" = "$pubFromCert" ] || {
            echo "错误: 私钥与证书不是同一对密钥（$PK8 与 $PEM 不匹配）。" >&2
            exit 1
        }
        SIGN_FPR="$(openssl x509 -in "$PEM" -outform DER 2>/dev/null | dgstHex)"
        echo "密钥配对校验通过（公钥指纹 $(printf '%s' "$pubFromKey" | cut -c1-16)…）"
    fi
    echo "签名证书 SHA-256: $SIGN_FPR"
    if [ "$SIGN_FPR" = "$AOSP_TESTKEY_SHA256" ]; then
        echo "提示: 当前是 AOSP 公签证书，只能装在使用 test-keys 的 ROM 上；"
        echo "      量产 ROM(release-keys) 的车机会因签名不匹配装不上，需换该车型的 platform 私钥。"
    fi
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
resolveSigningFingerprint

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
if [ "$SIGN_MODE" = "ks" ]; then
    java -jar "$APKSIGNER" sign \
        --ks "$KS" \
        --ks-key-alias "$KS_ALIAS" \
        --ks-pass "pass:$KS_STORE_PASS" \
        --key-pass "pass:$KS_KEY_PASS" \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled false \
        --v4-signing-enabled false \
        --out "$ROOT/bin/apk/$APK_NAME" \
        "$ROOT/bin/apk/aligned.apk"
else
    java -jar "$APKSIGNER" sign \
        --key "$PK8" \
        --cert "$PEM" \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled false \
        --v4-signing-enabled false \
        --out "$ROOT/bin/apk/$APK_NAME" \
        "$ROOT/bin/apk/aligned.apk"
fi

echo "Step 8: 校验产物签名..."
VERIFY_OUT="$(java -jar "$APKSIGNER" verify -v --print-certs "$ROOT/bin/apk/$APK_NAME" 2>&1)" || {
    echo "$VERIFY_OUT" >&2
    echo "错误: 产物签名校验失败。" >&2
    exit 1
}
APK_FPR="$(printf '%s\n' "$VERIFY_OUT" \
    | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | normFpr)"
printf '%s\n' "$VERIFY_OUT" | grep -E "^(Verifies|Verified using)|DN:" || true
if [ -n "$SIGN_FPR" ] && [ "$APK_FPR" != "$SIGN_FPR" ]; then
    echo "错误: 产物指纹($APK_FPR)与签名密钥指纹($SIGN_FPR)不一致，请检查密钥是否被替换。" >&2
    exit 1
fi
echo "产物签名校验通过，指纹: $APK_FPR"

echo "Signed   APK: $ROOT/bin/apk/$APK_NAME"


rm -f "$ROOT/bin/apk/unsigned.apk" \
      "$ROOT/bin/apk/aligned.apk" \
      "$ROOT/bin/apk/classes.dex"
echo "已清理中间产物，最终产物: $ROOT/bin/apk/$APK_NAME"
