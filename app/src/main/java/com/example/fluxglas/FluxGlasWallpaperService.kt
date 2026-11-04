package com.example.fluxglas

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.widget.Toast
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TAG = "FluxGlas"

/** Zeigt 3 s nach dem Sichtbarwerden einen Toast, welcher Lagesensor aktiv ist und ob Events ankommen. Danach auf false setzen. */
private const val DEBUG_SENSOR_TOAST = false

/**
 * Szenen-Konstanten. Einheit: 1.0 = Bildschirmbreite.
 * Der Bildschirm liegt bei z = 0, die Kamera bei z = CAM_Z (Blick Richtung -z).
 * Die Hintergrundebene liegt PLANE_DEPTH (~10 cm) hinter dem Bildschirm, die Kugel liegt darauf.
 */
private object Scene {
    const val CAM_Z = 3.0f
    const val PLANE_DEPTH = 1.43f
    const val SPH_R = 0.4628f                      // so gewählt, dass die Kugel ~0.7 * Bildschirmbreite erscheint
    const val SPH_Z = -(PLANE_DEPTH - SPH_R)       // Kugelmittelpunkt (liegt auf der Ebene)
    const val MARBLE_COUNT = 4 * 42                // 168 Murmeln
    const val REL_IOR = 1.25f                      // Brechungsindex der Kugelfüllung relativ zur Umgebungsflüssigkeit
    const val CIRCLE_R = 0.75f * (CAM_Z + PLANE_DEPTH) / CAM_Z   // Kreise erscheinen mit Durchmesser 1.5 * Breite
}

// ---------------------------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------------------------

class FluxGlasWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GLEngine()

    inner class GLEngine : Engine(), SensorEventListener {

        private var glView: GLView? = null
        private val swarm = Swarm(Scene.MARBLE_COUNT)
        private var surfW = 1
        private var surfH = 1
        private var lastXOffset = -1f
        private var sensorManager: SensorManager? = null
        private val uiHandler = Handler(Looper.getMainLooper())
        private var accCount = 0
        private var rotCount = 0
        private var displayRotDebug = 0

        inner class GLView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = this@GLEngine.surfaceHolder

            fun destroy() {
                super.onDetachedFromWindow()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            glView = GLView(this@FluxGlasWallpaperService).apply {
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                setRenderer(FluxRenderer(swarm))
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfW = max(width, 1)
            surfH = max(height, 1)
            updateDisplayRotation()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                glView?.onResume()
                updateDisplayRotation()
                val sm = sensorManager
                val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (sm != null && acc != null) {
                    sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
                }
                // Lage im Raum: bevorzugt Gyroskop-basiert (ohne Magnetometer), sonst der normale Rotationsvektor.
                // Ohne beide greift der Schwerkraft-Fallback (dann ist die Drehung um die Senkrechte unbekannt).
                val rv = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                    ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (sm != null && rv != null) {
                    sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
                }
                accCount = 0
                rotCount = 0
                val accName = acc?.name ?: "fehlt"
                val rotName = rv?.name ?: "fehlt"
                Log.i(TAG, "Sensoren: Beschleunigung=$accName, Lage=$rotName")
                if (DEBUG_SENSOR_TOAST) {
                    uiHandler.removeCallbacksAndMessages(null)
                    uiHandler.postDelayed({
                        val msg = "FluxGlas: Lagesensor ${if (rv == null) "FEHLT (Fallback Schwerkraft)" else rotName}, " +
                                "Events nach 3 s: Beschl.=$accCount, Lage=$rotCount, Display-Rotation=$displayRotDebug"
                        Log.i(TAG, msg)
                        Toast.makeText(this@FluxGlasWallpaperService, msg, Toast.LENGTH_LONG).show()
                    }, 3000L)
                }
            } else {
                uiHandler.removeCallbacksAndMessages(null)
                sensorManager?.unregisterListener(this)
                glView?.onPause()
            }
        }

        override fun onDestroy() {
            sensorManager?.unregisterListener(this)
            glView?.destroy()
            glView = null
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            swarm.onTouch(
                event.x / surfW,
                event.y / surfH,
                surfH.toFloat() / surfW.toFloat(),
                event.actionMasked,
                event.eventTime
            )
            super.onTouchEvent(event)
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            if (lastXOffset >= 0f) swarm.onScroll(xOffset - lastXOffset)
            lastXOffset = xOffset
        }

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accCount++
                    swarm.setAccel(event.values[0], event.values[1], event.values[2])
                }
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                    rotCount++
                    swarm.setRotationVector(event.values)
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun updateDisplayRotation() {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            displayRotDebug = wm.defaultDisplay.rotation
            swarm.setDisplayRotation(displayRotDebug)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}

