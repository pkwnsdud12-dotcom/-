package com.notexs.iphonexscam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView.Renderer 구현.
 *
 * 역할:
 *  1) 카메라 SurfaceTexture(GL_TEXTURE_EXTERNAL_OES) 를 생성하여 CameraEngine 에 제공
 *  2) 매 프레임 카메라 텍스처를 color_grade_shader 로 변환하여 화면에 렌더링
 *  3) 셔터 캡처 시 현재 GL 프레임버퍼를 Bitmap 으로 읽어 반환 (색감이 입혀진 결과물)
 *  4) 3D LUT 텍스처 로드/토글 인터페이스 제공
 *
 * 스레드 규약: 모든 GL 작업은 GLSurfaceView 의 GL 스레드에서 수행된다.
 *   외부 요청(LUT 로드, 캡처)은 queueEvent 또는 atomic flag 로 GL 스레드에 위임한다.
 */
class ColorGradeRenderer(
    private val context: Context,
    private val glSurfaceView: GLSurfaceView,
    /** SurfaceTexture 준비 완료 콜백 (CameraEngine 에 전달용) */
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "ColorGradeRenderer"
    }

    // ---- GL 핸들 ----
    private var program = 0
    private var oesTextureId = 0
    private var lutTextureId = 0

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uCameraTexLoc = 0
    private var uLutTexLoc = 0
    private var uUseLutLoc = 0
    private var uLutSizeLoc = 0
    private var uWarmLoc = 0
    private var uContrastLoc = 0
    private var uSkinLoc = 0

    // ---- 카메라 SurfaceTexture ----
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val frameAvailable = AtomicBoolean(false)

    // ---- 뷰포트 ----
    private var viewWidth = 0
    private var viewHeight = 0

    // ---- 색감 파라미터 (런타임 조정 가능) ----
    @Volatile var warmStrength = 1.0f
    @Volatile var contrast = 1.0f
    @Volatile var skinProtect = 1.0f

    // ---- LUT 상태 ----
    @Volatile private var useLut = false
    private var pendingLut: CubeLutLoader.LutData? = null
    private var lutSize = 33

    // ---- 캡처 요청 ----
    @Volatile private var captureRequested = false
    @Volatile private var captureCallback: ((Bitmap?) -> Unit)? = null

    private val lutLoader = CubeLutLoader()

    // 풀스크린 쿼드 정점 (x, y) + 텍스처좌표 (s, t)
    // 정점: NDC, 텍스처좌표는 SurfaceTexture 변환행렬로 보정됨
    private val vertexData = floatArrayOf(
        // X,    Y,    S,   T
        -1.0f, -1.0f, 0.0f, 0.0f,
        1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
        1.0f,  1.0f, 1.0f, 1.0f
    )
    private lateinit var vertexBuffer: FloatBuffer

    // =========================================================================
    // GLSurfaceView.Renderer
    // =========================================================================
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 정점 버퍼 구성
        vertexBuffer = ByteBuffer
            .allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(vertexData).position(0)

        // 셰이더 로드 & 프로그램 생성
        val vsSource = GLUtil.readAssetText(context, "shaders/vertex_shader.glsl")
        val fsSource = GLUtil.readAssetText(context, "shaders/color_grade_shader.glsl")
        program = GLUtil.createProgram(vsSource, fsSource)

        // attribute / uniform 위치
        aPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uCameraTexLoc = GLES30.glGetUniformLocation(program, "uCameraTexture")
        uLutTexLoc = GLES30.glGetUniformLocation(program, "uLutTexture")
        uUseLutLoc = GLES30.glGetUniformLocation(program, "uUseLut")
        uLutSizeLoc = GLES30.glGetUniformLocation(program, "uLutSize")
        uWarmLoc = GLES30.glGetUniformLocation(program, "uWarmStrength")
        uContrastLoc = GLES30.glGetUniformLocation(program, "uContrast")
        uSkinLoc = GLES30.glGetUniformLocation(program, "uSkinProtect")

        // 외부 OES 텍스처 생성 (카메라 프레임 수신용)
        oesTextureId = createOesTexture()

        // SurfaceTexture 생성 후 CameraEngine 에 전달
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener {
                frameAvailable.set(true)
                glSurfaceView.requestRender()
            }
        }
        surfaceTexture?.let { onSurfaceTextureReady(it) }

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLUtil.checkGlError("onSurfaceCreated")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 1) 보류 중인 LUT 업로드 (GL 스레드에서만 가능)
        pendingLut?.let { data ->
            if (lutTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            }
            lutTextureId = lutLoader.uploadToGl(data)
            lutSize = data.size
            useLut = lutTextureId != 0
            pendingLut = null
            Log.i(TAG, "LUT 업로드 완료 (size=$lutSize, tex=$lutTextureId)")
        }

        // 2) 새 카메라 프레임 수신
        val st = surfaceTexture ?: return
        if (frameAvailable.compareAndSet(true, false)) {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        }

        // 3) 화면 클리어 & 드로우
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawScene()

        // 4) 캡처 요청 처리 (드로우 직후 프레임버퍼 읽기)
        if (captureRequested) {
            captureRequested = false
            val bmp = readPixelsToBitmap(viewWidth, viewHeight)
            val cb = captureCallback
            captureCallback = null
            // 콜백은 메인 스레드에서 호출하도록 위임
            glSurfaceView.post { cb?.invoke(bmp) }
        }
    }

    private fun drawScene() {
        GLES30.glUseProgram(program)

        // 카메라 OES 텍스처 바인딩 -> texture unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(uCameraTexLoc, 0)

        // LUT 텍스처 바인딩 -> texture unit 1
        if (useLut && lutTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glUniform1i(uLutTexLoc, 1)
            GLES30.glUniform1i(uUseLutLoc, 1)
            GLES30.glUniform1f(uLutSizeLoc, lutSize.toFloat())
        } else {
            GLES30.glUniform1i(uUseLutLoc, 0)
            GLES30.glUniform1f(uLutSizeLoc, lutSize.toFloat())
        }

        // 변환 행렬 & 색감 파라미터
        GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES30.glUniform1f(uWarmLoc, warmStrength)
        GLES30.glUniform1f(uContrastLoc, contrast)
        GLES30.glUniform1f(uSkinLoc, skinProtect)

        // 정점 attribute
        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(aTexCoordLoc)
        // aTexCoord 는 vec4 in 이지만 vec2 만 채우고 z=0,w=1 은 기본값 사용 불가하므로
        // 셰이더에서 vec4 aTexCoord 의 xy 만 사용. attrib 는 2개 컴포넌트만 전달하고
        // 나머지는 일반화 vertex attrib 기본값(0,0,0,1)을 따른다.
        GLES30.glVertexAttribPointer(aTexCoordLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aTexCoordLoc)

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    // =========================================================================
    // 외부 OES 텍스처 생성
    // =========================================================================
    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return tex[0]
    }

    // =========================================================================
    // 캡처: 현재 프레임버퍼를 Bitmap 으로 (색감 입혀진 최종 결과)
    // =========================================================================
    private fun readPixelsToBitmap(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        buf.position(0)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.position(0)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buf)

        // glReadPixels 는 좌하단 원점 -> Android Bitmap 은 좌상단 원점. 수직 반전 필요.
        val matrix = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        bitmap.recycle()
        return flipped
    }

    // =========================================================================
    // 외부 인터페이스 (메인 스레드에서 호출 -> GL 스레드로 위임)
    // =========================================================================

    /** 셔터 캡처 요청. 결과 Bitmap 은 콜백으로 전달(메인 스레드). */
    fun requestCapture(callback: (Bitmap?) -> Unit) {
        glSurfaceView.queueEvent {
            captureCallback = callback
            captureRequested = true
        }
        glSurfaceView.requestRender()
    }

    /** assets 의 .cube LUT 로드 요청 (GL 스레드에서 업로드). */
    fun loadLutFromAssets(assetPath: String, onResult: (Boolean) -> Unit) {
        val data = lutLoader.parseCubeFromAssets(context, assetPath)
        if (data == null) {
            onResult(false)
            return
        }
        glSurfaceView.queueEvent {
            pendingLut = data
        }
        glSurfaceView.requestRender()
        onResult(true)
    }

    /** LUT on/off 토글. 로드된 LUT 가 없으면 무시. */
    fun setLutEnabled(enabled: Boolean) {
        if (lutTextureId != 0 || !enabled) {
            useLut = enabled && lutTextureId != 0
            glSurfaceView.requestRender()
        }
    }

    fun isLutLoaded(): Boolean = lutTextureId != 0
    fun isLutEnabled(): Boolean = useLut
}
