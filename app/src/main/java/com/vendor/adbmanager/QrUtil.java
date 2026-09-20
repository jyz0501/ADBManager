package com.vendor.adbmanager;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

public final class QrUtil {

    private QrUtil() {
    }

    /** 配对串：格式与系统无线调试二维码一致。 */
    public static String pairingPayload(String serviceName, String code) {
        String name = serviceName == null ? "" : serviceName;
        String pwd = code == null ? "" : code;
        return "WIFI:T:ADB;S:" + name + ";P:" + pwd + ";;";
    }

    /** 生成配对二维码位图；失败返回 null。 */
    public static Bitmap pairingQr(String serviceName, String code, int size) {
        BitMatrix matrix = encode(pairingPayload(serviceName, code), size);
        if (matrix == null) return null;
        return toBitmap(matrix, size);
    }

    private static BitMatrix encode(String payload, int size) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            return new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap toBitmap(BitMatrix matrix, int size) {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }
}