// ---------------------------------------------------------------------------------------------
// Schwarm-Simulation (CPU)
//
// Zwei Bezugssysteme:
//  - Bildschirm-Frame (S): x rechts, y oben, z zum Betrachter. Die große Glaskugel ist fest an das Handy
//    gebunden und steht in diesem Frame still. Gerendert wird in S.
//  - Welt-Frame (W): z = oben (Schwerkraft). Die Flüssigkeit mit den Murmeln gehört zur Umgebung:
//    Sie behält ihre Lage im Raum, wenn das Handy gedreht wird, und spürt nur Schwerkraft,
//    die Trägheit bei Beschleunigung des Handys, eine kleine Wandreibung und den Finger.
//  Die Drehmatrix rot (S -> W) kommt aus dem Rotationssensor (Fallback: nur aus der Schwerkraft).
// ---------------------------------------------------------------------------------------------

private const val GRAV = 9.81f

/** Wandreibung: 0 = Flüssigkeit völlig losgelöst vom Gefäß, größer = Wand nimmt sie beim Drehen stärker mit. */
private const val WALL_DRAG = 0.05f

private class Swarm(val n: Int) {

    // Flüssigkeit: Welt-Frame, Ursprung = Kugelmittelpunkt
    private val wx = FloatArray(n)
    private val wy = FloatArray(n)
    private val wz = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val vz = FloatArray(n)
    private val ax = FloatArray(n)
    private val ay = FloatArray(n)
    private val az = FloatArray(n)

    // Murmelpositionen im Bildschirm-Frame (fürs Rendern)
    val px = FloatArray(n)
    val py = FloatArray(n)
    val pz = FloatArray(n)

    val rad = FloatArray(n)
    val cr = FloatArray(n)
    val cg = FloatArray(n)
    val cb = FloatArray(n)
    val spin = FloatArray(n)
    val seed = FloatArray(n)
    private val phase = FloatArray(n)
    private val spinBias = FloatArray(n)
    private val kFlow = FloatArray(n)
    val order = IntArray(n) { it }

    var time = 0f
        private set
    var camX = 0f
        private set
    var camY = 0f
        private set

    // Rohdaten der Sensoren (Gerätekoordinaten des Sensors, nicht des Bildschirms)
    private var accRx = 0f
    private var accRy = GRAV
    private var accRz = 0f
    private val rotRaw = FloatArray(9)
    private var hasRotRaw = false
    private var displayRot = 0

    // Tiefpass der Beschleunigung (Bildschirm-Frame), nur für Kamera und Fallback
    private var gLx = 0f
    private var gLy = GRAV
    private var gLz = 0f

    // Drehmatrix Bildschirm -> Welt (zeilenweise), plus Vorwert
    private val rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val prevRot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val dR = FloatArray(9)
    private var rotInit = false
    private val tmp3 = FloatArray(3)

    // Zusatz-Winkelgeschwindigkeit der Flüssigkeit im Welt-Frame (Wischen, Wandreibung)
    private var wEx = 0f
    private var wEy = 0f
    private var wEz = 0f

    // Touch (Bildschirm-Frame, relativ zum Kugelmittelpunkt)
    private var touching = false
    private var tx = 0f
    private var ty = 0f
    private var fvx = 0f
    private var fvy = 0f
    private var lastTouchMs = 0L

    init {
        val rnd = Random(System.nanoTime())
        for (i in 0 until n) {
            rad[i] = 0.026f + 0.032f * rnd.nextFloat().pow(1.6f)
            var x = 0f
            var y = 0f
            var z = 0f
            do {
                x = rnd.nextFloat() * 2f - 1f
                y = rnd.nextFloat() * 2f - 1f
                z = rnd.nextFloat() * 2f - 1f
            } while (x * x + y * y + z * z > 1f)
            wx[i] = x * 0.85f * Scene.SPH_R
            wy[i] = y * 0.85f * Scene.SPH_R
            wz[i] = z * 0.85f * Scene.SPH_R
            val rgb = hsv(rnd.nextFloat(), 0.72f + 0.25f * rnd.nextFloat(), 0.80f + 0.20f * rnd.nextFloat())
            cr[i] = rgb[0]; cg[i] = rgb[1]; cb[i] = rgb[2]
            spin[i] = rnd.nextFloat() * 6.2831f
            seed[i] = rnd.nextFloat()
            phase[i] = rnd.nextFloat()
            spinBias[i] = (rnd.nextFloat() - 0.5f) * 1.2f
            kFlow[i] = 0.75f + 0.5f * rnd.nextFloat()
        }
        repeat(80) {
            separatePositions()
            for (i in 0 until n) wallClamp(i, false)
        }
        updateScreenPositions()
        sortByDepth()
    }

    private fun hsv(h: Float, s: Float, v: Float): FloatArray {
        val k = floatArrayOf(1f, 2f / 3f, 1f / 3f)
        val out = FloatArray(3)
        for (c in 0..2) {
            val t = h + k[c]
            val f = t - floor(t)
            val x = (abs(f * 6f - 3f) - 1f).coerceIn(0f, 1f)
            out[c] = v * (1f - s + s * x)
        }
        return out
    }

    // --- Sensoren ------------------------------------------------------------------------------

    @Synchronized
    fun setAccel(x: Float, y: Float, z: Float) {
        accRx = x; accRy = y; accRz = z
    }

