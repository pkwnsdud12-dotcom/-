package com.notexs.iphonexscam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 색감이 입혀진 결과 Bitmap 을 갤러리(MediaStore)에 JPEG 으로 저장.
 * Android 10(API 29)+ scoped storage 대응. 별도 저장소 권한 불필요.
 */
object ImageSaver {

    private const val TAG = "ImageSaver"
    private const val ALBUM = "iPhoneXSCam"

    /** @return 저장된 파일명 (실패 시 null) */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap): String? {
        val fileName = "IXS_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                Log.e(TAG, "MediaStore URI 생성 실패")
                return null
            }

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    throw RuntimeException("JPEG 압축 실패")
                }
            } ?: throw RuntimeException("OutputStream 열기 실패")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "저장 완료: $fileName")
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "저장 실패", e)
            resolver.delete(uri, null, null)
            null
        }
    }
}
