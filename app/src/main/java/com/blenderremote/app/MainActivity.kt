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
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private companion object {
        const val TAG = "BlenderRemote"
        const val PREFS = "blender_remote"
        const val PREF_ADDRESS = "address"
        const val GREEN = 0xFF4CD964.toInt()
        const val AMBER = 0xFFFFB300.toInt()
        const val RED = 0xFFFF5252.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
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
    private lateinit var btnStream: MaterialButton
    private lateinit var btnRecord: MaterialButton

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
        btnStream = findViewById(R.id.btnStream)
        btnRecord = findViewById(R.id.btnRecord)

        glView.setPreserveEGLContextOnPause(true)
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.setRenderer(this)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.setWillNotDraw(false)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.01f, 100f)
                return true
            }
        })
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                pendingTap.set(floatArrayOf(e.x, e.y))
                return true
            }
        })
        glView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
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
        btnStream.setOnClickListener {
            streamEnabled = !streamEnabled
            refreshUi()
        }
        btnRecord.setOnClickListener { toggleRecording() }
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
                return
            }
            trackingText = "Tracking"

            handleTap(s, frame, camera)

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
            stream(camera)
        } catch (t: Throwable) {
            Log.e(TAG, "draw", t)
        }
    }

    /** Model matrix = translate(anchor) * rotateY(180) * scale  (same convention as the old app). */
    private fun buildModelMatrix(out: FloatArray, a: Anchor?, scale: Float) {
        Matrix.setIdentityM(out, 0)
        if (a != null) {
            val p = a.pose
            Matrix.translateM(out, 0, p.tx(), p.ty(), p.tz())
        }
        Matrix.rotateM(out, 0, 180f, 0f, 1f, 0f)
        Matrix.scaleM(out, 0, scale, scale, scale)
    }

    private fun handleTap(s: Session, frame: com.google.ar.core.Frame, camera: Camera) {
        val tap = pendingTap.getAndSet(null) ?: return
        var placed: Anchor? = null
        if (hasModel) {
            for (hit in frame.hitTest(tap[0], tap[1])) {
                val t = hit.trackable
                if (t is Plane && t.isPoseInPolygon(hit.hitPose)) {
                    placed = hit.createAnchor()
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
    }

    private fun stream(camera: Camera) {
        val l = link ?: return
        if (!l.online || !streamEnabled) return
        if (hasModel && anchor == null) return

        camera.displayOrientedPose.toMatrix(poseM, 0)
        // Field of view from the projection matrix, i.e. what the screen really shows.
        val fovX = 2f * atan(1f / projM[0])
        val fovY = 2f * atan(1f / projM[5])
        val landscape = viewWidth >= viewHeight
        val fovLong = if (landscape) fovX else fovY
        val fovShort = if (landscape) fovY else fovX
        l.submit(Protocol.frame(mode, modelM, fovLong, fovShort, poseM))
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
            tvStream.text = "Stream: paused"
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
        btnStream.text = if (streamEnabled) "Stream on" else "Stream off"
        btnRecord.text = if (recorder == null) "Record" else "Stop"
        tvRecord.text = "REC  frame " + recordFrame

        val hint = when {
            session == null -> ""
            l == null -> "Tap Connect and enter the address of Blender"
            hasModel && !anchorPlaced -> "Move the phone slowly, then tap to place the model"
            !hasModel && !anchorPlaced && online && !downloading -> "Tap to set the origin here (optional)"
            else -> ""
        }
        tvHint.text = hint
        tvHint.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
    }
}
