package com.example.fluxglas

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val T_TAG = "FluxTorus"

/** Zeigt 3 s nach dem Sichtbarwerden einen Toast, welcher Lagesensor aktiv ist. Nur zum Testen auf true setzen. */
private const val T_DEBUG_SENSOR_TOAST = false

private const val T_GRAV = 9.81f
private const val T_TWO_PI = 6.2831855f

// ---------------------------------------------------------------------------------------------
// Stellschrauben
// ---------------------------------------------------------------------------------------------

/** Trägheit der Flüssigkeit bei Drehung des Handys: 1.0 = bleibt exakt im Raum stehen, kleiner = Wand nimmt sie mit. */
private const val T_INERTIA = 0.97f

/** Kleine Drehung (um die Rohrachse): Antrieb pro m/s² Beschleunigung senkrecht zum Display, in rad/s². */
private const val T_PUMP_GAIN = 2.0f

/** Beschleunigungen senkrecht zum Display unterhalb dieser Schwelle (m/s²) zählen nicht (Sensorrauschen). */
private const val T_PUMP_DEADZONE = 0.5f

/** false: jede Bewegung senkrecht zum Display (vor oder zurück) treibt innen ins Display hinein.
 *  true: physikalisch vorzeichenrichtig (Beschleunigung zum Betrachter hin treibt hinein, davon weg heraus). */
private const val T_PUMP_SIGNED = false

/** Abklingzeit der kleinen Drehung in Sekunden. */
private const val T_POLOIDAL_DECAY_S = 3.0f

/** Stärke der natürlichen Schwarmbewegung (schwach, klein), in Bildschirmbreiten pro Sekunde. */
private const val T_NATURAL_SPEED = 0.012f

/** Anführer: zufällige Wartezeit zwischen zwei Anführern in Sekunden (1 bis 5 Minuten). */
private const val T_LEADER_MIN_S = 60f
private const val T_LEADER_MAX_S = 300f

/** Anführer: Gesamtdauer, davon je Ein- und Ausblenden (sanft), und Geschwindigkeit entlang des Rings. */
private const val T_LEADER_DURATION_S = 30f
private const val T_LEADER_FADE_S = 6f
private const val T_LEADER_SPEED = 0.14f

/** Video-Variante: Name der Videodatei in res/raw (ohne Endung) und des Standbilds in res/drawable (ohne Endung). */
private const val T_VIDEO_RES = "unlocked"
private const val T_IMAGE_RES = "backup"

/** Video-Variante: Dauer der weichen Überblendung zwischen Standbild und Video in Sekunden. */
private const val T_VIDEO_FADE_S = 0.25f

/** Video-Variante: Mindestabstand zwischen zwei Video-Starts in Millisekunden (verhindert Doppelstarts). */
private const val T_VIDEO_DEBOUNCE_MS = 400L

/**
 * Szenen-Konstanten. Einheit: 1.0 = Bildschirmbreite.
 * Der Bildschirm liegt bei z = 0, die Kamera bei z = CAM_Z (Blick Richtung -z).
 * Der Torus liegt flach auf der Hintergrundebene (PLANE_DEPTH ~ 10 cm hinter dem Bildschirm),
 * seine Achse steht senkrecht zum Display.
 */
private object TorusScene {
    const val CAM_Z = 3.0f
    const val PLANE_DEPTH = 1.43f
    const val RING_R = 0.40f                                  // Radius des großen Rings (Mitte des Rohrs)
    const val TUBE_R = 0.175f                                 // Radius des Glasrohrs (Torus erscheint ca. 0.8 * Bildschirmbreite breit)
    const val TZ = -(PLANE_DEPTH - TUBE_R)                    // Mittelebene des Torus (Rohr liegt auf der Ebene)
    const val BOUND_R = RING_R + TUBE_R + 0.01f               // Hüllkugel für das Raymarching
    const val MARBLE_COUNT = 4 * 42                           // 168 Murmeln
    const val REL_IOR = 1.25f                                 // Brechungsindex der Füllung relativ zur Umgebung
    const val CIRCLE_R = 0.75f * (CAM_Z + PLANE_DEPTH) / CAM_Z   // Kreise erscheinen mit Durchmesser 1.5 * Breite
}

// ---------------------------------------------------------------------------------------------
// Services
//  - FluxTorusWallpaperService: Glastorus vor dem bunten Kreise-Hintergrund
//  - FluxTorusVideoWallpaperService: Glastorus vor einem Video, das bei jedem Sichtbarwerden einmal abläuft
//    und danach durch ein Standbild ersetzt wird
// ---------------------------------------------------------------------------------------------

open class FluxTorusWallpaperService : WallpaperService() {

    /** true = Hintergrund ist das Video mit Standbild, false = bunte Kreise. */
    protected open fun useVideo(): Boolean = false

    override fun onCreateEngine(): Engine = GLEngine()

