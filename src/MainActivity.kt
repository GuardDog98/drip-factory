package com.guarddog.dripfactory

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var game: GameView
    private lateinit var glView: GLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.setRenderer(SceneRenderer())
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        game = GameView(this)

        val root = FrameLayout(this)
        val lp = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(glView, FrameLayout.LayoutParams(lp, lp))
        root.addView(game, FrameLayout.LayoutParams(lp, lp))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        game.save()
    }

    override fun onDestroy() {
        super.onDestroy()
        game.release()
    }
}

/**
 * Game logic + 2D overlay (HUD, burner slots, upgrade buttons).
 * The 3D scene is drawn by SceneRenderer underneath; this view stays transparent.
 */
class GameView(context: Context) : View(context) {
    private class Drop(var x: Float, var y: Float, var vy: Float)
    private class Pop(var text: String, var x: Float, var y: Float, var life: Float)

    private val d = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("drip_factory", Context.MODE_PRIVATE)
    private val sfx = Sfx(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val rect2 = RectF()
    private val btn = Array(5) { RectF() }
    private val slots = Array(6) { RectF() }
    private val soundRect = RectF()

    // materials: water, chocolate, honey
    private val matNames = arrayOf("Water", "Chocolate", "Honey")
    private val matValue = doubleArrayOf(1.0, 3.0, 9.0)
    private val matFlow = floatArrayOf(1f, 0.85f, 0.7f)
    private val matCost = doubleArrayOf(5000.0, 250000.0)
    private val burnerColors = intArrayOf(
        Color.rgb(255, 160, 60), Color.rgb(255, 120, 40), Color.rgb(240, 80, 40), Color.rgb(220, 50, 80),
        Color.rgb(190, 50, 140), Color.rgb(140, 60, 190), Color.rgb(80, 90, 220), Color.rgb(40, 180, 230)
    )

    // saved progress
    private var money = prefs.getString("money", null)?.toDoubleOrNull() ?: 0.0
    private var capLv = prefs.getInt("cap", 1)
    private var valLv = prefs.getInt("val", 1)
    private var matLv = prefs.getInt("mat", 0).coerceIn(0, 2)
    private var autoSell = prefs.getBoolean("auto", false)
    private var soundOn = prefs.getBoolean("sound", true)
    private var burnersBought = prefs.getInt("bought", 0)
    private val burners = loadBurners()

    // game state (world coordinates, see World in Gl3D.kt)
    private val drops = ArrayList<Drop>()
    private val pops = ArrayList<Pop>()
    private var spawnAcc = 0f
    private var boost = 0f
    private var iceLeft = 0f
    private var iceWait = 0f
    private var fill = 0f
    private var phase = 0 // 0 filling, 1 jar leaving, 2 new jar entering, 3 jar full (waiting for tap)
    private var jarOff = 0f
    private var lastNs = 0L
    private var saveTimer = 0f
    private var beltPos = 0f
    private var tsec = 0f
    private var lastPlop = 0f
    private var selected = -1

    // layout (screen pixels)
    private var w = 0f
    private var h = 0f
    private var panelTop = 0f
    private var slotsTop = 0f
    private val vp = FloatArray(16)
    private val eye = FloatArray(3)
    private val tmp = FloatArray(2)

    init {
        iceLeft = iceMax()
    }

    private fun loadBurners(): IntArray {
        val out = intArrayOf(1, 0, 0, 0, 0, 0)
        val saved = prefs.getString("burners", null) ?: return out
        val parts = saved.split(",")
        if (parts.size == 6) {
            for (i in 0..5) out[i] = (parts[i].toIntOrNull() ?: 0).coerceIn(0, 8)
        }
        return out
    }

    private fun power(): Float {
        var s = 0f
        for (b in burners) if (b > 0) s += (1 shl (b - 1)).toFloat()
        return s
    }

    private fun meltRate() = (1.5f + power() * 2.5f) * matFlow[matLv]
    private fun iceMax() = 60f + power() * 8f
    private fun capacity() = 20f + capLv * 10f
    private fun unitValue() = 1.3.pow(valLv - 1) * matValue[matLv]

    private fun costOf(k: Int): Double = when (k) {
        0 -> floor(15.0 * 2.0.pow(burnersBought))
        1 -> floor(12.0 * 1.8.pow(capLv - 1))
        2 -> floor(20.0 * 1.9.pow(valLv - 1))
        3 -> 250.0
        else -> if (matLv >= 2) 0.0 else matCost[matLv]
    }

    private fun fmt(v: Double): String = when {
        v >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
        v >= 1e4 -> String.format(Locale.US, "%.1fK", v / 1e3)
        else -> String.format(Locale.US, "%.0f", v)
    }

    fun save() {
        prefs.edit()
            .putString("money", money.toString())
            .putInt("cap", capLv)
            .putInt("val", valLv)
            .putInt("mat", matLv)
            .putBoolean("auto", autoSell)
            .putBoolean("sound", soundOn)
            .putInt("bought", burnersBought)
            .putString("burners", burners.joinToString(","))
            .apply()
    }

    fun release() {
        sfx.release()
    }

    private fun beep(index: Int, vol: Float = 1f) {
        if (soundOn) sfx.play(index, vol)
    }

    /** Adds floating text at a 3D world position (projected to the screen). */
    private fun popAt(text: String, wx: Float, wy: Float, wz: Float, life: Float) {
        Cam.project(vp, wx, wy, wz, w, h, tmp)
        pops.add(Pop(text, tmp[0], tmp[1], life))
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw.toFloat()
        h = nh.toFloat()
        panelTop = h - 170f * d
        Cam.build(w / h, vp, eye)

        val gap = 10f * d
        val bw = (w - gap * 4f) / 3f
        val r1 = panelTop + 12f * d
        for (i in 0..2) {
            val l = gap + i * (bw + gap)
            btn[i].set(l, r1, l + bw, r1 + 60f * d)
        }
        val bw2 = (w - gap * 3f) / 2f
        val r2 = r1 + 68f * d
        for (i in 0..1) {
            val l = gap + i * (bw2 + gap)
            btn[3 + i].set(l, r2, l + bw2, r2 + 60f * d)
        }

        val sg = 8f * d
        slotsTop = h * 0.68f
        var sz = (w - 7f * sg) / 6f
        sz = max(20f * d, min(sz, panelTop - 10f * d - slotsTop))
        val total = 6f * sz + 5f * sg
        val sx0 = (w - total) / 2f
        for (i in 0..5) {
            val l = sx0 + i * (sz + sg)
            slots[i].set(l, slotsTop, l + sz, slotsTop + sz)
        }
        soundRect.set(w - 84f * d, 36f * d, w - 12f * d, 68f * d)
    }

    private fun finishJar() {
        val earned = capacity() * unitValue()
        money += earned
        popAt("+\$" + fmt(earned), jarOff, World.BELT_TOP + World.JAR_H + 0.2f, 0f, 1.2f)
        beep(Sfx.SELL)
        phase = 1
    }

    private fun onJarFull() {
        if (autoSell) finishJar() else phase = 3
    }

    private fun update(dt: Float) {
        if (w == 0f) return
        tsec += dt
        if (boost > 0f) boost = max(0f, boost - dt)

        when (phase) {
            0 -> {
                if (iceLeft <= 0f) {
                    iceWait += dt
                    if (iceWait > 0.6f) {
                        iceLeft = iceMax()
                        iceWait = 0f
                    }
                } else {
                    val rate = meltRate() * (if (boost > 0f) 2.5f else 1f)
                    spawnAcc += rate * dt
                    while (spawnAcc >= 1f && iceLeft > 0f) {
                        spawnAcc -= 1f
                        iceLeft -= 1f
                        val x = (Random.nextFloat() - 0.5f) * 0.1f
                        drops.add(Drop(x, World.PLATE_Y - World.PLATE_H - 0.3f, 0f))
                    }
                }
            }
            1, 2 -> {
                beltPos = (beltPos + dt * 3f) % 0.7f
                jarOff += 7f * dt
                if (phase == 1 && jarOff >= World.LEAVE_X) {
                    jarOff = -World.LEAVE_X
                    fill = 0f
                    phase = 2
                } else if (phase == 2 && jarOff >= 0f) {
                    jarOff = 0f
                    phase = 0
                }
            }
        }

        val cap = capacity()
        val iter = drops.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.vy -= World.GRAVITY * dt
            p.y += p.vy * dt
            val surface = World.BELT_TOP + 0.08f + (World.JAR_H - 0.16f) * (fill / cap)
            if (phase == 0 && p.y <= surface && abs(p.x - jarOff) < World.JAR_R * 0.9f) {
                iter.remove()
                fill += 1f
                if (tsec - lastPlop > 0.09f) {
                    lastPlop = tsec
                    beep(Sfx.PLOP, 0.35f)
                }
                if (fill >= cap) onJarFull()
            } else if (p.y < World.BELT_TOP) {
                iter.remove()
            }
        }

        val pit = pops.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.life -= dt
            p.y -= 60f * d * dt
            if (p.life <= 0f) pit.remove()
        }

