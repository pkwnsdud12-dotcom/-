package com.notexs.iphonexscam

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * OpenGL ES 3.0 공용 유틸리티.
 * 셰이더 컴파일/링크, assets 텍스트 로드, GL 에러 체크를 담당한다.
 */
object GLUtil {

    private const val TAG = "GLUtil"

    /** assets 폴더의 텍스트 파일(.glsl 등)을 문자열로 읽는다. */
    fun readAssetText(context: Context, path: String): String {
        context.assets.open(path).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                return reader.readText()
            }
        }
    }

    /** 단일 셰이더(VERTEX/FRAGMENT) 컴파일. 실패 시 IllegalStateException. */
    fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            throw IllegalStateException("glCreateShader 실패 (type=$type)")
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("셰이더 컴파일 실패:\n$log")
        }
        return shader
    }

    /** 프로그램 링크 (vertex + fragment). */
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES30.glCreateProgram()
        if (program == 0) {
            throw IllegalStateException("glCreateProgram 실패")
        }
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("프로그램 링크 실패:\n$log")
        }

        // 링크 완료 후 개별 셰이더는 삭제 가능
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    /** GL 에러 체크 (디버그용). */
    fun checkGlError(op: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError $error")
        }
    }
}