    inner class GLEngine : Engine(), SensorEventListener {

        private val videoMode = useVideo()
        private var glView: GLView? = null
        private var renderer: TorusRenderer? = null
        private val swarm = TorusSwarm(TorusScene.MARBLE_COUNT)
        private var surfW = 1
        private var surfH = 1
        private var lastXOffset = -1f
        private var sensorManager: SensorManager? = null
        private val uiHandler = Handler(Looper.getMainLooper())
        private var accCount = 0
        private var rotCount = 0
        private var displayRotDebug = 0

        /** Nach dem Entsperren (ACTION_USER_PRESENT) das Video erneut abspielen. */
        private val userPresentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (isVisible) startVideoIfUnlocked()
            }
        }
        private var receiverRegistered = false

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
            val r = TorusRenderer(swarm, applicationContext, videoMode)
            renderer = r
            glView = GLView(this@FluxTorusWallpaperService).apply {
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                setRenderer(r)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            if (videoMode) {
                val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(userPresentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(userPresentReceiver, filter)
                }
                receiverRegistered = true
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfW = max(width, 1)
            surfH = max(height, 1)
            updateDisplayRotation()
        }

        /** Startet das Video nur, wenn das Gerät nicht gesperrt ist (also nach dem Entsperren oder auf dem Homescreen). */
        private fun startVideoIfUnlocked() {
            if (!videoMode) return
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) renderer?.requestVideoStart()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                glView?.onResume()
                updateDisplayRotation()
                startVideoIfUnlocked()
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
                Log.i(T_TAG, "Sensoren: Beschleunigung=$accName, Lage=$rotName")
                if (T_DEBUG_SENSOR_TOAST) {
                    uiHandler.removeCallbacksAndMessages(null)
                    uiHandler.postDelayed({
                        val msg = "FluxTorus: Lagesensor ${if (rv == null) "FEHLT (Fallback Schwerkraft)" else rotName}, " +
                            "Events nach 3 s: Beschl.=$accCount, Lage=$rotCount, Display-Rotation=$displayRotDebug"
                        Log.i(T_TAG, msg)
                        Toast.makeText(this@FluxTorusWallpaperService, msg, Toast.LENGTH_LONG).show()
                    }, 3000L)
                }
            } else {
                uiHandler.removeCallbacksAndMessages(null)
                renderer?.requestVideoStop()
                sensorManager?.unregisterListener(this)
                glView?.onPause()
            }
        }

        override fun onDestroy() {
            uiHandler.removeCallbacksAndMessages(null)
            if (receiverRegistered) {
                try {
                    unregisterReceiver(userPresentReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(T_TAG, "Receiver war nicht registriert", e)
                }
                receiverRegistered = false
            }
            renderer?.shutdown()
            sensorManager?.unregisterListener(this)
            glView?.destroy()
            glView = null
            renderer = null
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

        /** Drehung des Standard-Displays über den DisplayManager (funktioniert aus jedem Context, nicht veraltet). */
        private fun updateDisplayRotation() {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            displayRotDebug = display.rotation
            swarm.setDisplayRotation(displayRotDebug)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}

/** Zweite wählbare Variante: Glastorus vor dem Video (einmal beim Sichtbarwerden) bzw. dem Standbild. */
class FluxTorusVideoWallpaperService : FluxTorusWallpaperService() {
    override fun useVideo(): Boolean = true
}

// ---------------------------------------------------------------------------------------------
// Schwarm-Simulation (CPU)
//
// Alles wird im Bildschirm-Frame gerechnet (x rechts, y oben, z zum Betrachter, Ursprung = Torusmitte).
// Der Glastorus ist fest an das Handy gebunden. Die Flüssigkeit gehört zur Umgebung: Bei jeder Drehung
// des Handys wird der gesamte Schwarm um die inverse Drehung mitgedreht (T_INERTIA), er bleibt also im Raum
// stehen. Dreht man das Handy um die Torusachse (senkrecht zum Display), läuft der Schwarm dadurch
// "groß" um den Ring. Die Torus-Wand hält die Murmeln danach im Rohr.
//
// Zwei Strömungen überlagern sich:
//  - große Drehung (um die Torusachse): kommt aus der Handydrehung (und aus Wischen),
//  - kleine Drehung (um die Rohrachse): wird durch Bewegung senkrecht zum Display angetrieben,
//    innen (im Loch) ins Display hinein, außen zum Betrachter zurück. Wie bei einem Rauchring ist sie auf der
//    Lochseite schneller (Geschwindigkeit ~ 1 / Abstand zur Torusachse) und wird zur Rohrwand hin abgebremst.
// Dazu kommt eine schwache natürliche Bewegung und der gelegentliche Anführer.
// ---------------------------------------------------------------------------------------------

private class TorusSwarm(val n: Int) {

    // Zustand im Bildschirm-Frame (wird direkt gerendert)
    val px = FloatArray(n)
    val py = FloatArray(n)
    val pz = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val vz = FloatArray(n)
    private val ax = FloatArray(n)
    private val ay = FloatArray(n)
    private val az = FloatArray(n)

    val rad = FloatArray(n)
    val cr = FloatArray(n)
    val cg = FloatArray(n)
    val cb = FloatArray(n)
    val spin = FloatArray(n)
    val seed = FloatArray(n)
    private val phase = FloatArray(n)
    private val spinBias = FloatArray(n)
    private val trail = FloatArray(n)
    val order = IntArray(n) { it }

    var time = 0f
        private set
    var camX = 0f
        private set
    var camY = 0f
        private set

    // Rohdaten der Sensoren (Gerätekoordinaten des Sensors, nicht des Bildschirms)
    private var accRx = 0f
    private var accRy = T_GRAV
    private var accRz = 0f
    private val rotRaw = FloatArray(9)
    private var hasRotRaw = false
    private var displayRot = 0

    // Tiefpass der Beschleunigung (Bildschirm-Frame), für Kamera und Fallback
    private var gLx = 0f
    private var gLy = T_GRAV
    private var gLz = 0f

    // Drehmatrix Bildschirm -> Welt (zeilenweise), plus Vorwert
    private val rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val prevRot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val dR = FloatArray(9)
    private var rotInit = false
    private val tmp3 = FloatArray(3)

    // Strömungen
    private var omegaP = 0f   // kleine Drehung (um die Rohrachse), >0 = innen ins Display hinein
    private var omegaT = 0f   // zusätzliche große Drehung (Wischen, Scrollen), um die Torusachse

    // Touch (Bildschirm-Frame, relativ zur Torusmitte)
    private var touching = false
    private var tx = 0f
    private var ty = 0f
    private var fvx = 0f
    private var fvy = 0f
    private var lastTouchMs = 0L

    // Anführer
    private val rnd = Random(System.nanoTime())
    private var leaderIdx = -1
    private var leaderStart = 0f
    private var leaderDir = 1f
    private var leaderW = 0f
    private var nextLeaderAt = 0f

    init {
        nextLeaderAt = T_LEADER_MIN_S + (T_LEADER_MAX_S - T_LEADER_MIN_S) * rnd.nextFloat()
        for (i in 0 until n) {
            rad[i] = 0.022f + 0.026f * rnd.nextFloat().pow(1.6f)
            val lim = TorusScene.TUBE_R * 0.955f - rad[i]
            val phi = rnd.nextFloat() * T_TWO_PI
            val th = rnd.nextFloat() * T_TWO_PI
            val rq = sqrt(rnd.nextFloat()) * lim
            val rho = TorusScene.RING_R + rq * cos(th)
            px[i] = rho * cos(phi)
            py[i] = rho * sin(phi)
            pz[i] = rq * sin(th)
            val rgb = hsv(rnd.nextFloat(), 0.72f + 0.25f * rnd.nextFloat(), 0.80f + 0.20f * rnd.nextFloat())
            cr[i] = rgb[0]; cg[i] = rgb[1]; cb[i] = rgb[2]
            spin[i] = rnd.nextFloat() * T_TWO_PI
            seed[i] = rnd.nextFloat()
            phase[i] = rnd.nextFloat()
            spinBias[i] = (rnd.nextFloat() - 0.5f) * 1.2f
            trail[i] = 0.2f + 2.6f * rnd.nextFloat()   // Abstand hinter dem Anführer (rad): ein Schweif über den halben Ring
        }
        repeat(80) {
            separatePositions()
            for (i in 0 until n) wallClamp(i, false)
        }
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

    @Synchronized
    fun onScroll(deltaX: Float) {
        omegaT = (omegaT + deltaX * 6f).coerceIn(-3f, 3f)
    }

    @Synchronized
    fun onTouch(nx: Float, ny: Float, aspect: Float, action: Int, tMs: Long) {
        val sx = nx - 0.5f
        val sy = (0.5f - ny) * aspect
        // Strahl Kamera -> Bildschirmpunkt, geschnitten mit der Mittelebene des Torus
        val s = (TorusScene.CAM_Z - TorusScene.TZ) / TorusScene.CAM_Z
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
                // Wischen um die Torusmitte treibt die große Drehung an
                val dTheta = (tx * dy - ty * dx) / (tx * tx + ty * ty + 0.01f)
                omegaT = (omegaT + dTheta * 2f).coerceIn(-3f, 3f)
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

        // "oben" im Bildschirm-Frame laut Lage; Rest der Beschleunigung = Bewegung des Handys
        val upX = rot[6]
        val upY = rot[7]
        val upZ = rot[8]
        val lx = ax0 - T_GRAV * upX
        val ly = ay0 - T_GRAV * upY
        val lz = az0 - T_GRAV * upZ

        // Kamera-Parallaxe: Neigung + leichtes Eigenschweben
        camX = (gLx / T_GRAV * 0.10f).coerceIn(-0.12f, 0.12f) + 0.020f * sin(time * 0.21f)
        camY = ((gLz - 3f) / T_GRAV * 0.10f).coerceIn(-0.10f, 0.10f) + 0.015f * cos(time * 0.17f)

        // Die Flüssigkeit bleibt im Raum stehen, während sich das Handy dreht
        applyInertia()

        // Kleine Drehung: Bewegung senkrecht zum Display pumpt innen ins Display hinein
        val mag = max(abs(lz) - T_PUMP_DEADZONE, 0f)
        val push = if (T_PUMP_SIGNED) (if (lz > 0f) mag else -mag) else mag
        omegaP = (omegaP + T_PUMP_GAIN * push * dt).coerceIn(if (T_PUMP_SIGNED) -5f else 0f, 5f)

        updateLeader()

        // Beschleunigungen aus Schwerkraft (sehr schwach, Murmeln schweben fast) und Trägheit (Schwappen)
        val grav = 0.006f * T_GRAV
        val gaX = -grav * upX - 0.04f * lx
        val gaY = -grav * upY - 0.04f * ly
        val gaZ = -grav * upZ - 0.04f * lz

        val ringR = TorusScene.RING_R
        val tubeR = TorusScene.TUBE_R
        val h = dt / 2f
        repeat(2) {
            val li = leaderIdx
            val lw = leaderW
            var lPhi = 0f
            var lQx = 0f
            var lQz = 0f
            if (li >= 0 && lw > 0f) {
                lPhi = atan2(py[li], px[li])
                lQx = sqrt(px[li] * px[li] + py[li] * py[li]) - ringR
                lQz = pz[li]
            }

            for (i in 0 until n) {
                val ph = phase[i]
                val x = px[i]
                val y = py[i]
                val z = pz[i]
                val rho = max(sqrt(x * x + y * y), 1e-3f)
                val ex = x / rho
                val ey = y / rho
                val qx = rho - ringR

                // Kleine Drehung wie bei einem Rauchring: innen (qx < 0) ins Display (-z), außen zum Betrachter,
                // hinten nach außen, vorn nach innen. Auf der Lochseite schneller (~ 1 / Abstand zur Torusachse),
                // in der Rohrmitte und an der Rohrwand langsam, dazwischen am schnellsten.
                val sq = sqrt(qx * qx + z * z) / tubeR
                val prof = 2.6f * max(1f - sq * sq, 0f) * (ringR / rho)
                var fx = omegaP * prof * (-z * ex)
                var fy = omegaP * prof * (-z * ey)
                var fz = omegaP * prof * qx

                // Große Drehung durch Wischen / Scrollen: Rotation um die Torusachse
                fx += -omegaT * y
                fy += omegaT * x

                // Natürliche Bewegung: schwach und klein
                val nat = T_NATURAL_SPEED
                val drift = 0.5f * nat * sin(time * 0.07f + ph * T_TWO_PI)
                fx += -ey * drift + nat * sin(time * 0.37f + ph * 6.283f)
                fy += ex * drift + nat * cos(time * 0.31f + ph * 4.1f)
                fz += nat * sin(time * 0.23f + ph * 9.7f)

                ax[i] = gaX
                ay[i] = gaY
                az[i] = gaZ

                // Anführer zieht den Schwarm hinterher
                if (li >= 0 && lw > 0f) {
                    if (i == li) {
                        // gleitet entlang des Rings und windet sich dabei um die Rohrachse
                        val sp = leaderDir * T_LEADER_SPEED
                        val wv = 1.2f
                        val tvx = -ey * sp + wv * (-z * ex)
                        val tvy = ex * sp + wv * (-z * ey)
                        val tvz = wv * qx
                        fx += (tvx - fx) * lw
                        fy += (tvy - fy) * lw
                        fz += (tvz - fz) * lw
                    } else {
                        // zieht sich auf eine Position hinter dem Anführer (entlang des Rings)
                        var d = lPhi - leaderDir * trail[i] - atan2(y, x)
                        d -= T_TWO_PI * floor(d / T_TWO_PI + 0.5f)
                        val aT = lw * 2.0f * ringR * d.coerceIn(-1.5f, 1.5f)
                        ax[i] += -ey * aT
                        ay[i] += ex * aT
                        // und hält lose im Rohrquerschnitt zusammen
                        val kq = lw * 0.25f
                        ax[i] += ex * (lQx - qx) * kq
                        ay[i] += ey * (lQx - qx) * kq
                        az[i] += (lQz - z) * kq
                    }
                }

                // Mitnahme der Strömung (Reibung der Flüssigkeit)
                ax[i] += (fx - vx[i]) * 2.2f
                ay[i] += (fy - vy[i]) * 2.2f
                az[i] += (fz - vz[i]) * 2.2f

                // Finger auf dem Glas
                if (touching) {
                    val dx = x - tx
                    val dy = y - ty
                    val d2 = dx * dx + dy * dy
                    if (d2 < 0.12f) {
                        val w = exp(-d2 / 0.02f)
                        ax[i] += w * ((fvx - vx[i]) * 10f + dx * 40f)
                        ay[i] += w * ((fvy - vy[i]) * 10f + dy * 40f)
                    }
                }
            }

            // Abstoßung zwischen Murmeln
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = px[i] - px[j]
                    val dy = py[i] - py[j]
                    val dz = pz[i] - pz[j]
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
                if (sp2 > 1f) {
                    val s = 1f / sqrt(sp2)
                    vx[i] *= s; vy[i] *= s; vz[i] *= s
                }
                px[i] += vx[i] * h
                py[i] += vy[i] * h
                pz[i] += vz[i] * h
                wallClamp(i, true)
            }
        }

        // Eigendrehung der Murmeln
        for (i in 0 until n) {
            val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i])
            spin[i] += (sp * 12f + spinBias[i]) * dt
        }

        // Abklingen
        omegaP *= exp(-dt / T_POLOIDAL_DECAY_S)
        omegaT *= exp(-dt / 2.5f)
        val fd = exp(-dt * 8f)
        fvx *= fd; fvy *= fd

        sortByDepth()
    }

    /**
     * Dreht den gesamten Schwarm (Orte und Geschwindigkeiten) um die inverse Handydrehung dieses Frames.
     * Q = R_neu^T * R_alt bildet Bildschirmvektoren des letzten Frames auf die des aktuellen ab.
     */
    private fun applyInertia() {
        for (i in 0..2) {
            for (j in 0..2) {
                dR[3 * i + j] = rot[i] * prevRot[j] + rot[3 + i] * prevRot[3 + j] + rot[6 + i] * prevRot[6 + j]
            }
        }
        val kx = 0.5f * (dR[7] - dR[5])
        val ky = 0.5f * (dR[2] - dR[6])
        val kz = 0.5f * (dR[3] - dR[1])
        val s = sqrt(kx * kx + ky * ky + kz * kz)
        if (s < 1e-6f) return
        val angle = min(asin(min(s, 1f)), 0.5f) * T_INERTIA
        val ux = kx / s
        val uy = ky / s
        val uz = kz / s
        val c = cos(angle)
        val sn = sin(angle)
        val omc = 1f - c
        for (i in 0 until n) {
            var x = px[i]
            var y = py[i]
            var z = pz[i]
            var dot = ux * x + uy * y + uz * z
            px[i] = x * c + (uy * z - uz * y) * sn + ux * dot * omc
            py[i] = y * c + (uz * x - ux * z) * sn + uy * dot * omc
            pz[i] = z * c + (ux * y - uy * x) * sn + uz * dot * omc
            x = vx[i]
            y = vy[i]
            z = vz[i]
            dot = ux * x + uy * y + uz * z
            vx[i] = x * c + (uy * z - uz * y) * sn + ux * dot * omc
            vy[i] = y * c + (uz * x - ux * z) * sn + uy * dot * omc
            vz[i] = z * c + (ux * y - uy * x) * sn + uz * dot * omc
        }
    }

    private fun smooth01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Alle 1 bis 5 Minuten (zufällig) wird eine zufällige Murmel sanft zum Anführer, ein- und ausgeblendet. */
    private fun updateLeader() {
        if (leaderIdx < 0) {
            leaderW = 0f
            if (time >= nextLeaderAt) {
                leaderIdx = rnd.nextInt(n)
                leaderStart = time
                leaderDir = if (rnd.nextBoolean()) 1f else -1f
            }
        } else {
            val s = time - leaderStart
            if (s >= T_LEADER_DURATION_S) {
                leaderIdx = -1
                leaderW = 0f
                nextLeaderAt = time + T_LEADER_MIN_S + (T_LEADER_MAX_S - T_LEADER_MIN_S) * rnd.nextFloat()
            } else {
                val up = smooth01(s / T_LEADER_FADE_S)
                val down = smooth01((T_LEADER_DURATION_S - s) / T_LEADER_FADE_S)
                leaderW = min(up, down)
            }
        }
    }

    /** Hält Murmel i im Torusrohr: Abstand zur Rohrmitte-Kreislinie höchstens (Rohrradius - Murmelradius). */
    private fun wallClamp(i: Int, withVelocity: Boolean) {
        val x = px[i]
        val y = py[i]
        val z = pz[i]
        val rho = sqrt(x * x + y * y)
        val ex: Float
        val ey: Float
        if (rho > 1e-5f) { ex = x / rho; ey = y / rho } else { ex = 1f; ey = 0f }
        val qx = rho - TorusScene.RING_R
        val dq = sqrt(qx * qx + z * z)
        val lim = TorusScene.TUBE_R * 0.955f - rad[i]
        if (dq > lim && dq > 1e-6f) {
            val sc = lim / dq
            val nrho = TorusScene.RING_R + qx * sc
            px[i] = ex * nrho
            py[i] = ey * nrho
            pz[i] = z * sc
            if (withVelocity) {
                val nx = qx / dq * ex
                val ny = qx / dq * ey
                val nz = z / dq
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
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                val dz = pz[i] - pz[j]
                val lim = (rad[i] + rad[j]) * 1.04f
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < lim * lim) {
                    val d = max(sqrt(d2), 1e-4f)
                    val s = (lim - d) * 0.5f / d
                    px[i] += dx * s; py[i] += dy * s; pz[i] += dz * s
                    px[j] -= dx * s; py[j] -= dy * s; pz[j] -= dz * s
                }
            }
        }
    }

    /** Hinten (kleines z) zuerst – Insertion Sort, von Frame zu Frame fast sortiert. */
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