        saveTimer += dt
        if (saveTimer > 5f) {
            save()
            saveTimer = 0f
        }
    }

    /** Copies the state the 3D renderer needs. */
    private fun publish() {
        synchronized(Scene.lock) {
            Scene.matLv = matLv
            Scene.iceFrac = max(0f, iceLeft / iceMax())
            Scene.jarX = jarOff
            Scene.jarFill = min(1f, fill / capacity())
            Scene.beltPos = beltPos
            Scene.boost = boost > 0f
            Scene.tsec = tsec
            val n = min(drops.size, World.MAX_DROPS)
            for (i in 0 until n) {
                Scene.dropX[i] = drops[i].x
                Scene.dropY[i] = drops[i].y
            }
            Scene.dropCount = n
        }
    }

    private fun buy(k: Int) {
        if (k == 3 && autoSell) return
        if (k == 4 && matLv >= 2) return
        val c = costOf(k)
        val free = burners.indexOfFirst { it == 0 }
        if (k == 0 && free < 0) {
            popAt("Merge burners first!", 0f, World.PLATE_Y + 1.4f, 0f, 1.2f)
            return
        }
        if (money < c) return
        money -= c
        when (k) {
            0 -> {
                burners[free] = 1
                burnersBought += 1
            }
            1 -> capLv += 1
            2 -> valLv += 1
            3 -> {
                autoSell = true
                if (phase == 3) finishJar()
            }
            else -> {
                matLv += 1
                iceLeft = iceMax()
                popAt("New: " + matNames[matLv] + "!", 0f, World.PLATE_Y + 1.4f, 0f, 1.4f)
            }
        }
        beep(Sfx.BUY)
        save()
    }

    private fun tapSlot(i: Int) {
        val sel = selected
        if (sel >= 0 && sel != i && burners[sel] > 0 && burners[sel] == burners[i] && burners[i] < 8) {
            burners[i] += 1
            burners[sel] = 0
            selected = -1
            pops.add(Pop("Merged! Lv " + burners[i], slots[i].centerX(), slots[i].top - 6f * d, 1.0f))
            beep(Sfx.MERGE)
            save()
        } else if (burners[i] > 0 && selected != i) {
            selected = i
            beep(Sfx.TAP, 0.6f)
        } else {
            selected = -1
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val x = e.getX(i)
            val y = e.getY(i)
            if (soundRect.contains(x, y)) {
                soundOn = !soundOn
                save()
            } else if (y >= panelTop) {
                for (k in 0..4) {
                    if (btn[k].contains(x, y)) buy(k)
                }
            } else {
                var hit = -1
                for (k in 0..5) {
                    if (slots[k].contains(x, y)) hit = k
                }
                if (hit >= 0) {
                    tapSlot(hit)
                } else if (phase == 3) {
                    finishJar()
                } else {
                    boost = min(1.5f, boost + 0.35f)
                    beep(Sfx.TAP, 0.4f)
                }
            }
        }
        return true
    }

    private fun shade(c: Int, f: Float): Int {
        val r = min(255, (Color.red(c) * f).toInt())
        val g = min(255, (Color.green(c) * f).toInt())
        val b = min(255, (Color.blue(c) * f).toInt())
        return Color.argb(Color.alpha(c), r, g, b)
    }

    private fun label(canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.textSize = size
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawText(s, x + size * 0.05f, y + size * 0.07f, paint)
        paint.color = color
        canvas.drawText(s, x, y, paint)
    }

    /** Raised 3D-looking button: dark base, gradient face, glossy highlight. */
    private fun bevel(canvas: Canvas, r: RectF, color: Int, radius: Float, depth: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = shade(color, 0.55f)
        rect.set(r.left, r.top + depth, r.right, r.bottom + depth)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = LinearGradient(
            0f, r.top, 0f, r.bottom, shade(color, 1.25f), shade(color, 0.85f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
        paint.color = Color.argb(70, 255, 255, 255)
        rect.set(r.left + radius * 0.5f, r.top + radius * 0.25f, r.right - radius * 0.5f, r.top + r.height() * 0.38f)
        canvas.drawRoundRect(rect, radius * 0.6f, radius * 0.6f, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1_000_000_000f)
        lastNs = now
        update(dt)
        publish()
        if (w == 0f) {
            postInvalidateOnAnimation()
            return
        }

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD

        // ---- HUD ----
        label(canvas, "\$" + fmt(money), w / 2f, h * 0.095f, 38f * d, Color.rgb(30, 60, 100))
        val hint = if (phase == 3) "Jar is full! Tap anywhere to sell" else "Tap to melt faster"
        label(canvas, hint, w / 2f, h * 0.095f + 24f * d, 13f * d, Color.argb(220, 30, 60, 100))

        rect2.set(soundRect)
        bevel(canvas, rect2, if (soundOn) Color.rgb(60, 140, 90) else Color.rgb(120, 120, 130), 10f * d, 3f * d)
        label(canvas, if (soundOn) "SFX ON" else "SFX OFF", soundRect.centerX(), soundRect.centerY() + 4f * d, 12f * d, Color.WHITE)

        if (phase == 3) {
            Cam.project(vp, jarOff, World.BELT_TOP + World.JAR_H + 0.35f, 0f, w, h, tmp)
            val al = (170f + 85f * sin(tsec * 6f)).toInt()
            label(canvas, "TAP TO SELL", tmp[0], tmp[1], 22f * d, Color.argb(al, 20, 140, 60))
        }

        // ---- burner slots (3D buttons) ----
        label(canvas, "BURNERS: tap two equal ones to merge", w / 2f, slotsTop - 8f * d, 11f * d, Color.argb(220, 30, 60, 100))
        for (i in 0..5) {
            val lv = burners[i]
            rect2.set(slots[i])
            if (lv > 0) {
                bevel(canvas, rect2, burnerColors[lv - 1], 12f * d, 5f * d)
                label(canvas, lv.toString(), slots[i].centerX(), slots[i].centerY() + 7f * d, 20f * d, Color.WHITE)
            } else {
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = Color.argb(90, 0, 0, 0)
                canvas.drawRoundRect(rect2, 12f * d, 12f * d, paint)
                paint.color = Color.argb(70, 255, 255, 255)
                rect.set(rect2.left, rect2.bottom - 3f * d, rect2.right, rect2.bottom)
                canvas.drawRoundRect(rect, 3f * d, 3f * d, paint)
            }
            if (i == selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f * d
                paint.color = Color.WHITE
                canvas.drawRoundRect(slots[i], 12f * d, 12f * d, paint)
                paint.style = Paint.Style.FILL
            }
        }

        // ---- floating text ----
        for (p in pops) {
            val al = (255f * min(1f, p.life)).toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * d
            paint.textSize = 22f * d
            paint.color = Color.argb(al, 255, 255, 255)
            canvas.drawText(p.text, p.x, p.y, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(al, 20, 140, 60)
            canvas.drawText(p.text, p.x, p.y, paint)
        }

        // ---- upgrade panel ----
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.rgb(40, 44, 60)
        canvas.drawRect(0f, panelTop, w, h, paint)
        val hasFree = burners.any { it == 0 }
        for (i in 0..4) {
            val c = costOf(i)
            val owned = (i == 3 && autoSell) || (i == 4 && matLv >= 2)
            val name = when (i) {
                0 -> "Burner"
                1 -> "Jar size Lv" + capLv
                2 -> "Value Lv" + valLv
                3 -> "Auto-sell"
                else -> if (matLv >= 2) "Material" else "Next: " + matNames[matLv + 1]
            }
            val price = when {
                i == 3 && autoSell -> "ON"
                i == 4 && matLv >= 2 -> "MAX"
                i == 0 && !hasFree -> "Merge first"
                else -> "\$" + fmt(c)
            }
            val col = when {
                owned -> Color.rgb(46, 110, 60)
                i == 0 && !hasFree -> Color.rgb(90, 95, 110)
                money >= c -> Color.rgb(76, 175, 80)
                else -> Color.rgb(90, 95, 110)
            }
            rect2.set(btn[i])
            bevel(canvas, rect2, col, 16f * d, 4f * d)
            label(canvas, name, btn[i].centerX(), btn[i].top + 26f * d, 14f * d, Color.WHITE)
            label(canvas, price, btn[i].centerX(), btn[i].top + 47f * d, 16f * d, Color.WHITE)
        }

        postInvalidateOnAnimation()
    }
}
