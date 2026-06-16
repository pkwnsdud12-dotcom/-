package com.notexs.iphonexscam

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adobe/Resolve .cube 3D LUT 파서 + OpenGL ES 3.0 sampler3D 텍스처 업로더.
 *
 * 지원 형식:
 *   - "LUT_3D_SIZE <N>"  (1D LUT 미지원 - 본 앱은 3D LUT 전용 컬러그레이딩)
 *   - "TITLE", "DOMAIN_MIN", "DOMAIN_MAX" 헤더 처리
 *   - 데이터 라인: "R G B" (0.0~1.0 float)
 *
 * .cube 데이터 순서 규약: R 이 가장 빠르게 변하고(B 가 가장 느림),
 *   index = r + g*N + b*N*N  (OpenGL sampler3D 좌표계와 일치).
 *
 * [확장 포인트]
 *   추후 새로운 LUT 추가는 assets/luts/ 폴더에 .cube 파일을 넣고
 *   loadCubeLutFromAssets(context, "luts/your_lut.cube") 만 호출하면 된다.
 */
class CubeLutLoader {

    companion object {
        private const val TAG = "CubeLutLoader"
    }

    /** 파싱 결과 보관 */
    data class LutData(
        val size: Int,                  // 한 축의 격자 수 (N)
        val data: FloatArray            // RGB 평탄화 배열 (size^3 * 3)
    )

    /**
     * assets 폴더의 .cube 파일을 파싱한다.
     * 파일이 없거나 형식 오류 시 null 반환 (수학적 색감으로 폴백 가능).
     */
    fun parseCubeFromAssets(context: Context, assetPath: String): LutData? {
        return try {
            context.assets.open(assetPath).use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    parseCube(reader)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LUT 로드 실패($assetPath): ${e.message}")
            null
        }
    }

    private fun parseCube(reader: BufferedReader): LutData? {
        var size = -1
        val values = ArrayList<Float>()

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine

            when {
                line.startsWith("TITLE", ignoreCase = true) -> { /* 무시 */ }
                line.startsWith("DOMAIN_MIN", ignoreCase = true) -> { /* 0,0,0 가정 */ }
                line.startsWith("DOMAIN_MAX", ignoreCase = true) -> { /* 1,1,1 가정 */ }
                line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> {
                    Log.w(TAG, "1D LUT 는 지원하지 않음")
                }
                line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                    size = line.split(Regex("\\s+"))[1].toInt()
                }
                else -> {
                    // 데이터 라인: R G B
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        try {
                            values.add(parts[0].toFloat())
                            values.add(parts[1].toFloat())
                            values.add(parts[2].toFloat())
                        } catch (_: NumberFormatException) {
                            // 헤더 잔여 라인 등은 무시
                        }
                    }
                }
            }
        }

        if (size <= 0) {
            Log.w(TAG, "LUT_3D_SIZE 헤더 없음")
            return null
        }
        val expected = size * size * size * 3
        if (values.size != expected) {
            Log.w(TAG, "LUT 데이터 개수 불일치 (기대=$expected, 실제=${values.size})")
            return null
        }

        return LutData(size, values.toFloatArray())
    }

    /**
     * 파싱된 LUT 를 OpenGL sampler3D 텍스처로 업로드한다.
     * @return 생성된 텍스처 핸들 (실패 시 0)
     *
     * 반드시 GL 스레드(GLSurfaceView.Renderer 콜백 내부)에서 호출해야 한다.
     */
    fun uploadToGl(lut: LutData): Int {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        if (texId == 0) return 0

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texId)

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        // RGB float 데이터 -> GPU 업로드 (RGB16F 내부 포맷, GL_RGB/GL_FLOAT)
        val buffer = ByteBuffer
            .allocateDirect(lut.data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(lut.data)
        buffer.position(0)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB16F,           // 내부 포맷 (float 정밀도 보존)
            lut.size, lut.size, lut.size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_FLOAT,
            buffer
        )

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        GLUtil.checkGlError("uploadLut")
        return texId
    }
}