private val T_COMMON = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes;
uniform float uTime;
uniform vec2 uCam;
const float C = ${TorusScene.CAM_Z};
const float PD = ${TorusScene.PLANE_DEPTH};
const float RM = ${TorusScene.RING_R};
const float RT = ${TorusScene.TUBE_R};
const float BR = ${TorusScene.BOUND_R};
const float ETA = ${TorusScene.REL_IOR};
const vec3 TC = vec3(0.0, 0.0, ${TorusScene.TZ});
const vec3 LD = vec3(-0.398, 0.597, 0.696);

void makeRay(out vec3 ro, out vec3 rd) {
    vec2 pw = vec2(gl_FragCoord.x / uRes.x - 0.5, (gl_FragCoord.y / uRes.y - 0.5) * uRes.y / uRes.x);
    ro = vec3(uCam, C);
    rd = normalize(vec3(pw, 0.0) - ro);
}

// Abstand zur Torusoberfläche (Achse = z)
float sdTorus(vec3 p) {
    vec3 q = p - TC;
    return length(vec2(length(q.xy) - RM, q.z)) - RT;
}

vec3 nTorus(vec3 p) {
    vec3 q = p - TC;
    float rho = max(length(q.xy), 0.0001);
    vec2 d = vec2(rho - RM, q.z);
    return normalize(vec3(q.xy / rho * d.x, d.y));
}

