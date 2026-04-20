package io.github.some_example_name.android

import android.opengl.GLES32
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.INITIAL_PARTICLE_CAPACITY
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.PARTICLE_STRUCT_SIZE
import io.github.some_example_name.old.systems.render.ShaderManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import kotlin.math.max

var usePostProcess = false

class ShaderManagerAndroidApi : ShaderManager {

    // SSBO (пока один, как в LibGDX-версии)
    private val ssbos = IntArray(1)
    private var currentReadIndex = 0
    private val ssboCapacities = IntArray(1)

    // Шейдеры (чистый GLES32)
    private var particleProgram = 0
    private var sobelProgram = 0
    private var blurProgram = 0

    // Uniform locations
    private var particleProjLoc = 0
    private var particleTextureScaleLoc = 0
    private var particleColorScaleLoc = 0
    private var particleTextureArrayLoc = 0

    private var sobelTextureLoc = 0
    private var sobelResolutionLoc = 0
    private var sobelZoomLoc = 0

    private var blurTextureLoc = 0
    private var blurAmountLoc = 0
    private var blurResolutionLoc = 0

    // Quad (VAO + VBO) — один на все full-screen и instanced passes
    private var quadVao = 0
    private var quadVbo = 0

    // Texture Array (те же текстуры, что и в LibGDX)
    private var textureArray = 0
    private var numLayers = 0

    // FBOs (ручные, без LibGDX FrameBuffer)
    private var sceneFbo = 0
    private var sceneColorTex = 0
    private var sceneDepthRbo = 0
    private var sceneFboWidth = 0
    private var sceneFboHeight = 0

    private var blurFbo = 0
    private var blurColorTex = 0
    private var blurFboWidth = 0
    private var blurFboHeight = 0

    private val invProjMatrix = Matrix4() // оставляем для совместимости (хотя сейчас не используется)

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES32.glCreateShader(type)
        GLES32.glShaderSource(shader, source)
        GLES32.glCompileShader(shader)

        val status = IntArray(1)
        GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES32.glGetShaderInfoLog(shader)
            GLES32.glDeleteShader(shader)
            throw RuntimeException("Shader compile error: $log")
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES32.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES32.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES32.glCreateProgram()
        GLES32.glAttachShader(program, vs)
        GLES32.glAttachShader(program, fs)
        GLES32.glLinkProgram(program)

