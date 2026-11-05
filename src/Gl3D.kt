package com.guarddog.dripfactory

import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** World-space constants shared by the game logic and the 3D renderer (units are arbitrary). */
object World {
    const val PLATE_Y = 3.6f
    const val PLATE_R = 1.2f
    const val PLATE_H = 0.22f
    const val JAR_R = 0.75f
    const val JAR_H = 1.7f
    const val BELT_TOP = 0.9f
    const val ICE_W = 1.35f
    const val ICE_H = 0.95f
    const val LEAVE_X = 3.6f
    const val GRAVITY = 16f
    const val MAX_DROPS = 256
}

/** Snapshot of the game state that the GL thread reads. */
object Scene {
    val lock = Any()
    var matLv = 0
    var iceFrac = 1f
    var jarX = 0f
    var jarFill = 0f
    var beltPos = 0f
    var boost = false
    var tsec = 0f
    var dropCount = 0
    val dropX = FloatArray(World.MAX_DROPS)
    val dropY = FloatArray(World.MAX_DROPS)
}

/** Camera shared by the renderer and the 2D overlay (so texts can follow 3D objects). */
object Cam {
    private const val FOVY = 45f
    private const val EYE_Y = 7f
    private const val EYE_Z = 8.5f
    private const val TARGET_Y = 2f

    fun build(aspect: Float, vp: FloatArray, eye: FloatArray) {
        val proj = FloatArray(16)
        val view = FloatArray(16)
        Matrix.perspectiveM(proj, 0, FOVY, aspect, 3f, 80f)
        Matrix.setLookAtM(view, 0, 0f, EYE_Y, EYE_Z, 0f, TARGET_Y, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        eye[0] = 0f
        eye[1] = EYE_Y
        eye[2] = EYE_Z
    }

    fun project(vp: FloatArray, x: Float, y: Float, z: Float, w: Float, h: Float, out: FloatArray) {
        val v = floatArrayOf(x, y, z, 1f)
        val r = FloatArray(4)
        Matrix.multiplyMV(r, 0, vp, 0, v, 0)
        out[0] = (r[0] / r[3] * 0.5f + 0.5f) * w
        out[1] = (1f - (r[1] / r[3] * 0.5f + 0.5f)) * h
    }
}

class Mesh(verts: FloatArray, indices: ShortArray) {
    val vb: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val ib: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    val count = indices.size

    init {
        vb.put(verts)
        vb.position(0)
        ib.put(indices)
        ib.position(0)
    }
}

/** Procedural meshes. Vertex layout: position(3) + normal(3). */
object Meshes {
    private class Builder {
        val v = ArrayList<Float>()
        val i = ArrayList<Short>()

        fun vert(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float): Int {
            v.add(px); v.add(py); v.add(pz)
            v.add(nx); v.add(ny); v.add(nz)
            return v.size / 6 - 1
        }

        fun tri(a: Int, b: Int, c: Int) {
            i.add(a.toShort()); i.add(b.toShort()); i.add(c.toShort())
        }

        fun build(): Mesh = Mesh(v.toFloatArray(), i.toShortArray())
    }

