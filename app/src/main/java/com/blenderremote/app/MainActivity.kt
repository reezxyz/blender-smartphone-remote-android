package com.blenderremote.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private companion object {
        const val TAG = "BlenderRemote"
        const val PREFS = "blender_remote"
        const val PREF_ADDRESS = "address"
        const val GREEN = 0xFF4CD964.toInt()
        const val AMBER = 0xFFFFB300.toInt()
        const val RED = 0xFFFF5252.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val METERS_PER_PIXEL = 0.0025f
    }

    // Views
    private lateinit var glView: GLSurfaceView
    private lateinit var tvConnection: TextView
    private lateinit var tvTracking: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvStream: TextView
    private lateinit var tvRecord: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnSync: MaterialButton
    private lateinit var btnMode: MaterialButton
    private lateinit var btnRecord: MaterialButton
    private lateinit var btnAdjust: MaterialButton
    private lateinit var btnLock: MaterialButton
    private lateinit var btnStep: MaterialButton
    private lateinit var adjustPanel: View
    private lateinit var ivReticle: View
    private lateinit var seekYaw: SeekBar
    private lateinit var tvYaw: TextView
    private lateinit var tvScale: TextView

    // AR (session and anchor are touched by the GL thread, except resume/pause)
    private var session: Session? = null
    private var installRequested = false
    private val background = BackgroundRenderer()
    private val modelRenderer = ModelRenderer()
    private var anchor: Anchor? = null
    private var viewWidth = 1
    private var viewHeight = 1

    @Volatile
    private var viewportChanged = true

    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)
    private val modelM = FloatArray(16)
    private val poseM = FloatArray(16)

    // State shared between threads
    private val pendingTap = AtomicReference<FloatArray?>(null)

    @Volatile private var scaleFactor = 1f
    // manual adjustment of the origin (metres in AR world space, degrees around the vertical axis)
    @Volatile private var offX = 0f
    @Volatile private var offY = 0f
    @Volatile private var offZ = 0f
    @Volatile private var yawDeg = 0f
    // horizontal direction the phone looks at, published by the GL thread
    @Volatile private var camFx = 0f
    @Volatile private var camFz = -1f
    @Volatile private var gesturesLocked = false
    @Volatile private var adjustOpen = false
    private var stepCm = 5
    private val moveHere = AtomicBoolean(false)

    // touch state (UI thread only)
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var prevAngle = Float.NaN
    private var touchSlop = 16f
    @Volatile private var hasModel = false
    @Volatile private var anchorPlaced = false
    @Volatile private var streamEnabled = true
    @Volatile private var mode = Protocol.MODE_CAMERA
    @Volatile private var trackingText = "Starting..."
    @Volatile private var modelStatus = "No model yet"
    @Volatile private var downloading = false
    @Volatile private var link: BlenderLink? = null
    @Volatile private var recorder: RecordClient? = null
    @Volatile private var recordFrame = 0

    private var autoSyncDone = false
    private var settleFrames = 0
    private var lastSentFrames = 0L
    private var lastFpsTime = 0L
    private var fps = 0
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startSession()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            viewportChanged = true
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 300)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.surfaceView)
        tvConnection = findViewById(R.id.tvConnection)
        tvTracking = findViewById(R.id.tvTracking)
        tvModel = findViewById(R.id.tvModel)
        tvStream = findViewById(R.id.tvStream)
        tvRecord = findViewById(R.id.tvRecord)
        tvHint = findViewById(R.id.tvHint)
        btnConnect = findViewById(R.id.btnConnect)
        btnSync = findViewById(R.id.btnSync)
        btnMode = findViewById(R.id.btnMode)
        btnRecord = findViewById(R.id.btnRecord)
        btnAdjust = findViewById(R.id.btnAdjust)
        btnLock = findViewById(R.id.btnLock)
        btnStep = findViewById(R.id.btnStep)
        adjustPanel = findViewById(R.id.adjustPanel)
        ivReticle = findViewById(R.id.ivReticle)
        seekYaw = findViewById(R.id.seekYaw)
        tvYaw = findViewById(R.id.tvYaw)
        tvScale = findViewById(R.id.tvScale)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        glView.setPreserveEGLContextOnPause(true)
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.setRenderer(this)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.setWillNotDraw(false)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!gesturesLocked) setScale(scaleFactor * detector.scaleFactor)
                return true
            }
        })
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (gesturesLocked) return true
                // first placement: the ring in the middle of the screen is the target
                if (!anchorPlaced) {
                    pendingTap.set(floatArrayOf(viewWidth / 2f, viewHeight / 2f))
                } else {
                    pendingTap.set(floatArrayOf(e.x, e.y))
                }
                return true
            }
        })
        glView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        btnConnect.setOnClickListener {
            if (link == null) showConnectDialog() else disconnect()
        }
        btnSync.setOnClickListener { syncModel() }
        btnMode.setOnClickListener {
            mode = if (mode == Protocol.MODE_CAMERA) Protocol.MODE_OBJECT else Protocol.MODE_CAMERA
            refreshUi()
        }
        tvStream.setOnClickListener {
            streamEnabled = !streamEnabled
            refreshUi()
        }
        btnRecord.setOnClickListener { toggleRecording() }

        // ---- adjust panel
        btnAdjust.setOnClickListener {
            adjustOpen = !adjustOpen
            refreshUi()
        }
        btnLock.setOnClickListener {
            gesturesLocked = !gesturesLocked
            refreshUi()
        }
        btnStep.setOnClickListener {
            stepCm = when (stepCm) {
                1 -> 5
                5 -> 20
                else -> 1
            }
            refreshUi()
        }
        holdRepeat(findViewById(R.id.btnLeft)) { nudge(-stepCm / 100f, 0f, 0f) }
        holdRepeat(findViewById(R.id.btnRight)) { nudge(stepCm / 100f, 0f, 0f) }
        holdRepeat(findViewById(R.id.btnFwd)) { nudge(0f, stepCm / 100f, 0f) }
        holdRepeat(findViewById(R.id.btnBack)) { nudge(0f, -stepCm / 100f, 0f) }
        holdRepeat(findViewById(R.id.btnUp)) { nudge(0f, 0f, stepCm / 100f) }
        holdRepeat(findViewById(R.id.btnDown)) { nudge(0f, 0f, -stepCm / 100f) }
        holdRepeat(findViewById(R.id.btnScaleMinus)) { setScale(scaleFactor / 1.03f) }
        holdRepeat(findViewById(R.id.btnScalePlus)) { setScale(scaleFactor * 1.03f) }
        seekYaw.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setYaw(progress - 180f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        findViewById<View>(R.id.btnHere).setOnClickListener { moveHere.set(true) }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            offX = 0f
            offY = 0f
            offZ = 0f
            yawDeg = 0f
            scaleFactor = 1f
            refreshUi()
        }
        refreshUi()
    }

    // ------------------------------------------------------------------ adjustment helpers

    private fun setScale(v: Float) {
        scaleFactor = v.coerceIn(0.01f, 100f)
    }

    private fun setYaw(v: Float) {
        var y = v
        while (y > 180f) y -= 360f
        while (y < -180f) y += 360f
        yawDeg = y
    }

    /** Moves the origin relative to where the phone looks: right / forward / up, in metres. */
    private fun nudge(right: Float, forward: Float, up: Float) {
        val fx = camFx
        val fz = camFz
        offX += -fz * right + fx * forward
        offZ += fx * right + fz * forward
        offY += up
    }

    private fun holdRepeat(b: View, action: () -> Unit) {
        val again = object : Runnable {
            override fun run() {
                action()
                ui.postDelayed(this, 70)
            }
        }
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    action()
                    ui.postDelayed(again, 350)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    ui.removeCallbacks(again)
                }
            }
            true
        }
    }

    private fun angleOf(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private fun handleTouch(e: MotionEvent) {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                dragging = false
                prevAngle = Float.NaN
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = false
                if (e.pointerCount >= 2) prevAngle = angleOf(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                prevAngle = Float.NaN
                dragging = false
                val idx = if (e.actionIndex == 0) 1 else 0
                if (idx < e.pointerCount) {
                    downX = e.getX(idx)
                    downY = e.getY(idx)
                    lastX = downX
                    lastY = downY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val a = angleOf(e)
                    if (!prevAngle.isNaN() && !gesturesLocked) {
                        var d = a - prevAngle
                        if (d > 180f) d -= 360f else if (d < -180f) d += 360f
                        // clockwise on screen = clockwise seen from above
                        setYaw(yawDeg - d)
                    }
                    prevAngle = a
                } else if (adjustOpen && !gesturesLocked) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > touchSlop) {
                        dragging = true
                        lastX = e.x
                        lastY = e.y
                    }
                    if (dragging) {
                        nudge((e.x - lastX) * METERS_PER_PIXEL, -(e.y - lastY) * METERS_PER_PIXEL, 0f)
                        lastX = e.x
                        lastY = e.y
                    }
                }
            }
        }
        if (!scaleDetector.isInProgress && e.pointerCount == 1) tapDetector.onTouchEvent(e)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startSession()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        dm.unregisterDisplayListener(displayListener)
        if (session != null) {
            glView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        recorder?.requestStop()
        link?.stop()
        link = null
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ AR session

    private fun startSession() {
        if (session == null) {
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    else -> {}
                }
                val s = Session(this)
                val config = Config(s)
                config.setFocusMode(Config.FocusMode.AUTO)
                config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
                s.configure(config)
                session = s
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
            } catch (e: Exception) {
                Log.e(TAG, "session", e)
                message = "Could not start AR: " + e.javaClass.simpleName
            }
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                trackingText = message
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera not available. Restart the app.", Toast.LENGTH_LONG).show()
            session = null
            return
        }
        glView.onResume()
        viewportChanged = true
    }

    // ------------------------------------------------------------------ GL renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        try {
            background.create()
            modelRenderer.create()
        } catch (e: Exception) {
            Log.e(TAG, "GL setup failed", e)
            trackingText = "Graphics error: " + e.message
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        try {
            if (viewportChanged) {
                @Suppress("DEPRECATION")
                val rotation = windowManager.defaultDisplay.rotation
                s.setDisplayGeometry(rotation, viewWidth, viewHeight)
                viewportChanged = false
            }
            s.setCameraTextureName(background.textureId)
            val frame = s.update()
            val camera = frame.camera
            background.draw(frame)

            if (camera.trackingState != TrackingState.TRACKING) {
                trackingText = "Lost: " + camera.trackingFailureReason.name.lowercase().replace('_', ' ')
                settleFrames = 8
                return
            }
            trackingText = "Tracking"

            val dp = camera.displayOrientedPose
            val zAxis = dp.zAxis
            var fx = -zAxis[0]
            var fz = -zAxis[2]
            var len = sqrt(fx * fx + fz * fz)
            if (len < 0.3f) {
                // looking straight down/up: the top of the screen is "forward"
                val yAxis = dp.yAxis
                fx = yAxis[0]
                fz = yAxis[2]
                len = sqrt(fx * fx + fz * fz)
            }
            if (len > 0.001f) {
                camFx = fx / len
                camFz = fz / len
            }

            handleTap(s, frame, camera)
            if (moveHere.getAndSet(false)) {
                val cp = camera.pose
                val a0 = anchor
                if (a0 == null) {
                    anchor = s.createAnchor(Pose.makeTranslation(cp.tx(), cp.ty(), cp.tz()))
                    anchorPlaced = true
                    offX = 0f
                    offY = 0f
                    offZ = 0f
                } else {
                    // keep the height, put the origin under the phone
                    offX = cp.tx() - a0.pose.tx()
                    offZ = cp.tz() - a0.pose.tz()
                }
            }

            camera.getProjectionMatrix(projM, 0, 0.05f, 500f)
            camera.getViewMatrix(viewM, 0)

            val a = anchor
            if (a != null && a.trackingState == TrackingState.STOPPED) {
                a.detach()
                anchor = null
                anchorPlaced = false
            }
            buildModelMatrix(modelM, anchor, scaleFactor)
            if (hasModel && anchor != null) {
                modelRenderer.draw(viewM, projM, modelM)
            }
            stream(camera, frame.timestamp)
        } catch (t: Throwable) {
            Log.e(TAG, "draw", t)
        }
    }

    /** Model matrix = translate(anchor + offset) * rotateY(180 + yaw) * scale. */
    private fun buildModelMatrix(out: FloatArray, a: Anchor?, scale: Float) {
        Matrix.setIdentityM(out, 0)
        var tx = offX
        var ty = offY
        var tz = offZ
        if (a != null) {
            val p = a.pose
            tx += p.tx()
            ty += p.ty()
            tz += p.tz()
        }
        Matrix.translateM(out, 0, tx, ty, tz)
        Matrix.rotateM(out, 0, 180f + yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(out, 0, scale, scale, scale)
    }

    private fun handleTap(s: Session, frame: com.google.ar.core.Frame, camera: Camera) {
        val tap = pendingTap.getAndSet(null) ?: return
        var placed: Anchor? = null
        if (hasModel) {
            for (hit in frame.hitTest(tap[0], tap[1])) {
                val t = hit.trackable
                if (t is Plane && t.isPoseInPolygon(hit.hitPose)) {
                    // free anchor: it does not follow plane merges / refinements
                    placed = s.createAnchor(hit.hitPose)
                    break
                }
            }
            if (placed == null) {
                // no surface found: put the model 1.2 m in front of the camera
                placed = s.createAnchor(camera.pose.compose(Pose.makeTranslation(0f, 0f, -1.2f)))
            }
        } else {
            // tracking only: the current phone position becomes the origin
            val p = camera.pose
            placed = s.createAnchor(Pose.makeTranslation(p.tx(), p.ty(), p.tz()))
        }
        anchor?.detach()
        anchor = placed
        anchorPlaced = true
        offX = 0f
        offY = 0f
        offZ = 0f
    }

    private fun stream(camera: Camera, timestampNs: Long) {
        val l = link ?: return
        if (!l.online || !streamEnabled) return
        if (hasModel && anchor == null) return
        // right after tracking comes back ARCore may still be correcting its pose
        if (settleFrames > 0) {
            settleFrames--
            return
        }

        camera.displayOrientedPose.toMatrix(poseM, 0)
        // Field of view from the projection matrix, i.e. what the screen really shows.
        val fovX = 2f * atan(1f / projM[0])
        val fovY = 2f * atan(1f / projM[5])
        val landscape = viewWidth >= viewHeight
        val fovLong = if (landscape) fovX else fovY
        val fovShort = if (landscape) fovY else fovX
        l.submit(Protocol.frame(mode, modelM, fovLong, fovShort, poseM, timestampNs))
    }

    // ------------------------------------------------------------------ network actions

    private fun showConnectDialog() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val input = EditText(this)
        input.setText(prefs.getString(PREF_ADDRESS, ""))
        input.hint = "192.168.1.5:" + Protocol.DEFAULT_PORT
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Blender address")
            .setMessage("IP address of the computer running Blender, optionally with :port (default " +
                    Protocol.DEFAULT_PORT + ").")
            .setView(box)
            .setPositiveButton("Connect") { _, _ -> connect(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connect(address: String) {
        if (address.isEmpty()) return
        val parts = address.split(":")
        val host = parts[0].trim()
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: Protocol.DEFAULT_PORT
        if (host.isEmpty()) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_ADDRESS, address).apply()

        link?.stop()
        autoSyncDone = false
        val l = BlenderLink(host, port) { online, _ ->
            if (online) ui.post { onBlenderOnline() }
        }
        link = l
        l.start()
        refreshUi()
    }

    private fun disconnect() {
        recorder?.requestStop()
        link?.stop()
        link = null
        autoSyncDone = false
        refreshUi()
    }

    private fun onBlenderOnline() {
        if (!autoSyncDone && link != null) {
            autoSyncDone = true
            syncModel()
        }
    }

    private fun syncModel() {
        val l = link
        if (l == null || !l.online) {
            Toast.makeText(this, "Connect to Blender first", Toast.LENGTH_SHORT).show()
            return
        }
        if (downloading) return
        downloading = true
        val host = l.host
        val port = l.port
        val dest = File(filesDir, "scene_cache.obj")
        Thread({
            try {
                modelStatus = "Asking Blender..."
                val size = SceneClient.download(host, port, dest, AtomicBoolean(false)) { received, waited ->
                    modelStatus = if (received == 0L) {
                        "Blender is exporting... " + (waited / 1000) + " s"
                    } else {
                        "Downloading " + (received / 1024) + " KB"
                    }
                }
                if (size == 0L) {
                    hasModel = false
                    modelStatus = "No model (tracking only)"
                } else {
                    modelStatus = "Loading the model..."
                    val mesh = ObjLoader.load(dest)
                    modelRenderer.setMesh(mesh)
                    hasModel = true
                    modelStatus = "Ready, " + mesh.triangles + " triangles"
                }
            } catch (e: OutOfMemoryError) {
                modelStatus = "Model too large for this phone. Lower the triangle limit in Blender."
            } catch (e: Exception) {
                Log.e(TAG, "sync", e)
                modelStatus = "Failed: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                downloading = false
            }
        }, "scene-sync").start()
    }

    private fun toggleRecording() {
        val r = recorder
        if (r != null) {
            r.requestStop()
            return
        }
        val l = link
        if (l == null || !l.online) {
            Toast.makeText(this, "Connect to Blender first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!streamEnabled) {
            Toast.makeText(this, "Turn the stream on first", Toast.LENGTH_SHORT).show()
            return
        }
        recordFrame = 0
        val client = RecordClient(l.host, l.port, object : RecordClient.Listener {
            override fun onStarted() {
                ui.post { tvRecord.visibility = View.VISIBLE }
            }

            override fun onFrame(frame: Int) {
                recordFrame = frame
            }

            override fun onFinished(message: String?) {
                ui.post {
                    recorder = null
                    tvRecord.visibility = View.GONE
                    if (message != null) Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    refreshUi()
                }
            }
        })
        recorder = client
        client.start()
        refreshUi()
    }

    // ------------------------------------------------------------------ UI

    private fun refreshUi() {
        val l = link
        val online = l != null && l.online

        if (l == null) {
            tvConnection.text = "Not connected"
            tvConnection.setTextColor(RED)
        } else if (l.online) {
            tvConnection.text = "Connected  " + l.rttMs + " ms"
            tvConnection.setTextColor(GREEN)
        } else {
            tvConnection.text = "Waiting for Blender..."
            tvConnection.setTextColor(AMBER)
        }

        tvTracking.text = "AR: " + trackingText
        tvTracking.setTextColor(if (trackingText == "Tracking") GREEN else AMBER)
        tvModel.text = "Model: " + modelStatus
        tvModel.setTextColor(if (modelStatus.startsWith("Failed") || modelStatus.startsWith("Model too")) RED else WHITE)

        val now = System.currentTimeMillis()
        val sent = l?.sentFrames ?: 0L
        if (now - lastFpsTime >= 1000) {
            fps = if (lastFpsTime == 0L || sent < lastSentFrames) 0 else ((sent - lastSentFrames) * 1000 / (now - lastFpsTime)).toInt()
            lastSentFrames = sent
            lastFpsTime = now
        }
        if (!streamEnabled) {
            tvStream.text = "Stream: paused (tap to resume)"
            tvStream.setTextColor(AMBER)
        } else if (!online) {
            tvStream.text = "Stream: -"
            tvStream.setTextColor(WHITE)
        } else {
            tvStream.text = "Stream: " + fps + " fps"
            tvStream.setTextColor(if (fps > 0) GREEN else AMBER)
        }

        btnConnect.text = if (l == null) "Connect" else "Disconnect"
        btnMode.text = if (mode == Protocol.MODE_CAMERA) "Camera" else "Object"
        btnRecord.text = if (recorder == null) "REC" else "STOP"
        btnAdjust.text = if (adjustOpen) "Close" else "Adjust"
        adjustPanel.visibility = if (adjustOpen) View.VISIBLE else View.GONE
        btnLock.text = if (gesturesLocked) "Unlock gestures" else "Lock gestures"
        btnStep.text = "Step " + stepCm + " cm"
        tvYaw.text = yawDeg.toInt().toString() + "\u00B0"
        val wantProgress = (yawDeg + 180f).toInt()
        if (seekYaw.progress != wantProgress) seekYaw.progress = wantProgress
        tvScale.text = String.format(Locale.US, "x%.2f", scaleFactor)
        tvRecord.text = "REC  frame " + recordFrame

        val hint = when {
            session == null -> ""
            l == null -> "Tap Connect and enter the address of Blender"
            adjustOpen -> ""
            hasModel && !anchorPlaced -> "Aim the ring at the floor and tap to place the model"
            !hasModel && !anchorPlaced && online && !downloading -> "Tap to set the origin at the phone position (optional)"
            else -> ""
        }
        ivReticle.visibility = if (session != null && !adjustOpen && hasModel && !anchorPlaced) View.VISIBLE else View.GONE
        tvHint.text = hint
        tvHint.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
    }
}
