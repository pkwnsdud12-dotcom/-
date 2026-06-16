#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// =============================================================================
//  color_grade_shader.glsl
//  iPhone XS (A12 Bionic / Smart HDR 1st Gen) 컬러 사이언스 수학적 모사 셰이더
//
//  파이프라인 순서 (매우 중요 - 순서가 결과를 좌우함):
//    1) sRGB -> Linear  (감마 디코딩, 모든 톤/색 연산은 선형광 공간에서 수행)
//    2) White Balance Shift (+300K~+400K Warm, Bradford 유사 RGB gain 행렬)
//    3) Tone Mapping  (Smart HDR 1세대 모사: 하이라이트 soft roll-off + 섀도우 압착 S-Curve)
//    4) Skin Tone 보호 (HSL 황/주황 영역 채도 미세 상승 + 인물톤 과장 억제)
//    5) Linear -> sRGB  (감마 인코딩)
//    6) 3D LUT 적용 (선택적, sRGB 공간에서 텍스처 트라이리니어 보간)
//
//  주의: GL_TEXTURE_EXTERNAL_OES (samplerExternalOES) 는 카메라가 이미 sRGB(감마 적용)
//        디스플레이용 데이터를 내보내므로, 정확한 톤매핑을 위해 선형화 후 작업한다.
// =============================================================================

precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

// ----- Uniforms -----
uniform samplerExternalOES uCameraTexture; // 카메라 외부 텍스처
uniform sampler3D uLutTexture;             // 3D LUT (.cube 로드 시)
uniform bool  uUseLut;                     // LUT 사용 여부
uniform float uLutSize;                    // LUT 한 축의 격자 수 (예: 33)

// 미세 조정용(런타임 변경 가능). 기본값은 iPhone XS 룩에 최적화.
uniform float uWarmStrength;   // 화이트밸런스 Warm 강도 (기본 1.0)
uniform float uContrast;       // S-Curve 대비 강도 (기본 1.0)
uniform float uSkinProtect;    // 피부톤 보호 강도 (기본 1.0)

// =============================================================================
// [공용] sRGB <-> Linear 변환 (IEC 61966-2-1 정확식)
// =============================================================================
vec3 srgbToLinear(vec3 c) {
    bvec3 cutoff = lessThanEqual(c, vec3(0.04045));
    vec3 low  = c / 12.92;
    vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(high, low, vec3(cutoff));
}

vec3 linearToSrgb(vec3 c) {
    bvec3 cutoff = lessThanEqual(c, vec3(0.0031308));
    vec3 low  = c * 12.92;
    vec3 high = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, vec3(cutoff));
}

// =============================================================================
// [공용] RGB <-> HSL (피부톤 영역 선택을 위함)
// =============================================================================
vec3 rgbToHsl(vec3 c) {
    float maxc = max(max(c.r, c.g), c.b);
    float minc = min(min(c.r, c.g), c.b);
    float l = (maxc + minc) * 0.5;
    float h = 0.0;
    float s = 0.0;
    float d = maxc - minc;
    if (d > 1e-5) {
        s = (l > 0.5) ? d / (2.0 - maxc - minc) : d / (maxc + minc);
        if (maxc == c.r) {
            h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
        } else if (maxc == c.g) {
            h = (c.b - c.r) / d + 2.0;
        } else {
            h = (c.r - c.g) / d + 4.0;
        }
        h /= 6.0;
    }
    return vec3(h, s, l);
}

float hue2rgb(float p, float q, float t) {
    if (t < 0.0) t += 1.0;
    if (t > 1.0) t -= 1.0;
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 1.0 / 2.0) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

vec3 hslToRgb(vec3 hsl) {
    float h = hsl.x;
    float s = hsl.y;
    float l = hsl.z;
    if (s <= 1e-5) return vec3(l);
    float q = (l < 0.5) ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return vec3(
        hue2rgb(p, q, h + 1.0 / 3.0),
        hue2rgb(p, q, h),
        hue2rgb(p, q, h - 1.0 / 3.0)
    );
}