    /** Unit cube centered at the origin. */
    fun cube(): Mesh {
        val b = Builder()
        // each face: normal(3), u(3), v(3)
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 0f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f)
        )
        val su = floatArrayOf(-0.5f, 0.5f, 0.5f, -0.5f)
        val sv = floatArrayOf(-0.5f, -0.5f, 0.5f, 0.5f)
        for (q in faces) {
            val idx = IntArray(4)
            for (k in 0..3) {
                idx[k] = b.vert(
                    q[0] * 0.5f + q[3] * su[k] + q[6] * sv[k],
                    q[1] * 0.5f + q[4] * su[k] + q[7] * sv[k],
                    q[2] * 0.5f + q[5] * su[k] + q[8] * sv[k],
                    q[0], q[1], q[2]
                )
            }
            b.tri(idx[0], idx[1], idx[2])
            b.tri(idx[0], idx[2], idx[3])
        }
        return b.build()
    }

    /** Cylinder with radius 1, from y = 0 to y = 1. */
    fun cylinder(caps: Boolean): Mesh {
        val b = Builder()
        val n = 32
        for (k in 0..n) {
            val a = (2.0 * PI * k / n).toFloat()
            val c = cos(a)
            val s = sin(a)
            b.vert(c, 0f, s, c, 0f, s)
            b.vert(c, 1f, s, c, 0f, s)
        }
        for (k in 0 until n) {
            val b0 = k * 2
            val t0 = k * 2 + 1
            val b1 = k * 2 + 2
            val t1 = k * 2 + 3
            b.tri(b0, t0, b1)
            b.tri(b1, t0, t1)
        }
        if (caps) {
            for (top in 0..1) {
                val y = top.toFloat()
                val ny = if (top == 1) 1f else -1f
                val center = b.vert(0f, y, 0f, 0f, ny, 0f)
                val first = center + 1
                for (k in 0..n) {
                    val a = (2.0 * PI * k / n).toFloat()
                    b.vert(cos(a), y, sin(a), 0f, ny, 0f)
                }
                for (k in 0 until n) {
                    if (top == 1) b.tri(center, first + k + 1, first + k)
                    else b.tri(center, first + k, first + k + 1)
                }
            }
        }
        return b.build()
    }

    /** Sphere with radius 1. */
    fun sphere(): Mesh {
        val b = Builder()
        val lat = 12
        val lon = 16
        for (i in 0..lat) {
            val th = (PI * i / lat).toFloat()
            for (j in 0..lon) {
                val ph = (2.0 * PI * j / lon).toFloat()
                val x = sin(th) * cos(ph)
                val y = cos(th)
                val z = sin(th) * sin(ph)
                b.vert(x, y, z, x, y, z)
            }
        }
        for (i in 0 until lat) {
            for (j in 0 until lon) {
                val a = i * (lon + 1) + j
                val c = a + lon + 1
                b.tri(a, c, a + 1)
                b.tri(a + 1, c, c + 1)
            }
        }
        return b.build()
    }
}

class SceneRenderer : GLSurfaceView.Renderer {
    private var prog = 0
    private var aPos = 0
    private var aNorm = 0
    private var uMVP = 0
    private var uModel = 0
    private var uColor = 0
    private var uAlpha = 0
    private var uUnlit = 0
    private var uGlass = 0
    private var uSpec = 0
    private var uEmis = 0
    private var uCam = 0

    private lateinit var cube: Mesh
    private lateinit var cyl: Mesh
    private lateinit var cylOpen: Mesh
    private lateinit var sphere: Mesh

    private val vp = FloatArray(16)
    private val eye = FloatArray(3)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    // copy of Scene taken once per frame
    private var matLv = 0
    private var iceFrac = 1f
    private var jarX = 0f
    private var jarFill = 0f
    private var beltPos = 0f
    private var boost = false
    private var tsec = 0f
    private var dropN = 0
    private val dx = FloatArray(World.MAX_DROPS)
    private val dy = FloatArray(World.MAX_DROPS)

    private val liquidColors = intArrayOf(Color.rgb(46, 148, 255), Color.rgb(100, 58, 34), Color.rgb(245, 165, 20))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.62f, 0.82f, 0.98f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(
            GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE
        )
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNorm = GLES20.glGetAttribLocation(prog, "aNorm")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        uUnlit = GLES20.glGetUniformLocation(prog, "uUnlit")
        uGlass = GLES20.glGetUniformLocation(prog, "uGlass")
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec")
        uEmis = GLES20.glGetUniformLocation(prog, "uEmis")
        uCam = GLES20.glGetUniformLocation(prog, "uCam")
        cube = Meshes.cube()
        cyl = Meshes.cylinder(true)
        cylOpen = Meshes.cylinder(false)
        sphere = Meshes.sphere()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Cam.build(width.toFloat() / height.toFloat(), vp, eye)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(Scene.lock) {
            matLv = Scene.matLv
            iceFrac = Scene.iceFrac
            jarX = Scene.jarX
            jarFill = Scene.jarFill
            beltPos = Scene.beltPos
            boost = Scene.boost
            tsec = Scene.tsec
            dropN = min(Scene.dropCount, World.MAX_DROPS)
            System.arraycopy(Scene.dropX, 0, dx, 0, dropN)
            System.arraycopy(Scene.dropY, 0, dy, 0, dropN)
        }
        val liq = liquidColors[matLv.coerceIn(0, 2)]

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        GLES20.glUniform3f(uCam, eye[0], eye[1], eye[2])

