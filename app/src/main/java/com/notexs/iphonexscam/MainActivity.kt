package com.notexs.iphonexscam

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.notexs.iphonexscam.databinding.ActivityMainBinding

/**
 * MainActivity : 앱 진입점.
 *
 * 구성 요소를 조립한다:
 *   GLSurfaceView (뷰파인더)
 *     └ ColorGradeRenderer (GLSL iPhone XS 색감 렌더링 + SurfaceTexture 제공)
 *          └ CameraEngine (CameraX 프레임을 SurfaceTexture 로 공급 + ISP 억제)
 *
 * 흐름:
 *   1) 카메라 권한 요청
 *   2) GLSurfaceView/Renderer 초기화 (EGL 3.0 컨텍스트)
 *   3) Renderer 가 SurfaceTexture 준비되면 CameraEngine.startCamera()
 *   4) 셔터 -> Renderer.requestCapture() -> 색감 입힌 Bitmap -> 갤러리 저장
 *   5) LUT 버튼 -> assets/luts 의 .cube 로드/토글
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: ColorGradeRenderer
    private lateinit var cameraEngine: CameraEngine

    // assets/luts 폴더에 넣을 기본 LUT 파일명(있으면 로드). 없으면 수학적 색감만 사용.
    // [확장 포인트] 다른 .cube 를 넣고 이 경로만 바꾸면 즉시 적용된다.
    private val defaultLutAsset = "luts/iphone_xs_emulation.cube"

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startGlAndCamera()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        glSurfaceView = binding.glSurfaceView

        // 셔터 버튼
        binding.btnShutter.setOnClickListener { onShutter() }

        // LUT 토글 버튼
        binding.btnLut.setOnClickListener { onToggleLut() }

        // 권한 체크 후 시작
        if (hasCameraPermission()) {
            startGlAndCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startGlAndCamera() {
        // GLSurfaceView 초기 1회 설정 (재진입 방지)
        if (::renderer.isInitialized) return

        // EGL 3.0 컨텍스트 요청
        glSurfaceView.setEGLContextClientVersion(3)
        // RGBA8888 + 깊이/스텐실 불필요 (2D 풀스크린 쿼드)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0)

        cameraEngine = CameraEngine(this, this)

        renderer = ColorGradeRenderer(
            context = this,
            glSurfaceView = glSurfaceView,
            onSurfaceTextureReady = { surfaceTexture ->
                // GL 스레드에서 호출됨 -> 메인 스레드로 위임하여 카메라 시작
                runOnUiThread {
                    cameraEngine.startCamera(surfaceTexture) { err ->
                        Toast.makeText(
                            this,
                            "카메라 시작 실패: ${err.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // SurfaceTexture 준비 직후 기본 LUT 자동 로드 시도(있을 경우)
                    tryLoadDefaultLut()
                }
            }
        )

        glSurfaceView.setRenderer(renderer)
        // 프레임 도착 시에만 렌더 (배터리 절약)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    /** 기본 LUT 가 assets 에 존재하면 미리 로드(토글로 켤 수 있게). */
    private fun tryLoadDefaultLut() {
        renderer.loadLutFromAssets(defaultLutAsset) { ok ->
            if (ok) {
                binding.tvMode.text = "iPhone XS Color Science : MATH (LUT ready)"
            }
            // 없으면 수학적 색감만 사용 - 정상 동작
        }
    }

    private fun onShutter() {
        if (!::renderer.isInitialized) return
        renderer.requestCapture { bitmap ->
            if (bitmap == null) {
                Toast.makeText(this, "캡처 실패", Toast.LENGTH_SHORT).show()
                return@requestCapture
            }
            // 백그라운드 저장
            Thread {
                val name = ImageSaver.saveBitmapToGallery(this, bitmap)
                runOnUiThread {
                    val msg = if (name != null) "저장됨: $name" else "저장 실패"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                bitmap.recycle()
            }.start()
        }
    }

    private fun onToggleLut() {
        if (!::renderer.isInitialized) return
        if (!renderer.isLutLoaded()) {
            Toast.makeText(
                this,
                "LUT 파일이 없습니다 (assets/luts/*.cube). 수학적 색감 사용 중.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val newState = !renderer.isLutEnabled()
        renderer.setLutEnabled(newState)
        binding.tvMode.text = if (newState) {
            "iPhone XS Color Science : MATH + 3D LUT"
        } else {
            "iPhone XS Color Science : MATH"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::glSurfaceView.isInitialized && ::renderer.isInitialized) {
            glSurfaceView.onResume()
        }
    }

    override fun onPause() {
        if (::glSurfaceView.isInitialized && ::renderer.isInitialized) {
            glSurfaceView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::cameraEngine.isInitialized) {
            cameraEngine.stopCamera()
        }
        super.onDestroy()
    }
}