// =============================================================================
// [1단계 색공간 보정] White Balance Warm Shift (+300K~+400K)
//
//  원리: 색온도를 따뜻하게(낮은 K 방향 -> 더 붉고 노랗게) 옮기려면
//        R 채널 gain↑, B 채널 gain↓ 가 필요하다.
//        실제 +300~400K Warm 시프트를 Bradford 채널 게인으로 근사한다.
//        (정확한 CCT->RGB 는 카메라 CCM 이 필요하나, 디스플레이용 sRGB 선형 데이터
//         기준의 실용적 근사치로 아래 게인 행렬을 사용한다.)
// =============================================================================
vec3 applyWarmWhiteBalance(vec3 lin, float strength) {
    // iPhone XS 는 갤럭시 대비 약간 더 따뜻하고 마젠타가 덜한(그린이 약간 살아있는) 톤.
    // R 을 살짝 올리고 B 를 내려 +350K 가량의 Warm 시프트를 모사.
    // strength=1.0 일 때 약 +350K 에 해당하도록 게인 보정.
    vec3 gain = vec3(
        1.0 + 0.060 * strength,   // R  : +6% (Warm)
        1.0 + 0.010 * strength,   // G  : +1% (살짝, 그린 보존 / 마젠타 억제)
        1.0 - 0.075 * strength    // B  : -7.5% (Warm)
    );
    vec3 outc = lin * gain;
    // 화이트밸런스로 인한 전체 노출 변화 보정(휘도 보존) - Rec.709 가중
    float lumIn  = dot(lin,  vec3(0.2126, 0.7152, 0.0722));
    float lumOut = dot(outc, vec3(0.2126, 0.7152, 0.0722));
    if (lumOut > 1e-5) {
        outc *= (lumIn / lumOut) * 0.5 + 0.5; // 절반만 보존(완전 보존 시 Warm 효과 상쇄됨)
    }
    return outc;
}

// =============================================================================
// [2단계 톤매핑] Smart HDR 1세대 모사 S-Curve
//
//  특징:
//   - 하이라이트: 부드러운 Roll-off (필믹 곡선) 로 클리핑 방지, 디테일 유지
//   - 섀도우: 갤럭시보다 더 어둡고 짙게 압착하여 깊은 콘트라스트 형성
//   - 미드톤: 약간 들어올려 입체감 유지 (iPhone 특유의 '쫀득한' 중간톤)
//
//  구현: 휘도(luma)에 대해 S-Curve 를 적용하고, 채널별 비율 보존(색 왜곡 최소화).
// =============================================================================

// 필믹 하이라이트 Roll-off (Reinhard 확장형 + shoulder)
float filmicRolloff(float x) {
    // shoulder 시작점 이상에서 부드럽게 압축, 1.0 근처를 절대 넘기지 않음
    float a = 1.10;   // 하이라이트 헤드룸
    return (x * (1.0 + x / (a * a))) / (1.0 + x);
}

// 섀도우 압착 (gamma 기반, 1보다 큰 지수 -> 암부 더 어둡게)
float crushShadows(float x, float amount) {
    // amount>0 일수록 암부가 짙어짐. 미드~하이는 거의 영향 없도록 가중.
    float shadowMask = 1.0 - smoothstep(0.0, 0.45, x); // 암부에서 1, 중간 이후 0
    float crushed = pow(x, 1.0 + amount * shadowMask);
    return crushed;
}

vec3 applyToneMapping(vec3 lin, float contrast) {
    // 1) 휘도 추출
    float luma = dot(lin, vec3(0.2126, 0.7152, 0.0722));
    luma = max(luma, 1e-5);

    // 2) S-Curve 구성: 섀도우 압착 -> 하이라이트 roll-off -> 미드톤 리프트
    float shadowAmount = 0.55 * contrast;   // 섀도우 짙게(갤럭시 대비 강함)
    float t = crushShadows(luma, shadowAmount);
    t = filmicRolloff(t * 1.05);            // 약간 push 후 하이라이트 부드럽게

    // 미드톤 살짝 리프트 (pivot 0.18 중심 대비)
    float pivot = 0.18;
    t = mix(t, pivot + (t - pivot) * (1.0 + 0.18 * contrast), 0.5);
    t = clamp(t, 0.0, 1.0);

    // 3) 색 비율 보존하며 새 휘도 적용
    float ratio = t / luma;
    vec3 outc = lin * ratio;

    // 4) 전역 채도 미세 상승 (iPhone XS 의 풍부한 색감) - 휘도 기준 보간
    float newLuma = dot(outc, vec3(0.2126, 0.7152, 0.0722));
    outc = mix(vec3(newLuma), outc, 1.0 + 0.08 * contrast);

    return clamp(outc, 0.0, 4.0);
}

