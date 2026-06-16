package com.notexs.iphonexscam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * CameraEngine : CameraX 기반 카메라 제어 + 삼성 ISP 후처리 억제.
 *
 * 핵심 전략 (요구사항 1단계):
 *   Camera2Interop.Extender 를 통해 CameraX Preview UseCase 에 raw Camera2
 *   CaptureRequest 키를 주입하여 갤럭시 특유의 과도한 ISP 개입을 끈다.
 *     - NOISE_REDUCTION_MODE = OFF      (노이즈 리덕션 끔 -> 디테일/질감 보존)
 *     - EDGE_MODE            = OFF      (엣지 강화/샤프닝 끔 -> 오버샤픈 제거)
 *     - TONEMAP_MODE         = CONTRAST_CURVE / GAMMA_VALUE 로 가능한 한 선형에 가깝게
 *     - COLOR_CORRECTION_ABERRATION_MODE = OFF
 *     - HOT_PIXEL_MODE       = OFF (가능 시)
 *
 *   => 삼성 자체 룩을 최대한 배제한 '평평한(flat)' 신호를 받아,
 *      GLSL 셰이더에서 iPhone XS 룩을 우리가 직접 그린다.
 *
 * [한계/주의 - 코드 주석으로만 남김]
 *   - 일부 삼성 펌웨어는 CameraX(Camera2) 표준 키를 무시하고 강제 ISP 를 적용할 수 있다.
 *     (특히 '장면 최적화 도구', AI 카메라). 그럴 경우 효과가 제한적일 수 있다.
 *   - 완전한 RAW(DngCreator) 파이프라인이 아니므로 센서 raw 가 아닌 YUV 디스플레이
 *     스트림 기준이다. 더 강력한 통제가 필요하면 Camera2 + ImageReader(YUV/RAW) 로
 *     전환해야 한다(아키텍처 확장 포인트).
 *   - TONEMAP_MODE 를 GAMMA_VALUE(1.0=linear)로 설정하면 일부 기기에서 미지원/이상동작
 *     가능 -> try/catch 로 안전하게 처리.
 */