        // ground
        draw(cube, 0f, -0.1f, 0f, 300f, 0.2f, 300f, Color.rgb(205, 225, 240))

        // conveyor block, moving stripes and rails
        draw(cube, 0f, 0.45f, 0f, 18f, 0.9f, 2.6f, Color.rgb(66, 70, 84), spec = 0.15f)
        for (k in -13..13) {
            draw(cube, k * 0.7f + beltPos, World.BELT_TOP + 0.012f, 0f, 0.3f, 0.024f, 2.3f, Color.rgb(96, 101, 120))
        }
        draw(cube, 0f, 1.0f, 1.28f, 18f, 0.25f, 0.12f, Color.rgb(48, 50, 62), spec = 0.3f)
        draw(cube, 0f, 1.0f, -1.28f, 18f, 0.25f, 0.12f, Color.rgb(48, 50, 62), spec = 0.3f)

        // machine body behind the plate
        draw(cube, 0f, 2.65f, -1.6f, 3.6f, 3.5f, 0.6f, Color.rgb(232, 236, 244), spec = 0.25f)
        draw(cube, 0f, 1.7f, -1.28f, 2.8f, 0.45f, 0.06f, Color.rgb(70, 140, 225), spec = 0.5f)
        draw(cube, 0f, World.PLATE_Y - 0.11f, -1.25f, 1.0f, 0.22f, 0.2f, Color.rgb(150, 155, 170), spec = 0.4f)

        // hot plate (cylinder) with glowing ring
        val pr = World.PLATE_R
        draw(cyl, 0f, World.PLATE_Y - World.PLATE_H, 0f, pr, World.PLATE_H, pr, Color.rgb(58, 60, 72), spec = 0.5f)
        val g = if (boost) 1f else 0.7f + 0.2f * sin(tsec * 3f)
        draw(
            cyl, 0f, World.PLATE_Y + 0.004f, 0f, pr * 0.92f, 0.01f, pr * 0.92f,
            Color.rgb((255f * g).toInt(), (115f * g).toInt(), (40f * g).toInt()), unlit = true
        )
        draw(cyl, 0f, World.PLATE_Y + 0.016f, 0f, pr * 0.8f, 0.01f, pr * 0.8f, Color.rgb(74, 76, 90), spec = 0.3f)

        // spout under the plate
        draw(cyl, 0f, World.PLATE_Y - World.PLATE_H - 0.3f, 0f, 0.12f, 0.3f, 0.12f, Color.rgb(175, 180, 195), spec = 0.9f)

        // contact shadow of the jar
        draw(
            cyl, jarX, World.BELT_TOP + 0.004f, 0.05f, World.JAR_R * 1.25f, 0.002f, World.JAR_R * 1.25f,
            Color.rgb(0, 0, 0), alpha = 0.25f, unlit = true
        )

        // melting block (ice / chocolate / honey)
        if (iceFrac > 0.01f) {
            val iw = World.ICE_W * (0.55f + 0.45f * iceFrac)
            val ih = World.ICE_H * iceFrac + 0.05f
            val c = when (matLv) {
                0 -> Color.rgb(150, 215, 255)
                1 -> Color.rgb(98, 58, 36)
                else -> Color.rgb(255, 178, 34)
            }
            val a = if (matLv == 1) 1f else 0.88f
            val sp = if (matLv == 1) 0.5f else 1f
            draw(cube, 0f, World.PLATE_Y + 0.03f + ih / 2f, 0f, iw, ih, iw * 0.9f, c, alpha = a, spec = sp, rotY = 18f)
        }