// =============================================================================
// [3단계] Skin Tone 보호 / 강화
//
//  목표:
//   - 황색/주황색(피부톤 인접) 채도를 미세하게 상승 -> 혈색 좋은 인물
//   - 단, 인물 피부톤이 과장(주황 떡칠)되지 않도록 채도 상한 클램프 + 부드러운 마스크
//   - 피부톤 색상(hue) 자체는 iPhone 특유의 약간 따뜻한 쪽으로 미세 회전
// =============================================================================
vec3 protectSkinTone(vec3 srgb, float strength) {
    vec3 hsl = rgbToHsl(srgb);
    float h = hsl.x; // 0~1
    float s = hsl.y;
    float l = hsl.z;

    // 피부톤 대략 hue 범위: 0.02 ~ 0.11 (약 7°~40°, 주황~노랑)
    float center = 0.065;
    float width  = 0.045;
    float skinMask = exp(-pow((h - center) / width, 2.0)); // 가우시안 마스크 (0~1)

    // 밝기 마스크: 너무 어둡거나 너무 밝은 픽셀은 피부가 아닐 확률 높음
    float lumaMask = smoothstep(0.15, 0.35, l) * (1.0 - smoothstep(0.85, 0.97, l));
    float mask = skinMask * lumaMask * strength;

    // 1) 채도 미세 상승 (단, 과채도 방지: 상한 0.62)
    float targetS = min(s * (1.0 + 0.12 * mask), mix(s, 0.62, mask));
    // 2) hue 를 아주 약간 따뜻한 쪽(주황)으로 회전
    float targetH = mix(h, h - 0.004, mask);
    // 3) 밝기는 살짝만 들어올려 인물 입체감 (과도 방지)
    float targetL = mix(l, l + 0.015 * mask, 1.0);

    vec3 adjusted = hslToRgb(vec3(targetH, targetS, clamp(targetL, 0.0, 1.0)));
    return mix(srgb, adjusted, 1.0); // 마스크는 이미 채널 보간에 반영됨
}

// =============================================================================
// [4단계] 3D LUT 적용 (선택적)
//  - .cube 파일을 RGB 3D 텍스처로 로드한 뒤, sRGB 공간 좌표로 트라이리니어 샘플링.
//  - LUT 격자 외곽 보정: 0.5/size ~ 1-0.5/size 범위로 좌표 스케일(텍스처 경계 정확도).
// =============================================================================
vec3 applyLut(vec3 srgb, float lutSize) {
    float scale = (lutSize - 1.0) / lutSize;
    float offset = 1.0 / (2.0 * lutSize);
    vec3 coord = clamp(srgb, 0.0, 1.0) * scale + offset;
    return texture(uLutTexture, coord).rgb;
}

// =============================================================================
// main
// =============================================================================
void main() {
    // 0) 카메라 원본 픽셀 (sRGB/감마 인코딩 상태)
    vec3 cam = texture(uCameraTexture, vTexCoord).rgb;

    // 1) sRGB -> Linear (선형광 공간에서 색/톤 연산)
    vec3 lin = srgbToLinear(cam);

    // 2) White Balance Warm Shift (+350K)
    lin = applyWarmWhiteBalance(lin, uWarmStrength);

    // 3) Tone Mapping (Smart HDR 1세대 S-Curve)
    lin = applyToneMapping(lin, uContrast);

    // 4) Linear -> sRGB
    vec3 srgb = linearToSrgb(clamp(lin, 0.0, 1.0));

    // 5) Skin Tone 보호/강화 (sRGB/HSL 공간)
    srgb = protectSkinTone(srgb, uSkinProtect);

    // 6) 3D LUT (있으면 최종 룩 적용)
    if (uUseLut) {
        srgb = applyLut(srgb, uLutSize);
    }

    fragColor = vec4(clamp(srgb, 0.0, 1.0), 1.0);
}