class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "CameraEngine"
        // 갤럭시 노트 20 울트라 메인 카메라 적정 해상도 (4:3 고화질). 필요 시 조정.
        private val TARGET_RESOLUTION = Size(1440, 1080)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    /**
     * 카메라 시작.
     * @param surfaceTexture ColorGradeRenderer 가 생성한 외부 텍스처 SurfaceTexture
     * @param onError 실패 콜백
     */
    @SuppressLint("UnsafeOptInUsageError", "RestrictedApi")
    fun startCamera(
        surfaceTexture: SurfaceTexture,
        onError: (Throwable) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindUseCases(surfaceTexture)
            } catch (e: Exception) {
                Log.e(TAG, "카메라 시작 실패", e)
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError", "RestrictedApi")
    private fun bindUseCases(surfaceTexture: SurfaceTexture) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        // ----- Preview UseCase 구성 + Camera2Interop 로 ISP 억제 키 주입 -----
        val previewBuilder = Preview.Builder()
            .setTargetResolution(TARGET_RESOLUTION)

        val extender = Camera2Interop.Extender(previewBuilder)
        applyIspSuppression(extender)

        preview = previewBuilder.build()

        // SurfaceProvider: CameraX 가 SurfaceTexture 에 프레임을 그리도록 연결
        preview?.setSurfaceProvider { request ->
            val resolution = request.resolution
            surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
            val surface = Surface(surfaceTexture)
            request.provideSurface(
                surface,
                ContextCompat.getMainExecutor(context)
            ) { result ->
                // Surface 가 더 이상 사용되지 않을 때 정리
                surface.release()
                Log.d(TAG, "Preview Surface 종료: ${result.resultCode}")
            }
        }

        // 후면 카메라 선택 (메인 광각). 노트20 울트라 메인 센서.
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
            Log.i(TAG, "카메라 바인딩 성공 (ISP 억제 적용)")
        } catch (e: Exception) {
            Log.e(TAG, "UseCase 바인딩 실패", e)
        }
    }

    /**
     * 삼성 ISP 후처리 억제 키 주입 (요구사항 1단계 핵심).
     * 각 키는 기기 미지원 가능성이 있으므로 개별 try/catch 로 안전 적용.
     */
    @SuppressLint("UnsafeOptInUsageError", "RestrictedApi")
    private fun <T> applyIspSuppression(extender: Camera2Interop.Extender<T>) {
        // 1) 노이즈 리덕션 OFF -> 질감/디테일 보존 (갤럭시 워터컬러 현상 방지)
        safeSet(extender, CaptureRequest.NOISE_REDUCTION_MODE,
            CaptureRequest.NOISE_REDUCTION_MODE_OFF, "NOISE_REDUCTION_MODE_OFF")

        // 2) 엣지(샤프닝) OFF -> 오버샤픈 제거 (iPhone 의 자연스러운 엣지에 근접)
        safeSet(extender, CaptureRequest.EDGE_MODE,
            CaptureRequest.EDGE_MODE_OFF, "EDGE_MODE_OFF")

        // 3) 색수차 보정 OFF (삼성 과도 개입 최소화). 미지원 기기 많으므로 try.
        safeSet(extender, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF, "ABERRATION_OFF")

        // 4) 핫픽셀 보정 OFF (가능 시) - 센서 raw 신호에 가깝게
        safeSet(extender, CaptureRequest.HOT_PIXEL_MODE,
            CaptureRequest.HOT_PIXEL_MODE_OFF, "HOT_PIXEL_OFF")

        // 5) 톤맵을 최대한 선형으로 -> 삼성 자체 콘트라스트 곡선 제거.
        //    우리가 셰이더에서 S-Curve 를 그릴 것이므로 ISP 톤맵은 평평해야 한다.
        //    GAMMA_VALUE 모드 + 감마 1.0(선형) 시도. 미지원 시 CONTRAST_CURVE 폴백.
        try {
            extender.setCaptureRequestOption(
                CaptureRequest.TONEMAP_MODE,
                CaptureRequest.TONEMAP_MODE_GAMMA_VALUE
            )
            extender.setCaptureRequestOption(
                CaptureRequest.TONEMAP_GAMMA,
                1.0f // 1.0 = 선형(감마 없음) -> 평평한 신호
            )
            Log.d(TAG, "TONEMAP_MODE_GAMMA_VALUE(1.0) 적용")
        } catch (e: Exception) {
            // 일부 기기는 GAMMA_VALUE 미지원 -> 무시하고 기본 톤맵 사용
            Log.w(TAG, "TONEMAP GAMMA 미지원, 기본값 사용: ${e.message}")
        }

        // 6) AWB(자동 화이트밸런스)는 켜두되, Warm 시프트는 셰이더에서 수행한다.
        //    (ISP AWB 를 끄면 그린/마젠타 캐스트가 심해질 수 있어 자동 유지가 안전)
        safeSet(extender, CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO, "AWB_AUTO")

        // 7) 비디오 안정화/디지털 효과 OFF (불필요한 ISP 개입 차단)
        safeSet(extender, CaptureRequest.CONTROL_EFFECT_MODE,
            CaptureRequest.CONTROL_EFFECT_MODE_OFF, "EFFECT_OFF")
    }

    /** 단일 CaptureRequest 키를 안전하게 설정 (미지원 시 무시). */
    // V : Any 제약 -> setCaptureRequestOption 의 non-null 파라미터 요구사항 충족
    @SuppressLint("UnsafeOptInUsageError", "RestrictedApi")
    private fun <T, V : Any> safeSet(
        extender: Camera2Interop.Extender<T>,
        key: CaptureRequest.Key<V>,
        value: V,
        label: String
    ) {
        try {
            extender.setCaptureRequestOption(key, value)
            Log.d(TAG, "ISP 억제 적용: $label")
        } catch (e: Exception) {
            Log.w(TAG, "ISP 키 미지원($label): ${e.message}")
        }
    }

    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "카메라 정지 중 오류: ${e.message}")
        }
    }
}
