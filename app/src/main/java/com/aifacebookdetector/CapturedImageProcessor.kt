package com.aifacebookdetector

import android.graphics.Bitmap
import android.util.Log

object CapturedImageProcessor {
    private const val TAG = "CapturedImageProcessor"

    /**
     * Điểm nhận Bitmap sau khi người dùng chủ động chạm nút nổi.
     * Bước xử lý tiếp theo (lưu file, phân tích trong app, v.v.) gắn vào đây.
     */
    fun processCapturedImage(bitmap: Bitmap) {
        Log.i(TAG, "Captured bitmap ${bitmap.width}x${bitmap.height}")
    }
}
