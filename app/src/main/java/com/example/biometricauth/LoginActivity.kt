package com.example.biometricauth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceContour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide

class LoginActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var storage: FirebaseStorage
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var registerLink: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button

    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var faceDetector: FaceDetector
    private var storedFace: Map<String, PointF>? = null
    private var storedFaceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        storage = FirebaseStorage.getInstance()

        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        registerLink = findViewById(R.id.registerLink)
        progressBar = findViewById(R.id.progressBar)
        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)

        loginButton.setOnClickListener { loginUser() }
        registerLink.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        captureButton.setOnClickListener { takePhoto() }

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
        faceDetector = FaceDetection.getClient(options)
    }

    private fun loginUser() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        loginButton.isEnabled = false

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                progressBar.visibility = View.GONE
                loginButton.isEnabled = true

                if (task.isSuccessful) {
                    Toast.makeText(this, "Login successful, please verify your face", Toast.LENGTH_SHORT).show()
                    loadStoredFace()
                } else {
                    Toast.makeText(this, "Login failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun loadStoredFace() {
        val userId = auth.currentUser?.uid ?: return
        progressBar.visibility = View.VISIBLE

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File.createTempFile("stored_face", ".jpg", cacheDir)
                storage.reference.child("user_faces/$userId.jpg").getFile(tempFile).await()
                storedFaceUri = Uri.fromFile(tempFile)

                storedFace = detectFace(storedFaceUri!!)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (storedFace != null) {
                        showCameraUI()
                    } else {
                        Toast.makeText(this@LoginActivity, "Failed to load stored face", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@LoginActivity, "Error loading stored face: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCameraUI() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        emailEditText.visibility = View.GONE
        passwordEditText.visibility = View.GONE
        loginButton.visibility = View.GONE
        registerLink.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@LoginActivity, "Photo capture failed", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    verifyFace(savedUri)
                }
            }
        )
    }

    private fun verifyFace(capturedImageUri: Uri) {
        progressBar.visibility = View.VISIBLE
        captureButton.isEnabled = false

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val capturedFace = detectFace(capturedImageUri)
                if (capturedFace == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "No face detected in the captured image", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        captureButton.isEnabled = true
                    }
                    return@launch
                }

                val storedFace = this@LoginActivity.storedFace
                if (storedFace == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "Stored face not available", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        captureButton.isEnabled = true
                    }
                    return@launch
                }

                val similarity = compareFaces(capturedFace, storedFace)

                withContext(Dispatchers.Main) {
                    showComparisonDialog(capturedImageUri, similarity, capturedFace, storedFace)
                    progressBar.visibility = View.GONE
                    captureButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                    captureButton.isEnabled = true
                }
            }
        }
    }

    private fun showComparisonDialog(capturedImageUri: Uri, similarity: Float, capturedFace: Map<String, PointF>, storedFace: Map<String, PointF>) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.face_comparison_dialog)

        val storedFaceImageView = dialog.findViewById<ImageView>(R.id.storedFaceImageView)
        val capturedFaceImageView = dialog.findViewById<ImageView>(R.id.capturedFaceImageView)
        val comparisonImageView = dialog.findViewById<ImageView>(R.id.comparisonImageView)
        val similarityTextView = dialog.findViewById<TextView>(R.id.similarityTextView)
        val closeButton = dialog.findViewById<Button>(R.id.closeButton)

        // Load stored face image
        storedFaceUri?.let { uri ->
            Glide.with(this).load(uri).into(storedFaceImageView)
        }

        // Load captured face image
        Glide.with(this).load(capturedImageUri).into(capturedFaceImageView)

        // Create comparison visualization
        val comparisonBitmap = createComparisonVisualization(capturedFace, storedFace)
        comparisonImageView.setImageBitmap(comparisonBitmap)

        similarityTextView.text = "Similarity: ${(similarity * 100).toInt()}%"

        closeButton.setOnClickListener {
            dialog.dismiss()
            if (similarity > SIMILARITY_THRESHOLD) {
                Toast.makeText(this@LoginActivity, "Face verification successful", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@LoginActivity, "Face verification failed", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun createComparisonVisualization(face1: Map<String, PointF>, face2: Map<String, PointF>): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        // Draw background
        canvas.drawColor(Color.WHITE)

        // Normalize coordinates
        val normalizedFace1 = normalizeFaceCoordinates(face1)
        val normalizedFace2 = normalizeFaceCoordinates(face2)

        // Draw facial landmarks
        paint.color = Color.BLUE
        for ((key, point) in normalizedFace1) {
            canvas.drawCircle(point.x * 400, point.y * 400, 5f, paint)
        }

        paint.color = Color.RED
        for ((key, point) in normalizedFace2) {
            canvas.drawCircle(point.x * 400, point.y * 400, 5f, paint)
        }

        // Draw lines between corresponding points
        paint.color = Color.GREEN
        paint.strokeWidth = 2f
        for ((key, point1) in normalizedFace1) {
            val point2 = normalizedFace2[key]
            if (point2 != null) {
                canvas.drawLine(point1.x * 400, point1.y * 400, point2.x * 400, point2.y * 400, paint)
            }
        }

        return bitmap
    }

    private suspend fun detectFace(imageUri: Uri): Map<String, PointF>? {
        return withContext(Dispatchers.Default) {
            val image = InputImage.fromFilePath(this@LoginActivity, imageUri)
            val faces = faceDetector.process(image).await()
            if (faces.isNotEmpty()) {
                val face = faces[0]
                val landmarks = mutableMapOf<String, PointF>()

                // Add more facial landmarks
                face.getLandmark(FaceLandmark.LEFT_EYE)?.let { landmarks["LEFT_EYE"] = it.position }
                face.getLandmark(FaceLandmark.RIGHT_EYE)?.let { landmarks["RIGHT_EYE"] = it.position }
                face.getLandmark(FaceLandmark.NOSE_BASE)?.let { landmarks["NOSE_BASE"] = it.position }
                face.getLandmark(FaceLandmark.MOUTH_LEFT)?.let { landmarks["MOUTH_LEFT"] = it.position }
                face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.let { landmarks["MOUTH_RIGHT"] = it.position }
                face.getLandmark(FaceLandmark.LEFT_EAR)?.let { landmarks["LEFT_EAR"] = it.position }
                face.getLandmark(FaceLandmark.RIGHT_EAR)?.let { landmarks["RIGHT_EAR"] = it.position }
                face.getLandmark(FaceLandmark.LEFT_CHEEK)?.let { landmarks["LEFT_CHEEK"] = it.position }
                face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.let { landmarks["RIGHT_CHEEK"] = it.position }

                // Add contour points
                face.getContour(FaceContour.FACE)?.points?.let { landmarks["FACE_CONTOUR"] = it.first() }
                face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points?.let { landmarks["LEFT_EYEBROW_TOP"] = it.first() }
                face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points?.let { landmarks["RIGHT_EYEBROW_TOP"] = it.first() }
                face.getContour(FaceContour.NOSE_BRIDGE)?.points?.let { landmarks["NOSE_BRIDGE"] = it.first() }
                face.getContour(FaceContour.UPPER_LIP_TOP)?.points?.let { landmarks["UPPER_LIP_TOP"] = it.first() }
                face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points?.let { landmarks["LOWER_LIP_BOTTOM"] = it.first() }

                landmarks
            } else {
                null
            }
        }
    }

    private fun compareFaces(face1: Map<String, PointF>, face2: Map<String, PointF>): Float {
        if (face1.size != face2.size) return 0f

        // Normalize coordinates
        val normalizedFace1 = normalizeFaceCoordinates(face1)
        val normalizedFace2 = normalizeFaceCoordinates(face2)

        var totalWeightedDistance = 0f
        var totalWeight = 0f

        // Define weights for different facial features
        val weights = mapOf(
            "LEFT_EYE" to 2f, "RIGHT_EYE" to 2f,
            "NOSE_BASE" to 1.5f,
            "MOUTH_LEFT" to 1.5f, "MOUTH_RIGHT" to 1.5f,
            "LEFT_EAR" to 1f, "RIGHT_EAR" to 1f,
            "LEFT_CHEEK" to 1f, "RIGHT_CHEEK" to 1f,
            "FACE_CONTOUR" to 1f,
            "LEFT_EYEBROW_TOP" to 1.2f, "RIGHT_EYEBROW_TOP" to 1.2f,
            "NOSE_BRIDGE" to 1.3f,
            "UPPER_LIP_TOP" to 1.2f, "LOWER_LIP_BOTTOM" to 1.2f
        )

        for ((key, point1) in normalizedFace1) {
            val point2 = normalizedFace2[key] ?: continue
            val weight = weights[key] ?: 1f
            val distance = calculateDistance(point1, point2)
            totalWeightedDistance += distance * weight
            totalWeight += weight
        }

        // Calculate similarity (inverse of average weighted distance)
        val averageWeightedDistance = totalWeightedDistance / totalWeight
        val similarity = 1 / (1 + averageWeightedDistance)

        // Calculate and compare angles between key landmarks
        val anglesSimilarity = compareAngles(normalizedFace1, normalizedFace2)

        // Combine distance-based similarity and angle-based similarity
        return (similarity * 0.7f + anglesSimilarity * 0.3f)
    }

    private fun normalizeFaceCoordinates(face: Map<String, PointF>): Map<String, PointF> {
        val points = face.values
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val maxX = points.maxOf { it.x }
        val maxY = points.maxOf { it.y }

        return face.mapValues { (_, point) ->
            PointF(
                (point.x - minX) / (maxX - minX),
                (point.y - minY) / (maxY - minY)
            )
        }
    }

    private fun calculateDistance(p1: PointF, p2: PointF): Float {
        return sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
    }

    private fun compareAngles(face1: Map<String, PointF>, face2: Map<String, PointF>): Float {
        val keyPoints = listOf("LEFT_EYE", "RIGHT_EYE", "NOSE_BASE", "MOUTH_LEFT", "MOUTH_RIGHT")
        var totalAngleDifference = 0f
        var count = 0

        for (i in 0 until keyPoints.size - 2) {
            for (j in i + 1 until keyPoints.size - 1) {
                for (k in j + 1 until keyPoints.size) {
                    val angle1 = calculateAngle(face1[keyPoints[i]]!!, face1[keyPoints[j]]!!, face1[keyPoints[k]]!!)
                    val angle2 = calculateAngle(face2[keyPoints[i]]!!, face2[keyPoints[j]]!!, face2[keyPoints[k]]!!)
                    totalAngleDifference += abs(angle1 - angle2)
                    count++
                }
            }
        }

        val averageAngleDifference = totalAngleDifference / count
        return 1 - (averageAngleDifference / 180f) // Normalize to [0, 1]
    }

    private fun calculateAngle(p1: PointF, p2: PointF, p3: PointF): Float {
        val angle1 = atan2(p1.y - p2.y, p1.x - p2.x)
        val angle2 = atan2(p3.y - p2.y, p3.x - p2.x)
        var angle = abs(angle1 - angle2) * (180 / Math.PI.toFloat())
        if (angle > 180) angle = 360 - angle
        return angle
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val SIMILARITY_THRESHOLD = 0.98f
    }
}