// Sphere-Tracing in der Hüllkugel. Liefert den Trefferabstand oder -1; dmin/tmin = knappster Vorbeiflug.
float marchTorus(vec3 ro, vec3 rd, out float dmin, out float tmin) {
    dmin = 1000.0;
    tmin = 0.0;
    vec3 oc = ro - TC;
    float b = dot(oc, rd);
    float h = b * b - (dot(oc, oc) - BR * BR);
    if (h < 0.0) return -1.0;
    h = sqrt(h);
    float t = max(-b - h, 0.0);
    float tmax = -b + h;
    for (int i = 0; i < 64; i++) {
        float d = sdTorus(ro + rd * t);
        if (d < dmin) { dmin = d; tmin = t; }
        if (d < 0.0004) return t;
        t += d;
        if (t > tmax) break;
    }
    return -1.0;
}
"""

private const val T_FULLSCREEN_VS = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

/** Licht auf der Hintergrundebene: Schatten des Glasrings, gebündelter Lichtring darin, Kontaktschatten. */
private val T_LIGHT = """
vec3 torusLight(vec3 col, vec2 h) {
    float tS = (TC.z + PD) / LD.z;
    vec2 shc = TC.xy - LD.xy * tS;
    float rr = length(h - shc);
    float sd = abs(rr - RM) - RT;
    col *= 1.0 - 0.42 * smoothstep(0.10, -0.03, sd);
    float kr = (rr - RM) / (RT * 0.55);
    col += col * 0.65 * exp(-kr * kr);
    float kc = (length(h - TC.xy) - RM) / (RT * 0.5);
    col *= 1.0 - 0.5 * exp(-kc * kc);
    return col;
}
"""

/** Hintergrundebene Variante 1: große bunte Kreise mit Wellen und Kaustiken. */
private val T_PLANE_CIRCLES = """
const float CR = ${TorusScene.CIRCLE_R};
const float FOGK = 0.05;

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
    return torusLight(col, h);
}
"""

/** Hintergrundebene Variante 2: Video (externe Textur) und Standbild, weich überblendet (uVidMix 1 = Video, 0 = Standbild). */
private val T_PLANE_VIDEO = """
uniform samplerExternalOES uVideo;
uniform sampler2D uImage;
uniform float uVidMix;
uniform float uVidAspect;
uniform float uImgAspect;
uniform mat4 uST;
const float FOGK = 0.012;

