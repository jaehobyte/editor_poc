package com.example.photorecipe.gl

import android.opengl.GLES30
import android.util.Log

private const val TAG = "GLProgram"

fun compileShader(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)
    val status = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES30.glGetShaderInfoLog(shader)
        GLES30.glDeleteShader(shader)
        error("Shader compile failed: $log\nSource:\n$source")
    }
    return shader
}

fun linkProgram(vertexShader: Int, fragmentShader: Int): Int {
    val program = GLES30.glCreateProgram()
    GLES30.glAttachShader(program, vertexShader)
    GLES30.glAttachShader(program, fragmentShader)
    GLES30.glLinkProgram(program)
    val status = IntArray(1)
    GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES30.glGetProgramInfoLog(program)
        GLES30.glDeleteProgram(program)
        error("Program link failed: $log")
    }
    return program
}

fun checkGLError(tag: String) {
    val e = GLES30.glGetError()
    if (e != GLES30.GL_NO_ERROR) {
        Log.e(TAG, "$tag: GL error 0x${Integer.toHexString(e)}")
    }
}