        // droplets
        for (i in 0 until dropN) {
            draw(sphere, dx[i], dy[i], 0f, 0.1f, 0.14f, 0.1f, liq, spec = 1f)
        }

        // liquid inside the jar
        if (jarFill > 0.005f) {
            val lh = (World.JAR_H - 0.16f) * jarFill
            val lr = World.JAR_R * 0.94f
            draw(cyl, jarX, World.BELT_TOP + 0.08f, 0f, lr, lh, lr, liq, spec = 0.7f, emis = 0.08f)
        }

        // glass jar: back faces first, then front faces (transparent)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_FRONT)
        draw(cylOpen, jarX, World.BELT_TOP, 0f, World.JAR_R, World.JAR_H, World.JAR_R, Color.WHITE, spec = 0.9f, glass = true)
        GLES20.glCullFace(GLES20.GL_BACK)
        draw(cylOpen, jarX, World.BELT_TOP, 0f, World.JAR_R, World.JAR_H, World.JAR_R, Color.WHITE, spec = 0.9f, glass = true)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        draw(cyl, jarX, World.BELT_TOP, 0f, World.JAR_R, 0.08f, World.JAR_R, Color.rgb(200, 230, 255), alpha = 0.5f, spec = 0.8f)
        GLES20.glDepthMask(true)
    }

    private fun draw(
        m: Mesh, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: Int,
        alpha: Float = 1f, spec: Float = 0f, emis: Float = 0f,
        unlit: Boolean = false, glass: Boolean = false, rotY: Float = 0f
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rotY != 0f) Matrix.rotateM(model, 0, rotY, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3f(uColor, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniform1f(uUnlit, if (unlit) 1f else 0f)
        GLES20.glUniform1f(uGlass, if (glass) 1f else 0f)
        GLES20.glUniform1f(uSpec, spec)
        GLES20.glUniform1f(uEmis, emis)
        m.vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, m.vb)
        m.vb.position(3)
        GLES20.glEnableVertexAttribArray(aNorm)
        GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, 24, m.vb)
        m.ib.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.count, GLES20.GL_UNSIGNED_SHORT, m.ib)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("DripFactory", "shader error: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val VERTEX = """
uniform mat4 uMVP;
uniform mat4 uModel;
attribute vec3 aPos;
attribute vec3 aNorm;
varying vec3 vN;
varying vec3 vW;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vW = w.xyz;
    vN = (uModel * vec4(aNorm, 0.0)).xyz;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
        private const val FRAGMENT = """
precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
uniform float uUnlit;
uniform float uGlass;
uniform float uSpec;
uniform float uEmis;
uniform vec3 uCam;
varying vec3 vN;
varying vec3 vW;
void main() {
    if (uUnlit > 0.5) {
        gl_FragColor = vec4(uColor, uAlpha);
        return;
    }
    vec3 N = normalize(vN);
    vec3 V = normalize(uCam - vW);
    vec3 L = normalize(vec3(-0.35, 0.9, 0.55));
    float diff = max(dot(N, L), 0.0);
    float hemi = 0.5 + 0.5 * N.y;
    vec3 col = uColor * (0.40 * hemi + 0.70 * diff + uEmis);
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 40.0) * uSpec;
    col += vec3(spec);
    float dist = length(uCam - vW);
    float fogT = clamp((dist - 16.0) / 40.0, 0.0, 1.0);
    col = mix(col, vec3(0.62, 0.82, 0.98), fogT);
    float a = uAlpha;
    if (uGlass > 0.5) {
        float f = pow(1.0 - abs(dot(N, V)), 2.0);
        a = 0.08 + 0.55 * f;
        col = mix(col, vec3(1.0), 0.5);
    }
    gl_FragColor = vec4(col, a);
}
"""
    }
}