// Ebenenkoordinate -> Texturkoordinate; das Bild füllt (cover) die sichtbare Ebene samt Rand für Parallaxe und Brechung
vec2 coverUv(vec2 h, float aspect) {
    float need = (C + PD) / C * 1.12;
    float needH = need * uRes.y / uRes.x;
    float pw = max(need, needH / aspect);
    return vec2(h.x / pw + 0.5, h.y / (pw * aspect) + 0.5);
}

vec3 plane(vec2 h) {
    vec3 col = vec3(0.0);
    if (uVidMix > 0.001) {
        vec2 uv = coverUv(h, uVidAspect);
        col += texture2D(uVideo, (uST * vec4(uv, 0.0, 1.0)).xy).rgb * uVidMix;
    }
    if (uVidMix < 0.999) {
        vec2 uv = coverUv(h, uImgAspect);
        col += texture2D(uImage, vec2(uv.x, 1.0 - uv.y)).rgb * (1.0 - uVidMix);
    }
    return torusLight(col, h);
}
"""

/** Pass 1 (gemeinsamer Teil): Sicht auf die Ebene, auch durch das Glas (Brechung). */
private val T_BG_MAIN = """
vec3 bgAt(vec3 o, vec3 d, float t0) {
    if (d.z > -0.03) return vec3(0.02, 0.05, 0.07);
    float t = (-PD - o.z) / d.z;
    vec3 col = plane(o.xy + d.xy * t);
    float dist = t0 + t;
    return mix(col, vec3(0.02, 0.10, 0.13), 1.0 - exp(-dist * FOGK));
}

