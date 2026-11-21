// The old Camera API runs on every Android version and hands over NV21 frames whose first
// plane is exactly the luminance ZXing reads.
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.synaelle.microqr

import android.Manifest.permission.CAMERA
import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.HapticFeedbackConstants.LONG_PRESS
import android.view.TextureView
import android.view.View
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity(), TextureView.SurfaceTextureListener, Camera.PreviewCallback {
    private val worker = HandlerThread("camera").apply { start() }
    private val handler = Handler(worker.looper)
    private val reader = QRCodeReader()
    private val link = Regex("[a-zA-Z][a-zA-Z0-9+.-]*:\\S+")
    private lateinit var preview: TextureView
    private lateinit var denied: View
    private lateinit var panel: View
    private lateinit var text: TextView
    private lateinit var open: View
    private var camera: Camera? = null
    private var buffer = ByteArray(0)
    private var width = 0
    private var height = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.main)
        preview = findViewById(R.id.preview)
        denied = findViewById(R.id.denied)
        panel = findViewById(R.id.panel)
        text = findViewById(R.id.text)
        open = findViewById(R.id.open)
        preview.surfaceTextureListener = this
        panel.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingTop + insets.systemWindowInsetBottom)
            insets
        }
        denied.setOnClickListener { if (granted()) start() else ask() }
        open.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text.text.toString())))
            } catch (_: ActivityNotFoundException) {
                // nothing on the phone handles this scheme
            }
        }
        findViewById<View>(R.id.copy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(null, text.text))
            it.performHapticFeedback(LONG_PRESS)
        }
        findViewById<View>(R.id.close).setOnClickListener {
            panel.visibility = View.GONE
            camera?.addCallbackBuffer(buffer)
        }
        state?.getString("text")?.let(::show)
    }

    override fun onResume() {
        super.onResume()
        if (granted()) start() else if (denied.visibility != View.VISIBLE) requestPermissions(arrayOf(CAMERA), 0)
    }

    override fun onPause() {
        super.onPause()
        handler.post {
            camera?.release()
            camera = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.quitSafely()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (panel.visibility == View.VISIBLE) out.putString("text", text.text.toString())
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        if (results.firstOrNull() != PERMISSION_GRANTED) denied.visibility = View.VISIBLE
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
        if (granted()) start()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    override fun onPreviewFrame(data: ByteArray, cam: Camera) {
        val result = try {
            reader.decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false))))
        } catch (_: ReaderException) {
            null
        }
        if (result == null) cam.addCallbackBuffer(data) else runOnUiThread { show(result.text) }
    }

    private fun granted() = Build.VERSION.SDK_INT < 23 || checkSelfPermission(CAMERA) == PERMISSION_GRANTED

    // the denied icon only shows on Android 6 and later, where the camera is asked for at run time
    @TargetApi(23)
    private fun ask() {
        if (shouldShowRequestPermissionRationale(CAMERA)) requestPermissions(arrayOf(CAMERA), 0)
        else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun start() {
        denied.visibility = View.GONE
        val texture = preview.surfaceTexture ?: return
        val scanning = panel.visibility != View.VISIBLE
        val rotation = windowManager.defaultDisplay.rotation * 90
        handler.post { if (camera == null) open(texture, rotation, scanning) }
    }

    // runs on the worker, so frames arrive and are decoded there
    private fun open(texture: SurfaceTexture, rotation: Int, scanning: Boolean) {
        val infos = (0 until Camera.getNumberOfCameras()).map { Camera.CameraInfo().apply { Camera.getCameraInfo(it, this) } }
        // CAMERA_FACING_BACK is 0, so the first back camera wins and a front one is the fallback
        val id = infos.indices.minByOrNull { infos[it].facing } ?: return
        val info = infos[id]
        val turn = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - (info.orientation + rotation) % 360) % 360
        else (info.orientation - rotation + 360) % 360
        try {
            val cam = Camera.open(id).also { camera = it }
            val params = cam.parameters
            val size = params.supportedPreviewSizes.minBy { abs(it.width * it.height - 1280 * 720) }
            params.setPreviewSize(size.width, size.height)
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in params.supportedFocusModes) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            cam.parameters = params
            cam.setDisplayOrientation(turn)
            cam.setPreviewTexture(texture)
            width = size.width
            height = size.height
            buffer = ByteArray(width * height * 3 / 2)
            cam.setPreviewCallbackWithBuffer(this)
            if (scanning) cam.addCallbackBuffer(buffer)
            cam.startPreview()
            runOnUiThread { if (turn % 180 == 0) crop(width, height) else crop(height, width) }
        } catch (_: IOException) {
            unavailable()
        } catch (_: RuntimeException) {
            unavailable()
        }
    }

    // another app holds the camera or it would not start, so the icon offers to try again
    private fun unavailable() {
        camera?.release()
        camera = null
        runOnUiThread { denied.visibility = View.VISIBLE }
    }

    // TextureView stretches the frame to the view, so scale it back up to fill without distortion
    private fun crop(w: Int, h: Int) {
        val vw = preview.width.toFloat()
        val vh = preview.height.toFloat()
        val scale = max(vw / w, vh / h)
        preview.setTransform(Matrix().apply { setScale(w * scale / vw, h * scale / vh, vw / 2, vh / 2) })
    }

    private fun show(value: String) {
        text.text = value
        open.visibility = if (link.matches(value)) View.VISIBLE else View.GONE
        panel.visibility = View.VISIBLE
        panel.performHapticFeedback(LONG_PRESS)
    }
}