    @Synchronized
    fun setRotationVector(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotRaw, values)
        hasRotRaw = true
    }

    /** Surface.ROTATION_0 .. ROTATION_270 als 0..3 */
    @Synchronized
    fun setDisplayRotation(r: Int) {
        displayRot = r and 3
    }

    /** Sensor-Frame -> Bildschirm-Frame, Ergebnis in tmp3 */
    private fun toScreen(x: Float, y: Float, z: Float) {
        when (displayRot) {
            1 -> { tmp3[0] = y; tmp3[1] = -x; tmp3[2] = z }
            2 -> { tmp3[0] = -x; tmp3[1] = -y; tmp3[2] = z }
            3 -> { tmp3[0] = -y; tmp3[1] = x; tmp3[2] = z }
            else -> { tmp3[0] = x; tmp3[1] = y; tmp3[2] = z }
        }
    }

    /** Bildschirm-Frame -> Sensor-Frame, Ergebnis in tmp3 */
    private fun toSensor(sx: Float, sy: Float, sz: Float) {
        when (displayRot) {
            1 -> { tmp3[0] = -sy; tmp3[1] = sx; tmp3[2] = sz }
            2 -> { tmp3[0] = -sx; tmp3[1] = -sy; tmp3[2] = sz }
            3 -> { tmp3[0] = sy; tmp3[1] = -sx; tmp3[2] = sz }
            else -> { tmp3[0] = sx; tmp3[1] = sy; tmp3[2] = sz }
        }
    }

    /** Berechnet rot (Bildschirm -> Welt) aus dem Rotationssensor, sonst aus der Schwerkraft. */
    private fun rebuildRotation() {
        if (hasRotRaw) {
            for (j in 0..2) {
                toSensor(if (j == 0) 1f else 0f, if (j == 1) 1f else 0f, if (j == 2) 1f else 0f)
                val s0 = tmp3[0]
                val s1 = tmp3[1]
                val s2 = tmp3[2]
                for (i in 0..2) {
                    rot[3 * i + j] = rotRaw[3 * i] * s0 + rotRaw[3 * i + 1] * s1 + rotRaw[3 * i + 2] * s2
                }
            }
        } else {
            // Fallback ohne Gyroskop: nur die Neigung (oben = Gegenrichtung der Schwerkraft) ist bekannt
            var ux = gLx
            var uy = gLy
            var uz = gLz
            var len = sqrt(ux * ux + uy * uy + uz * uz)
            if (len < 1e-3f) { ux = 0f; uy = 1f; uz = 0f; len = 1f }
            ux /= len; uy /= len; uz /= len
            val rx: Float
            val ry: Float
            val rz: Float
            if (abs(uz) > 0.7f) { rx = 0f; ry = 1f; rz = 0f } else { rx = 0f; ry = 0f; rz = -1f }
            var exx = ry * uz - rz * uy
            var exy = rz * ux - rx * uz
            var exz = rx * uy - ry * ux
            val el = max(sqrt(exx * exx + exy * exy + exz * exz), 1e-4f)
            exx /= el; exy /= el; exz /= el
            val eyx = uy * exz - uz * exy
            val eyy = uz * exx - ux * exz
            val eyz = ux * exy - uy * exx
            rot[0] = exx; rot[1] = exy; rot[2] = exz
            rot[3] = eyx; rot[4] = eyy; rot[5] = eyz
            rot[6] = ux; rot[7] = uy; rot[8] = uz
        }
    }

    /** Fügt der Flüssigkeit eine Winkelgeschwindigkeit hinzu, angegeben im Bildschirm-Frame. */
    private fun addSwirlScreen(sx: Float, sy: Float, sz: Float) {
        wEx = (wEx + rot[0] * sx + rot[1] * sy + rot[2] * sz).coerceIn(-4f, 4f)
        wEy = (wEy + rot[3] * sx + rot[4] * sy + rot[5] * sz).coerceIn(-4f, 4f)
        wEz = (wEz + rot[6] * sx + rot[7] * sy + rot[8] * sz).coerceIn(-4f, 4f)
    }

    @Synchronized
    fun onScroll(deltaX: Float) {
        addSwirlScreen(0f, deltaX * 8f, 0f)
    }

    @Synchronized
    fun onTouch(nx: Float, ny: Float, aspect: Float, action: Int, tMs: Long) {
        val sx = nx - 0.5f
        val sy = (0.5f - ny) * aspect
        // Strahl Kamera -> Bildschirmpunkt, geschnitten mit der Ebene z = Kugelmitte
        val s = (Scene.CAM_Z - Scene.SPH_Z) / Scene.CAM_Z
        val tpx = camX + (sx - camX) * s
        val tpy = camY + (sy - camY) * s
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                tx = tpx; ty = tpy
                fvx = 0f; fvy = 0f
                lastTouchMs = tMs
            }
            MotionEvent.ACTION_MOVE -> if (touching) {
                val dtS = max((tMs - lastTouchMs) / 1000f, 0.008f)
                val dx = tpx - tx
                val dy = tpy - ty
                fvx = (0.6f * fvx + 0.4f * (dx / dtS)).coerceIn(-3f, 3f)
                fvy = (0.6f * fvy + 0.4f * (dy / dtS)).coerceIn(-3f, 3f)
                // Trackball: Wischbewegung dreht die Flüssigkeit um die dazu senkrechte Achse
                addSwirlScreen(-dy / Scene.SPH_R * 2.5f, dx / Scene.SPH_R * 2.5f, 0f)
                tx = tpx; ty = tpy
                lastTouchMs = tMs
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
    }

    // --- Simulation ----------------------------------------------------------------------------

    @Synchronized
    fun update(dtIn: Float) {
        val dt = dtIn.coerceIn(0.001f, 0.033f)
        time += dt

        // Beschleunigung im Bildschirm-Frame, gefiltert
        toScreen(accRx, accRy, accRz)
        val ax0 = tmp3[0]
        val ay0 = tmp3[1]
        val az0 = tmp3[2]
        gLx += (ax0 - gLx) * 0.05f
        gLy += (ay0 - gLy) * 0.05f
        gLz += (az0 - gLz) * 0.05f

        // Lage des Handys im Raum
        System.arraycopy(rot, 0, prevRot, 0, 9)
        rebuildRotation()
        if (!rotInit) {
            System.arraycopy(rot, 0, prevRot, 0, 9)
            rotInit = true
        }

        // Schwerkraft ("oben") im Bildschirm-Frame laut Lage; Rest der Beschleunigung = Bewegung des Handys
        val gx = GRAV * rot[6]
        val gy = GRAV * rot[7]
        val gz = GRAV * rot[8]
        val lx = ax0 - gx
        val ly = ay0 - gy
        val lz = az0 - gz
        // lineare Beschleunigung des Handys im Welt-Frame
        val lwx = rot[0] * lx + rot[1] * ly + rot[2] * lz
        val lwy = rot[3] * lx + rot[4] * ly + rot[5] * lz
        val lwz = rot[6] * lx + rot[7] * ly + rot[8] * lz

        // Kamera-Parallaxe: Neigung + leichtes Eigenschweben
        camX = (gLx / GRAV * 0.10f).coerceIn(-0.12f, 0.12f) + 0.020f * sin(time * 0.21f)
        camY = ((gLz - 3f) / GRAV * 0.10f).coerceIn(-0.10f, 0.10f) + 0.015f * cos(time * 0.17f)

        // Wandreibung: Drehung des Gefäßes nimmt die Flüssigkeit ein wenig mit (dR = rot * prevRot^T, Welt-Frame)
        for (i in 0..2) {
            for (j in 0..2) {
                dR[3 * i + j] = rot[3 * i] * prevRot[3 * j] + rot[3 * i + 1] * prevRot[3 * j + 1] +
                        rot[3 * i + 2] * prevRot[3 * j + 2]
            }
        }
        val dwx = (0.5f * (dR[7] - dR[5])).coerceIn(-0.2f, 0.2f)
        val dwy = (0.5f * (dR[2] - dR[6])).coerceIn(-0.2f, 0.2f)
        val dwz = (0.5f * (dR[3] - dR[1])).coerceIn(-0.2f, 0.2f)
        wEx = (wEx + dwx * WALL_DRAG).coerceIn(-4f, 4f)
        wEy = (wEy + dwy * WALL_DRAG).coerceIn(-4f, 4f)
        wEz = (wEz + dwz * WALL_DRAG).coerceIn(-4f, 4f)

        // Schwerkraft (schwach, Murmeln sind fast schwebend) und Trägheit der Flüssigkeit, beides im Welt-Frame
        val gaX = -0.06f * lwx
        val gaY = -0.06f * lwy
        val gaZ = -GRAV * 0.03f - 0.06f * lwz

        // Basis-Rotationsachse der Strömung (Welt-Frame, überwiegend senkrecht) wandert langsam
        var bx = 0.6f * sin(time * 0.17f)
        var by = 0.6f * cos(time * 0.13f)
        var bz = 1.0f
        val bl = sqrt(bx * bx + by * by + bz * bz)
        val baseSpeed = 0.7f
        bx = bx / bl * baseSpeed
        by = by / bl * baseSpeed
        bz = bz / bl * baseSpeed

        val h = dt / 2f
        repeat(2) {
            for (i in 0 until n) { ax[i] = gaX; ay[i] = gaY; az[i] = gaZ }

            for (i in 0 until n) {
                val ph = phase[i]
                val wxi = bx + wEx + 0.30f * sin(time * 0.31f + ph * 6.283f)
                val wyi = by + wEy + 0.30f * cos(time * 0.27f + ph * 4.1f)
                val wzi = bz + wEz + 0.30f * sin(time * 0.23f + ph * 9.7f)
                val k = kFlow[i]
                val x = wx[i]
                val y = wy[i]
                val z = wz[i]
                // Strömung = w x p  plus kleines individuelles Wandern
                val fx = (wyi * z - wzi * y) * k + 0.08f * sin(time * 0.8f + ph * 11f)
                val fy = (wzi * x - wxi * z) * k + 0.08f * cos(time * 0.9f + ph * 7f)
                val fz = (wxi * y - wyi * x) * k + 0.08f * sin(time * 0.7f + ph * 5f)
                ax[i] += (fx - vx[i]) * 2.2f
                ay[i] += (fy - vy[i]) * 2.2f
                az[i] += (fz - vz[i]) * 2.2f

                // Finger auf dem Glas (Berechnung im Bildschirm-Frame, Kraft zurück in den Welt-Frame)
                if (touching) {
                    val sxp = rot[0] * x + rot[3] * y + rot[6] * z
                    val syp = rot[1] * x + rot[4] * y + rot[7] * z
                    val dx = sxp - tx
                    val dy = syp - ty
                    val d2 = dx * dx + dy * dy
                    if (d2 < 0.16f) {
                        val sw = exp(-d2 / 0.04f)
                        val svx = rot[0] * vx[i] + rot[3] * vy[i] + rot[6] * vz[i]
                        val svy = rot[1] * vx[i] + rot[4] * vy[i] + rot[7] * vz[i]
                        val fsx = sw * ((fvx - svx) * 10f + dx * 40f)
                        val fsy = sw * ((fvy - svy) * 10f + dy * 40f)
                        ax[i] += rot[0] * fsx + rot[1] * fsy
                        ay[i] += rot[3] * fsx + rot[4] * fsy
                        az[i] += rot[6] * fsx + rot[7] * fsy
                    }
                }
            }

            // Abstoßung zwischen Murmeln
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = wx[i] - wx[j]
                    val dy = wy[i] - wy[j]
                    val dz = wz[i] - wz[j]
                    val lim = (rad[i] + rad[j]) * 1.04f
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 < lim * lim) {
                        val d = max(sqrt(d2), 1e-4f)
                        val f = (lim - d) * 90f / d
                        ax[i] += dx * f; ay[i] += dy * f; az[i] += dz * f
                        ax[j] -= dx * f; ay[j] -= dy * f; az[j] -= dz * f
                    }
                }
            }

            // Integration + Wand
            for (i in 0 until n) {
                vx[i] += ax[i] * h
                vy[i] += ay[i] * h
                vz[i] += az[i] * h
                val sp2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]
                if (sp2 > 1.96f) {
                    val s = 1.4f / sqrt(sp2)
                    vx[i] *= s; vy[i] *= s; vz[i] *= s
                }
                wx[i] += vx[i] * h
                wy[i] += vy[i] * h
                wz[i] += vz[i] * h
                wallClamp(i, true)
            }
        }

        // Eigendrehung der Murmeln
        for (i in 0 until n) {
            val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i])
            spin[i] += (sp * 8f + spinBias[i]) * dt
        }

        // Abklingen
        val decay = exp(-dt / 2.5f)
        wEx *= decay; wEy *= decay; wEz *= decay
        val fd = exp(-dt * 8f)
        fvx *= fd; fvy *= fd

        updateScreenPositions()
        sortByDepth()
    }

    /** Welt-Frame -> Bildschirm-Frame (rot^T * p). */
    private fun updateScreenPositions() {
        for (i in 0 until n) {
            val x = wx[i]
            val y = wy[i]
            val z = wz[i]
            px[i] = rot[0] * x + rot[3] * y + rot[6] * z
            py[i] = rot[1] * x + rot[4] * y + rot[7] * z
            pz[i] = rot[2] * x + rot[5] * y + rot[8] * z
        }
    }

    private fun wallClamp(i: Int, withVelocity: Boolean) {
        val rin = Scene.SPH_R * 0.955f
        val lim = rin - rad[i]
        val len = sqrt(wx[i] * wx[i] + wy[i] * wy[i] + wz[i] * wz[i])
        if (len > lim && len > 1e-5f) {
            val nx = wx[i] / len
            val ny = wy[i] / len
            val nz = wz[i] / len
            wx[i] = nx * lim; wy[i] = ny * lim; wz[i] = nz * lim
            if (withVelocity) {
                val vn = vx[i] * nx + vy[i] * ny + vz[i] * nz
                if (vn > 0f) {
                    vx[i] -= nx * vn * 1.3f
                    vy[i] -= ny * vn * 1.3f
                    vz[i] -= nz * vn * 1.3f
                }
            }
        }
    }

    private fun separatePositions() {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = wx[i] - wx[j]
                val dy = wy[i] - wy[j]
                val dz = wz[i] - wz[j]
                val lim = (rad[i] + rad[j]) * 1.04f
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < lim * lim) {
                    val d = max(sqrt(d2), 1e-4f)
                    val s = (lim - d) * 0.5f / d
                    wx[i] += dx * s; wy[i] += dy * s; wz[i] += dz * s
                    wx[j] -= dx * s; wy[j] -= dy * s; wz[j] -= dz * s
                }
            }
        }
    }

    /** Hinten (kleines z im Bildschirm-Frame) zuerst – Insertion Sort, von Frame zu Frame fast sortiert. */
    private fun sortByDepth() {
        for (a in 1 until n) {
            val idx = order[a]
            val z = pz[idx]
            var b = a - 1
            while (b >= 0 && pz[order[b]] > z) {
                order[b + 1] = order[b]
                b--
            }
            order[b + 1] = idx
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shader
// ---------------------------------------------------------------------------------------------

private val COMMON = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes;
uniform float uTime;
uniform vec2 uCam;
const float C = ${Scene.CAM_Z};
const float PD = ${Scene.PLANE_DEPTH};
const float R = ${Scene.SPH_R};
const float ETA = ${Scene.REL_IOR};
const vec3 SC = vec3(0.0, 0.0, ${Scene.SPH_Z});
const vec3 LD = vec3(-0.398, 0.597, 0.696);

void makeRay(out vec3 ro, out vec3 rd) {
    vec2 pw = vec2(gl_FragCoord.x / uRes.x - 0.5, (gl_FragCoord.y / uRes.y - 0.5) * uRes.y / uRes.x);
    ro = vec3(uCam, C);
    rd = normalize(vec3(pw, 0.0) - ro);
}
"""

private const val FULLSCREEN_VS = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

/** Pass 1: Hintergrundebene mit großen Kreisen, Wellen, Kaustiken, Schatten + Sicht durch die Kugel (Brechung). */
private val BG_FS = COMMON + """
const float CR = ${Scene.CIRCLE_R};

vec3 hsv(float h, float s, float v) {
    vec3 k = clamp(abs(fract(h + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return v * mix(vec3(1.0), k, s);
}

vec3 plane(vec2 h) {
    float t = uTime;
    h += 0.012 * vec2(sin(h.y * 7.0 + t * 0.8 + sin(h.x * 5.0)), cos(h.x * 6.0 - t * 0.7));
    vec3 col = vec3(0.03, 0.06, 0.09);
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        vec2 c = vec2(0.45 * sin(t * 0.031 * (1.0 + 0.21 * fi) + fi * 2.1),
                      1.05 * cos(t * 0.023 * (1.0 + 0.13 * fi) + fi * 1.3));
        float dd = length(h - c);
        float m = 1.0 - smoothstep(CR - 0.01, CR + 0.01, dd);
        float hue = fract(0.03 + fi * 0.143 + 0.03 * sin(t * 0.05 + fi));
        vec3 cc = hsv(hue, 0.8, 0.95) * (0.72 + 0.28 * smoothstep(0.0, CR, dd));
        col = mix(col, cc, m * 0.8);
    }
    // Kaustiken (Licht im Wasser)
    float ca = sin(h.x * 13.0 + t * 0.6 + 2.0 * sin(h.y * 8.0 + t * 0.5))
             * sin(h.y * 12.0 - t * 0.5 + 2.0 * sin(h.x * 7.0 - t * 0.4));
    col += pow(max(ca, 0.0), 3.0) * 0.12 * vec3(0.8, 1.0, 1.0);
    // Schatten der Kugel + gebündelter Lichtfleck
    float tS = (SC.z + PD) / LD.z;
    vec2 shc = SC.xy - LD.xy * tS;
    float sd = length(h - shc) / R;
    col *= 1.0 - 0.5 * smoothstep(1.3, 0.4, sd);
    col += (col * 0.9 + 0.08) * exp(-sd * sd * 7.0);
    // Kontaktschatten unter der Kugel
    float ao = exp(-dot(h - SC.xy, h - SC.xy) * 40.0);
    col *= 1.0 - 0.55 * ao;
    return col;
}

vec3 bgAt(vec3 o, vec3 d, float t0) {
    if (d.z > -0.03) return vec3(0.02, 0.05, 0.07);
    float t = (-PD - o.z) / d.z;
    vec3 col = plane(o.xy + d.xy * t);
    float dist = t0 + t;
    return mix(col, vec3(0.02, 0.10, 0.13), 1.0 - exp(-dist * 0.05));
}

void main() {
    vec3 ro, rd;
    makeRay(ro, rd);
    vec3 oc = ro - SC;
    float b = dot(oc, rd);
    float dmin = sqrt(max(dot(oc, oc) - b * b, 0.0));
    vec3 col = bgAt(ro, rd, 0.0);
    float aa = 2.2 / uRes.x;
    float cover = clamp((R + 0.5 * aa - dmin) / aa, 0.0, 1.0);
    if (cover > 0.0) {
        float disc = max(b * b - (dot(oc, oc) - R * R), 0.0);
        float t1 = -b - sqrt(disc);
        vec3 P1 = ro + rd * t1;
        vec3 N1 = normalize(P1 - SC);
        vec3 r1 = refract(rd, N1, 1.0 / ETA);
        float tin = -2.0 * dot(P1 - SC, r1);
        vec3 P2 = P1 + r1 * tin;
        vec3 N2 = normalize(P2 - SC);
        vec3 r2 = refract(r1, -N2, ETA);
        if (dot(r2, r2) < 0.5) r2 = reflect(r1, -N2);
        vec3 inside = bgAt(P2, r2, t1 + tin) * vec3(0.93, 1.0, 1.0);
        col = mix(col, inside, cover);
    }
    gl_FragColor = vec4(col, 1.0);
}
"""

/** Pass 3: Glashülle der Kugel (Fresnel-Reflexion einer Studio-Umgebung, dunkler Rand). Premultiplied Alpha. */
private val SHELL_FS = COMMON + """
float bx(vec2 p, vec2 c, vec2 h, float s) {
    vec2 d = abs(p - c) - h;
    return 1.0 - smoothstep(-s, s, max(d.x, d.y));
}

vec3 env(vec3 r) {
    float up = r.y * 0.5 + 0.5;
    vec3 e = mix(vec3(0.02, 0.07, 0.09), vec3(0.40, 0.58, 0.68), up * up);
    vec2 q = r.xy / max(r.z + 0.45, 0.25);
    float win = bx(q, vec2(-0.75, 0.80), vec2(0.42, 0.46), 0.05);
    win *= 1.0 - 0.75 * (bx(q, vec2(-0.75, 0.80), vec2(0.012, 0.46), 0.01)
                       + bx(q, vec2(-0.75, 0.80), vec2(0.42, 0.012), 0.01));
    float strip = bx(q, vec2(0.85, -0.65), vec2(0.22, 0.10), 0.06);
    e += vec3(2.4, 2.35, 2.25) * win + vec3(0.7, 0.9, 1.0) * strip * 0.9;
    return e;
}

void main() {
    vec3 ro, rd;
    makeRay(ro, rd);
    vec3 oc = ro - SC;
    float b = dot(oc, rd);
    float dmin = sqrt(max(dot(oc, oc) - b * b, 0.0));
    float aa = 2.2 / uRes.x;
    float cover = clamp((R + 0.5 * aa - dmin) / aa, 0.0, 1.0);
    if (cover <= 0.0) discard;
    float disc = max(b * b - (dot(oc, oc) - R * R), 0.0);
    float t1 = -b - sqrt(disc);
    vec3 N = normalize(ro + rd * t1 - SC);
    float cosT = clamp(dot(-rd, N), 0.0, 1.0);
    float F = 0.03 + 0.97 * pow(1.0 - cosT, 5.0);
    vec3 r = reflect(rd, N);
    vec3 e = env(r);
    float dark = pow(1.0 - cosT, 3.0) * 0.35;
    float alpha = clamp(F + dark, 0.0, 1.0) * cover;
    vec3 rgb = e * F * cover;
    gl_FragColor = vec4(rgb, alpha);
}
"""

private const val MARBLE_VS = """
attribute vec4 aA;   // corner.xy, center.xy (NDC)
attribute vec4 aB;   // radius.xy (NDC), spin, seed
attribute vec4 aC;   // rgb, depth shade
varying vec2 vUv;
varying vec4 vB;
varying vec4 vC;
void main() {
    vUv = aA.xy;
    vB = aB;
    vC = aC;
    gl_Position = vec4(aA.zw + aA.xy * aB.xy, 0.0, 1.0);
}
"""

/** Pass 2: Glasmurmeln als Kugel-Impostor (Farbkern, Fresnel, Glanzlichter, Rückseiten-Glühen). */
private const val MARBLE_FS = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
varying vec4 vB;
varying vec4 vC;

void main() {
    float d2 = dot(vUv, vUv);
    float d = sqrt(d2);
    float edge = 1.0 - smoothstep(0.955, 1.0, d);
    if (edge <= 0.0) discard;
    float z = sqrt(max(1.0 - d2, 0.0));
    vec3 n = vec3(vUv, z);
    vec3 col = vC.rgb;

    // rotierendes Farbband im Glaskern
    float s1 = sin(vB.z);
    float c1 = cos(vB.z);
    vec3 q = vec3(c1 * n.x + s1 * n.z, n.y, -s1 * n.x + c1 * n.z);
    float tilt = vB.w * 6.2831;
    float s2 = sin(tilt);
    float c2 = cos(tilt);
    q = vec3(q.x, c2 * q.y - s2 * q.z, s2 * q.y + c2 * q.z);
    float band = abs(q.x * 0.9 + 0.4 * sin(q.y * 3.2 + vB.w * 20.0));
    float rib = 1.0 - smoothstep(0.08, 0.24, band);
    vec3 ribCol = mix(col.gbr, vec3(1.0), 0.55);
    vec3 body = mix(col, ribCol, rib * 0.85);

    float fr = 0.04 + 0.96 * pow(1.0 - z, 4.0);
    vec3 r = vec3(2.0 * z * n.x, 2.0 * z * n.y, 2.0 * z * z - 1.0);
    float lg = max(dot(r, normalize(vec3(-0.5, 0.7, 0.5))), 0.0);
    vec3 envc = mix(vec3(0.10, 0.18, 0.22), vec3(0.75, 0.9, 1.0), r.y * 0.5 + 0.5) + vec3(1.6) * pow(lg, 12.0);

    vec3 L = normalize(vec3(-0.40, 0.60, 0.70));
    float spec = pow(max(dot(n, normalize(L + vec3(0.0, 0.0, 1.0))), 0.0), 110.0) * 1.6;
    float spec2 = pow(max(dot(n, normalize(vec3(0.5, -0.55, 0.65) + vec3(0.0, 0.0, 1.0))), 0.0), 35.0) * 0.3;
    float back = pow(max(dot(n, normalize(vec3(0.5, -0.6, 0.25))), 0.0), 3.0);

    vec3 inner = body * (0.40 + 0.65 * z * z) + body * back * 0.55;
    float a0 = mix(0.60, 0.95, d2);
    vec3 rgb = inner * (1.0 - fr) * a0 + envc * fr + vec3(spec + spec2);
    float a = clamp(a0 + fr + spec, 0.0, 1.0);
    rgb *= vC.a;
    gl_FragColor = vec4(rgb, a) * edge;
}
"""

// ---------------------------------------------------------------------------------------------
// Renderer
// ---------------------------------------------------------------------------------------------

private class FluxRenderer(private val swarm: Swarm) : GLSurfaceView.Renderer {

    private class Prog(val id: Int) {
        val uRes = GLES20.glGetUniformLocation(id, "uRes")
        val uTime = GLES20.glGetUniformLocation(id, "uTime")
        val uCam = GLES20.glGetUniformLocation(id, "uCam")
        val aPos = GLES20.glGetAttribLocation(id, "aPos")
        val aA = GLES20.glGetAttribLocation(id, "aA")
        val aB = GLES20.glGetAttribLocation(id, "aB")
        val aC = GLES20.glGetAttribLocation(id, "aC")
    }

    private var bgProg: Prog? = null
    private var shellProg: Prog? = null
    private var marbleProg: Prog? = null

    private var resX = 1f
    private var resY = 1f
    private var lastNanos = 0L

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val floatsPerVertex = 12
    private val marbleBuf: FloatBuffer =
        ByteBuffer.allocateDirect(swarm.n * 6 * floatsPerVertex * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val cornerX = floatArrayOf(-1f, 1f, 1f, -1f, 1f, -1f)
    private val cornerY = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f)
    private val expand = 1.08f

    private fun floatBuffer(a: FloatArray): FloatBuffer {
        val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(a)
        b.position(0)
        return b
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            Log.e(TAG, "Shader-Fehler: $log")
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader-Fehler: $log")
        }
        return s
    }

    private fun link(vs: String, fs: String): Prog {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            Log.e(TAG, "Link-Fehler: $log")
            throw RuntimeException("Link-Fehler: $log")
        }
        return Prog(p)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        bgProg = link(FULLSCREEN_VS, BG_FS)
        shellProg = link(FULLSCREEN_VS, SHELL_FS)
        marbleProg = link(MARBLE_VS, MARBLE_FS)
        lastNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        resX = max(width, 1).toFloat()
        resY = max(height, 1).toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        swarm.update(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glDisable(GLES20.GL_BLEND)
        drawFullscreen(bgProg!!)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawMarbles(marbleProg!!)
        drawFullscreen(shellProg!!)
    }

    private fun drawFullscreen(p: Prog) {
        GLES20.glUseProgram(p.id)
        GLES20.glUniform2f(p.uRes, resX, resY)
        GLES20.glUniform1f(p.uTime, swarm.time)
        GLES20.glUniform2f(p.uCam, swarm.camX, swarm.camY)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPos)
    }

    private fun drawMarbles(p: Prog) {
        val aspect = resY / resX
        val camX = swarm.camX
        val camY = swarm.camY
        val c = Scene.CAM_Z
        val buf = marbleBuf
        buf.clear()
        for (k in 0 until swarm.n) {
            val i = swarm.order[k]
            val zw = Scene.SPH_Z + swarm.pz[i]
            val t = c / (c - zw)                       // Perspektive auf die Bildschirmebene
            val sx = camX + (swarm.px[i] - camX) * t
            val sy = camY + (swarm.py[i] - camY) * t
            val rs = swarm.rad[i] * t
            val ndcX = 2f * sx
            val ndcY = 2f * sy / aspect
            val rx = 2f * rs
            val ry = 2f * rs / aspect
            val depth = (swarm.pz[i] / Scene.SPH_R) * 0.5f + 0.5f
            val shade = 0.74f + 0.26f * depth.coerceIn(0f, 1f)
            for (v in 0 until 6) {
                buf.put(cornerX[v] * expand)
                buf.put(cornerY[v] * expand)
                buf.put(ndcX)
                buf.put(ndcY)
                buf.put(rx)
                buf.put(ry)
                buf.put(swarm.spin[i])
                buf.put(swarm.seed[i])
                buf.put(swarm.cr[i])
                buf.put(swarm.cg[i])
                buf.put(swarm.cb[i])
                buf.put(shade)
            }
        }
        GLES20.glUseProgram(p.id)
        val stride = floatsPerVertex * 4
        GLES20.glEnableVertexAttribArray(p.aA)
        GLES20.glEnableVertexAttribArray(p.aB)
        GLES20.glEnableVertexAttribArray(p.aC)
        buf.position(0)
        GLES20.glVertexAttribPointer(p.aA, 4, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(4)
        GLES20.glVertexAttribPointer(p.aB, 4, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(8)
        GLES20.glVertexAttribPointer(p.aC, 4, GLES20.GL_FLOAT, false, stride, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, swarm.n * 6)
        GLES20.glDisableVertexAttribArray(p.aA)
        GLES20.glDisableVertexAttribArray(p.aB)
        GLES20.glDisableVertexAttribArray(p.aC)
    }
}