void main() {
    vec3 ro, rd;
    makeRay(ro, rd);
    vec3 col = bgAt(ro, rd, 0.0);
    float dmin;
    float tmin;
    float t = marchTorus(ro, rd, dmin, tmin);
    if (t >= 0.0) {
        vec3 P1 = ro + rd * t;
        vec3 N1 = nTorus(P1);
        vec3 r1 = refract(rd, N1, 1.0 / ETA);
        float ti = 0.006;
        for (int i = 0; i < 40; i++) {
            float d = -sdTorus(P1 + r1 * ti);
            if (d < 0.0004) break;
            ti += d;
        }
        vec3 P2 = P1 + r1 * ti;
        vec3 N2 = nTorus(P2);
        vec3 r2 = refract(r1, -N2, ETA);
        if (dot(r2, r2) < 0.5) r2 = reflect(r1, -N2);
        col = bgAt(P2, r2, t + ti) * exp(-ti * vec3(1.6, 0.6, 0.5));
    }
    gl_FragColor = vec4(col, 1.0);
}
"""

private val T_BG_FS = T_COMMON + T_LIGHT + T_PLANE_CIRCLES + T_BG_MAIN

private val T_BG_VIDEO_FS = "#extension GL_OES_EGL_image_external : require\n" +
    T_COMMON + T_LIGHT + T_PLANE_VIDEO + T_BG_MAIN

/** Pass 3: Glashülle des Torus (Fresnel-Reflexion einer Studio-Umgebung, dunkler Rand). Premultiplied Alpha. */
private val T_SHELL_FS = T_COMMON + """
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
    float dmin;
    float tmin;
    float t = marchTorus(ro, rd, dmin, tmin);
    if (t < 0.0) {
        // knapp vorbei: weicher dunkler Rand der Silhouette
        if (dmin > 0.05) discard;
        float fp = tmin / (C * uRes.x);
        float cov = 1.0 - smoothstep(0.0, 1.5 * fp, dmin);
        if (cov <= 0.0) discard;
        gl_FragColor = vec4(0.0, 0.0, 0.0, 0.45 * cov);
        return;
    }
    vec3 N = nTorus(ro + rd * t);
    float cosT = clamp(dot(-rd, N), 0.0, 1.0);
    float F = 0.03 + 0.97 * pow(1.0 - cosT, 5.0);
    vec3 r = reflect(rd, N);
    vec3 e = env(r);
    float dark = pow(1.0 - cosT, 3.0) * 0.35;
    float alpha = clamp(F + dark, 0.0, 1.0);
    gl_FragColor = vec4(e * F, alpha);
}
"""

private const val T_MARBLE_VS = """
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
private const val T_MARBLE_FS = """
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

private class TorusRenderer(
    private val swarm: TorusSwarm,
    private val context: Context,
    private val videoMode: Boolean
) : GLSurfaceView.Renderer {

    private class Prog(val id: Int) {
        val uRes = GLES20.glGetUniformLocation(id, "uRes")
        val uTime = GLES20.glGetUniformLocation(id, "uTime")
        val uCam = GLES20.glGetUniformLocation(id, "uCam")
        val uVideo = GLES20.glGetUniformLocation(id, "uVideo")
        val uImage = GLES20.glGetUniformLocation(id, "uImage")
        val uVidMix = GLES20.glGetUniformLocation(id, "uVidMix")
        val uVidAspect = GLES20.glGetUniformLocation(id, "uVidAspect")
        val uImgAspect = GLES20.glGetUniformLocation(id, "uImgAspect")
        val uST = GLES20.glGetUniformLocation(id, "uST")
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

    // Video-Variante
    private var oesTex = 0
    private var imgTex = 0
    private var hasImage = false
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var player: MediaPlayer? = null
    private val stMatrix = FloatArray(16)
    private var vidMix = 0f
    private var vidTarget = 0f
    private val playerLock = Any()
    @Volatile private var playing = false
    private var imgAspect = 2.17f
    @Volatile private var vidAspect = 2.17f
    @Volatile private var frameAvailable = false
    @Volatile private var videoFinished = false
    @Volatile private var startRequested = false
    @Volatile private var stopRequested = false
    @Volatile private var lastStartRequestMs = 0L

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val floatsPerVertex = 12
    private val marbleBuf: FloatBuffer =
        ByteBuffer.allocateDirect(swarm.n * 6 * floatsPerVertex * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val cornerX = floatArrayOf(-1f, 1f, 1f, -1f, 1f, -1f)
    private val cornerY = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f)
    private val expand = 1.08f

    init {
        android.opengl.Matrix.setIdentityM(stMatrix, 0)
    }

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
            Log.e(T_TAG, "Shader-Fehler: $log")
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
            Log.e(T_TAG, "Link-Fehler: $log")
            throw RuntimeException("Link-Fehler: $log")
        }
        return Prog(p)
    }

    // --- Video-Steuerung (von beliebigen Threads aufrufbar, ausgeführt im GL-Thread) -------------------

    /** Spielt das Video von vorn ab (bei Sichtbarwerden bzw. nach dem Entsperren). */
    fun requestVideoStart() {
        if (!videoMode) return
        val now = System.currentTimeMillis()
        if (now - lastStartRequestMs < T_VIDEO_DEBOUNCE_MS) return
        lastStartRequestMs = now
        stopRequested = false
        startRequested = true
    }

    /** Beendet das Video und gibt den Decoder sofort frei (Wallpaper nicht mehr sichtbar). */
    fun requestVideoStop() {
        if (!videoMode) return
        startRequested = false
        stopRequested = true
        releasePlayer()
    }

    /** Gibt Player, Oberfläche und Textur-Quelle frei (Wallpaper wird beendet). */
    fun shutdown() {
        if (!videoMode) return
        startRequested = false
        stopRequested = false
        releaseVideoResources()
    }

    private fun releasePlayer() {
        val mp = synchronized(playerLock) {
            val current = player
            player = null
            current
        }
        if (mp != null) {
            try {
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                mp.setOnPreparedListener(null)
                mp.setOnVideoSizeChangedListener(null)
                mp.release()
            } catch (e: Exception) {
                Log.w(T_TAG, "Player-Freigabe fehlgeschlagen", e)
            }
        }
        playing = false
    }

    private fun startVideo() {
        releasePlayer()
        val st = surfaceTexture
        val surface = videoSurface
        if (st == null || surface == null) return
        val resId = context.resources.getIdentifier(T_VIDEO_RES, "raw", context.packageName)
        if (resId == 0) {
            Log.w(T_TAG, "Video res/raw/$T_VIDEO_RES.* nicht gefunden")
            return
        }
        try {
            val mp = MediaPlayer()
            val afd = context.resources.openRawResourceFd(resId)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setSurface(surface)
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) vidAspect = h.toFloat() / w.toFloat() }
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { videoFinished = true }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(T_TAG, "Video-Fehler what=$what extra=$extra")
                videoFinished = true
                true
            }
            videoFinished = false
            frameAvailable = false
            playing = true
            synchronized(playerLock) { player = mp }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(T_TAG, "Video konnte nicht gestartet werden", e)
            releasePlayer()
        }
    }

    /** Pro Frame im GL-Thread: Start/Stopp abarbeiten, neues Videobild holen, Überblendung fortschreiben. */
    private fun updateVideo(dt: Float) {
        if (stopRequested) {
            stopRequested = false
            releasePlayer()
            if (hasImage) {
                // sofort zurück zum Standbild, damit beim nächsten Einblenden kein altes Videobild aufblitzt
                vidMix = 0f
                vidTarget = 0f
            }
        }
        if (startRequested) {
            startRequested = false
            startVideo()
            if (hasImage) {
                vidMix = 0f
                vidTarget = 0f
            }
        }
        val st = surfaceTexture
        if (st != null && frameAvailable) {
            frameAvailable = false
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                if (playing && !videoFinished) vidTarget = 1f
            } catch (e: Exception) {
                Log.w(T_TAG, "updateTexImage fehlgeschlagen", e)
            }
        }
        if (playing && videoFinished) {
            // Video ist zu Ende: Decoder freigeben, das Standbild (= letztes Bild) übernimmt
            releasePlayer()
            vidTarget = if (hasImage) 0f else 1f
        }
        val step = dt / T_VIDEO_FADE_S
        vidMix += (vidTarget - vidMix).coerceIn(-step, step)
    }

    private fun createVideoTextures() {
        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        oesTex = t[0]
        imgTex = t[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener { frameAvailable = true }
        surfaceTexture = st
        videoSurface = Surface(st)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        hasImage = false
        val imgId = context.resources.getIdentifier(T_IMAGE_RES, "drawable", context.packageName)
        if (imgId == 0) {
            Log.w(T_TAG, "Standbild res/drawable/$T_IMAGE_RES.* nicht gefunden")
        } else {
            val opts = BitmapFactory.Options()
            opts.inScaled = false
            var bmp: Bitmap? = BitmapFactory.decodeResource(context.resources, imgId, opts)
            if (bmp != null) {
                val maxTex = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
                val big = max(bmp.width, bmp.height)
                if (maxTex[0] > 0 && big > maxTex[0]) {
                    val f = maxTex[0].toFloat() / big.toFloat()
                    val scaled = Bitmap.createScaledBitmap(
                        bmp, max((bmp.width * f).toInt(), 1), max((bmp.height * f).toInt(), 1), true
                    )
                    bmp.recycle()
                    bmp = scaled
                }
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                imgAspect = bmp.height.toFloat() / bmp.width.toFloat()
                bmp.recycle()
                hasImage = true
            }
        }
        // Ohne Video zeigt das Wallpaper sofort das Standbild
        vidMix = 0f
        vidTarget = 0f
    }

    private fun releaseVideoResources() {
        releasePlayer()
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    // --- GLSurfaceView.Renderer ----------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        releaseVideoResources()
        bgProg = link(T_FULLSCREEN_VS, if (videoMode) T_BG_VIDEO_FS else T_BG_FS)
        shellProg = link(T_FULLSCREEN_VS, T_SHELL_FS)
        marbleProg = link(T_MARBLE_VS, T_MARBLE_FS)
        if (videoMode) createVideoTextures()
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
        if (videoMode) updateVideo(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glDisable(GLES20.GL_BLEND)
        drawFullscreen(bgProg!!, true)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawMarbles(marbleProg!!)
        drawFullscreen(shellProg!!, false)
    }

    private fun drawFullscreen(p: Prog, background: Boolean) {
        GLES20.glUseProgram(p.id)
        GLES20.glUniform2f(p.uRes, resX, resY)
        GLES20.glUniform1f(p.uTime, swarm.time)
        GLES20.glUniform2f(p.uCam, swarm.camX, swarm.camY)
        if (background && videoMode) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniform1i(p.uVideo, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgTex)
            GLES20.glUniform1i(p.uImage, 1)
            GLES20.glUniform1f(p.uVidMix, vidMix)
            GLES20.glUniform1f(p.uVidAspect, vidAspect)
            GLES20.glUniform1f(p.uImgAspect, imgAspect)
            GLES20.glUniformMatrix4fv(p.uST, 1, false, stMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
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
        val c = TorusScene.CAM_Z
        val buf = marbleBuf
        buf.clear()
        for (k in 0 until swarm.n) {
            val i = swarm.order[k]
            val zw = TorusScene.TZ + swarm.pz[i]
            val t = c / (c - zw)                       // Perspektive auf die Bildschirmebene
            val sx = camX + (swarm.px[i] - camX) * t
            val sy = camY + (swarm.py[i] - camY) * t
            val rs = swarm.rad[i] * t
            val ndcX = 2f * sx
            val ndcY = 2f * sy / aspect
            val rx = 2f * rs
            val ry = 2f * rs / aspect
            val depth = (swarm.pz[i] / TorusScene.TUBE_R) * 0.5f + 0.5f
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
