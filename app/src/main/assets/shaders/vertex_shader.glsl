#version 300 es
// =============================================================================
// Vertex Shader : 풀스크린 쿼드에 카메라 SurfaceTexture 좌표를 매핑한다.
// SurfaceTexture 의 변환 행렬(uTexMatrix)을 적용하여 센서 회전/크롭을 보정한다.
// =============================================================================

layout(location = 0) in vec4 aPosition;   // 풀스크린 쿼드 정점 (NDC)
layout(location = 1) in vec4 aTexCoord;   // 텍스처 좌표 (0~1)

uniform mat4 uTexMatrix;                   // SurfaceTexture.getTransformMatrix() 결과

out vec2 vTexCoord;                        // 프래그먼트로 넘길 보정된 텍스처 좌표

void main() {
    gl_Position = aPosition;
    vTexCoord = (uTexMatrix * aTexCoord).xy;
}