        val status = IntArray(1)
        GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES32.glGetProgramInfoLog(program)
            GLES32.glDeleteProgram(program)
            throw RuntimeException("Program link error: $log")
        }

        GLES32.glDeleteShader(vs)
        GLES32.glDeleteShader(fs)
        return program
    }

    private fun createTextureArray() {
        val texturePaths = arrayOf(
            "leaf.png",
            "fat.png",
            "bone.png",
            "fat.png",
            "neuron.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "not_cell.png"
        )

        numLayers = texturePaths.size
        if (numLayers == 0) throw IllegalStateException("Нет текстур для TextureArray!")

        val pixmaps = texturePaths.map { path ->
            val file = Gdx.files.internal(path)
            if (!file.exists()) throw IllegalArgumentException("Текстура не найдена: $path")
            Pixmap(file)
        }

        val width = pixmaps[0].width
        val height = pixmaps[0].height

        for (p in pixmaps) {
            if (p.width != width || p.height != height) {
                throw IllegalStateException("Все текстуры в TextureArray должны быть одного размера! (${width}×${height})")
            }
        }

        val buffer = IntBuffer.allocate(1)
        GLES32.glGenTextures(1, buffer)
        textureArray = buffer.get(0)

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D_ARRAY, textureArray)

        GLES32.glTexImage3D(
            GLES32.GL_TEXTURE_2D_ARRAY, 0, GLES32.GL_RGBA8,
            width, height, numLayers, 0,
            GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, null
        )

        for ((layer, pixmap) in pixmaps.withIndex()) {
            GLES32.glTexSubImage3D(
                GLES32.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, layer,
                pixmap.width, pixmap.height, 1,
                GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE,
                pixmap.pixels
            )
            pixmap.dispose()
        }

        GLES32.glGenerateMipmap(GLES32.GL_TEXTURE_2D_ARRAY)

        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D_ARRAY, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR_MIPMAP_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D_ARRAY, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D_ARRAY, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_REPEAT)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D_ARRAY, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_REPEAT)

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D_ARRAY, 0)

        println("✅ TextureArray создан (Android GLES): $numLayers слоёв, ${width}×${height} px")
    }

    private fun createQuadMesh() {
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        val buf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(vertices).position(0)

        val vao = IntArray(1)
        GLES32.glGenVertexArrays(1, vao, 0)
        quadVao = vao[0]

        GLES32.glBindVertexArray(quadVao)

        val vbo = IntArray(1)
        GLES32.glGenBuffers(1, vbo, 0)
        quadVbo = vbo[0]

        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, quadVbo)
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, vertices.size * 4, buf, GLES32.GL_STATIC_DRAW)

        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 0, 0)

        GLES32.glBindVertexArray(0)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)

        println("✅ Quad mesh (VAO) создан")
    }

    private fun createSSBO() {
        GLES32.glGenBuffers(1, ssbos, 0)
        ssboCapacities[0] = INITIAL_PARTICLE_CAPACITY * PARTICLE_STRUCT_SIZE

        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, ssbos[0])
        GLES32.glBufferData(
            GLES32.GL_SHADER_STORAGE_BUFFER,
            INITIAL_PARTICLE_CAPACITY * PARTICLE_STRUCT_SIZE,
            null,
            GLES32.GL_DYNAMIC_DRAW
        )
        GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 0, ssbos[0])
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, 0)

        println("✅ SSBO создан (Android GLES)")
    }

    private fun resizeSsbo(dataSize: Int, targetIndex: Int, ssboId: Int) {
        if (dataSize > ssboCapacities[targetIndex]) {
            var newCapacity = ssboCapacities[targetIndex].toDouble()
            do {
                newCapacity *= 1.5
            } while (newCapacity < dataSize)

            val finalCapacity = newCapacity.toInt().coerceAtLeast(dataSize)

            GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, ssboId)
            GLES32.glBufferData(GLES32.GL_SHADER_STORAGE_BUFFER, finalCapacity, null, GLES32.GL_DYNAMIC_DRAW)
            GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, 0)

            ssboCapacities[targetIndex] = finalCapacity
        }
    }

    private fun createSceneFbo(width: Int, height: Int) {
        // Color texture
        val tex = IntArray(1)
        GLES32.glGenTextures(1, tex, 0)
        sceneColorTex = tex[0]

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, sceneColorTex)
        GLES32.glTexImage2D(
            GLES32.GL_TEXTURE_2D, 0, GLES32.GL_RGBA8,
            width, height, 0,
            GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, null
        )
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)

        // Depth renderbuffer
        val rbo = IntArray(1)
        GLES32.glGenRenderbuffers(1, rbo, 0)
        sceneDepthRbo = rbo[0]

        GLES32.glBindRenderbuffer(GLES32.GL_RENDERBUFFER, sceneDepthRbo)
        GLES32.glRenderbufferStorage(GLES32.GL_RENDERBUFFER, GLES32.GL_DEPTH_COMPONENT24, width, height)
        GLES32.glBindRenderbuffer(GLES32.GL_RENDERBUFFER, 0)

        // FBO
        val f = IntArray(1)
        GLES32.glGenFramebuffers(1, f, 0)
        sceneFbo = f[0]

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, sceneFbo)
        GLES32.glFramebufferTexture2D(
            GLES32.GL_FRAMEBUFFER, GLES32.GL_COLOR_ATTACHMENT0,
            GLES32.GL_TEXTURE_2D, sceneColorTex, 0
        )
        GLES32.glFramebufferRenderbuffer(
            GLES32.GL_FRAMEBUFFER, GLES32.GL_DEPTH_ATTACHMENT,
            GLES32.GL_RENDERBUFFER, sceneDepthRbo
        )

        val status = GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER)
        if (status != GLES32.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Scene FBO incomplete: $status")
        }
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
    }

    private fun createBlurFbo(width: Int, height: Int) {
        // Color texture (linear filter — обязательно для blur)
        val tex = IntArray(1)
        GLES32.glGenTextures(1, tex, 0)
        blurColorTex = tex[0]

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, blurColorTex)
        GLES32.glTexImage2D(
            GLES32.GL_TEXTURE_2D, 0, GLES32.GL_RGBA8,
            width, height, 0,
            GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, null
        )
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)

        // FBO (depth не нужен)
        val f = IntArray(1)
        GLES32.glGenFramebuffers(1, f, 0)
        blurFbo = f[0]

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, blurFbo)
        GLES32.glFramebufferTexture2D(
            GLES32.GL_FRAMEBUFFER, GLES32.GL_COLOR_ATTACHMENT0,
            GLES32.GL_TEXTURE_2D, blurColorTex, 0
        )

        val status = GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER)
        if (status != GLES32.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Blur FBO incomplete: $status")
        }
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
    }

    private fun deleteFbos() {
        if (sceneFbo != 0) {
            GLES32.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
            GLES32.glDeleteTextures(1, intArrayOf(sceneColorTex), 0)
            GLES32.glDeleteRenderbuffers(1, intArrayOf(sceneDepthRbo), 0)
            sceneFbo = 0
            sceneColorTex = 0
            sceneDepthRbo = 0
        }
        if (blurFbo != 0) {
            GLES32.glDeleteFramebuffers(1, intArrayOf(blurFbo), 0)
            GLES32.glDeleteTextures(1, intArrayOf(blurColorTex), 0)
            blurFbo = 0
            blurColorTex = 0
        }
    }

    override fun create() {
        // Шейдеры (те же файлы, что и в LibGDX)
        val particleVert = Gdx.files.internal("shaders/debug/circle.vert").readString()
        val particleFrag = Gdx.files.internal("shaders/debug/circle.frag").readString()
        particleProgram = createProgram(particleVert, particleFrag)

        val sobelVert = Gdx.files.internal("shaders/post_process/post_process.vert").readString()
        val sobelFrag = Gdx.files.internal("shaders/post_process/post_process.frag").readString()
        sobelProgram = createProgram(sobelVert, sobelFrag)

        val blurVert = Gdx.files.internal("shaders/blur/blur.vert").readString()
        val blurFrag = Gdx.files.internal("shaders/blur/blur.frag").readString()
        blurProgram = createProgram(blurVert, blurFrag)

        // Uniform locations
        particleProjLoc = GLES32.glGetUniformLocation(particleProgram, "u_projTrans")
        particleTextureScaleLoc = GLES32.glGetUniformLocation(particleProgram, "u_textureScale")
        particleColorScaleLoc = GLES32.glGetUniformLocation(particleProgram, "u_colorScale")
        particleTextureArrayLoc = GLES32.glGetUniformLocation(particleProgram, "u_textureArray")

        sobelTextureLoc = GLES32.glGetUniformLocation(sobelProgram, "u_texture")
        sobelResolutionLoc = GLES32.glGetUniformLocation(sobelProgram, "u_resolution")
        sobelZoomLoc = GLES32.glGetUniformLocation(sobelProgram, "u_zoom")

        blurTextureLoc = GLES32.glGetUniformLocation(blurProgram, "u_texture")
        blurAmountLoc = GLES32.glGetUniformLocation(blurProgram, "u_blurAmount")
        blurResolutionLoc = GLES32.glGetUniformLocation(blurProgram, "u_resolution")

        createQuadMesh()
        createTextureArray()

        // Начальные FBO (размер экрана)
        val w = Gdx.graphics.width.coerceAtLeast(1)
        val h = Gdx.graphics.height.coerceAtLeast(1)
        val sw = (w / 2).coerceAtLeast(1)
        val sh = (h / 2).coerceAtLeast(1)

        createSceneFbo(sw, sh)
        sceneFboWidth = sw
        sceneFboHeight = sh

        createBlurFbo(w, h)
        blurFboWidth = w
        blurFboHeight = h

        createSSBO()
    }

    override fun resize(width: Int, height: Int) {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)

        deleteFbos()

        val sceneW = (safeW / 2).coerceAtLeast(1)
        val sceneH = (safeH / 2).coerceAtLeast(1)
        createSceneFbo(sceneW, sceneH)
        sceneFboWidth = sceneW
        sceneFboHeight = sceneH

        createBlurFbo(safeW, safeH)
        blurFboWidth = safeW
        blurFboHeight = safeH

        println("✅ FBOs resized (Android GLES) → scene: ${safeW}×${safeH} (половина), blur: ${safeW}×${safeH}")
    }

    override fun render(
        currentRead: ByteBuffer,
        cameraProjection: Matrix4,
        isNewFrame: Boolean,
        isClear: Boolean,
        worldX: Float,
        worldY: Float,
        blurAmount: Float,
        zoom: Float
    ) {
        val dataSize = currentRead.remaining()
        val numInstances = dataSize / PARTICLE_STRUCT_SIZE

        if (isNewFrame) {
            val writeIndex = 0
            if (dataSize > 0) {
                resizeSsbo(dataSize, writeIndex, ssbos[writeIndex])

                GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, ssbos[writeIndex])
                GLES32.glBufferSubData(
                    GLES32.GL_SHADER_STORAGE_BUFFER,
                    0,
                    dataSize,
                    currentRead
                )
                GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, 0)
            }
            currentReadIndex = writeIndex
        }

        // === Рендер частиц (в FBO или на экран) ===
        if (usePostProcess) {
            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, sceneFbo)
        } else {
            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        }

        GLES32.glDisable(GLES32.GL_BLEND)
        GLES32.glEnable(GLES32.GL_DEPTH_TEST)
        GLES32.glDepthFunc(GLES32.GL_LESS)
        GLES32.glDepthMask(true)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT or GLES32.GL_DEPTH_BUFFER_BIT)

        GLES32.glUseProgram(particleProgram)

        GLES32.glUniformMatrix4fv(particleProjLoc, 1, false, cameraProjection.`val`, 0)
        GLES32.glUniform1f(particleTextureScaleLoc, 1.0f)
        GLES32.glUniform1f(particleColorScaleLoc, if (usePostProcess) 0f else 0.5f)
        GLES32.glUniform1i(particleTextureArrayLoc, 0)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D_ARRAY, textureArray)

        GLES32.glBindVertexArray(quadVao)
        GLES32.glDrawArraysInstanced(GLES32.GL_TRIANGLE_STRIP, 0, 4, numInstances)
        GLES32.glBindVertexArray(0)

        GLES32.glUseProgram(0)

        if (!usePostProcess) return

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)

        // === POST-PROCESS: Sobel → blurFbo ===
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, blurFbo)
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDisable(GLES32.GL_BLEND)

        GLES32.glUseProgram(sobelProgram)
        GLES32.glUniform1i(sobelTextureLoc, 0)
        GLES32.glUniform2f(sobelResolutionLoc, sceneFboWidth.toFloat(), sceneFboHeight.toFloat())
        GLES32.glUniform1f(sobelZoomLoc, 0.12f)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, sceneColorTex)

        GLES32.glBindVertexArray(quadVao)
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
        GLES32.glBindVertexArray(0)

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)

        // === POST-PROCESS: Blur → экран ===
        GLES32.glUseProgram(blurProgram)
        GLES32.glUniform1i(blurTextureLoc, 0)
        GLES32.glUniform1f(blurAmountLoc, blurAmount * 0.5f)
        GLES32.glUniform2f(blurResolutionLoc, blurFboWidth.toFloat(), blurFboHeight.toFloat())

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, blurColorTex)

        GLES32.glBindVertexArray(quadVao)
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
        GLES32.glBindVertexArray(0)

        GLES32.glUseProgram(0)
    }

    override fun dispose() {
        GLES32.glDeleteProgram(particleProgram)
        GLES32.glDeleteProgram(sobelProgram)
        GLES32.glDeleteProgram(blurProgram)

        GLES32.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        GLES32.glDeleteBuffers(1, intArrayOf(quadVbo), 0)

        if (textureArray != 0) {
            GLES32.glDeleteTextures(1, intArrayOf(textureArray), 0)
            textureArray = 0
        }

        GLES32.glDeleteBuffers(1, ssbos, 0)

        deleteFbos()

        println("✅ ShaderManagerAndroidApi disposed")
    }
}
