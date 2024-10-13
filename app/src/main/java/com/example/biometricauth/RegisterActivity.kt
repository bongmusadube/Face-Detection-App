package com.example.biometricauth

import android.Manifest
import android.content.pm.PackageManager
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RegisterActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var storage: FirebaseStorage
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var nextButton: Button
    private lateinit var captureButton: Button
    private lateinit var completeRegistrationButton: Button
    private lateinit var loginLink: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var previewView: PreviewView

    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private var capturedImageUri: Uri? = null

    private lateinit var faceDetector: FaceDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        storage = FirebaseStorage.getInstance()

        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        nextButton = findViewById(R.id.nextButton)
        captureButton = findViewById(R.id.captureButton)
        completeRegistrationButton = findViewById(R.id.completeRegistrationButton)
        loginLink = findViewById(R.id.loginLink)
        progressBar = findViewById(R.id.progressBar)
        previewView = findViewById(R.id.previewView)

        nextButton.setOnClickListener {
            if (validateInputs()) {
                showCameraUI()
            }
        }

        captureButton.setOnClickListener {
            takePhoto()
        }

        completeRegistrationButton.setOnClickListener {
            registerUser()
        }

        loginLink.setOnClickListener {
            finish() // Go back to LoginActivity
        }

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize face detector
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        faceDetector = FaceDetection.getClient(options)
    }

    private fun validateInputs(): Boolean {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
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
        nextButton.visibility = View.GONE
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
                    Toast.makeText(this@RegisterActivity, "Photo capture failed", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedImageUri = Uri.fromFile(photoFile)
                    validateFaceImage(capturedImageUri!!)
                }
            }
        )
    }

    private fun validateFaceImage(imageUri: Uri) {
        val image = InputImage.fromFilePath(this, imageUri)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    Toast.makeText(this, "Face detected successfully", Toast.LENGTH_SHORT).show()
                    showCompleteRegistrationUI()
                } else {
                    Toast.makeText(this, "No face detected. Please try again.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Face detection failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showCompleteRegistrationUI() {
        previewView.visibility = View.GONE
        captureButton.visibility = View.GONE
        completeRegistrationButton.visibility = View.VISIBLE
    }

    private fun registerUser() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        progressBar.visibility = View.VISIBLE
        completeRegistrationButton.isEnabled = false

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    uploadFaceImage(task.result?.user?.uid)
                } else {
                    progressBar.visibility = View.GONE
                    completeRegistrationButton.isEnabled = true
                    Toast.makeText(this, "Registration failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun uploadFaceImage(userId: String?) {
        if (userId == null || capturedImageUri == null) {
            Toast.makeText(this, "Error: Missing user ID or face image", Toast.LENGTH_SHORT).show()
            return
        }

        val image = InputImage.fromFilePath(this, capturedImageUri!!)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    // Face detected, proceed with upload
                    uploadToFirebaseStorage(userId)
                } else {
                    progressBar.visibility = View.GONE
                    completeRegistrationButton.isEnabled = true
                    Toast.makeText(this, "No face detected in the image. Please try again.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                completeRegistrationButton.isEnabled = true
                Toast.makeText(this, "Face detection failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun uploadToFirebaseStorage(userId: String) {
        val ref = storage.reference.child("user_faces/$userId.jpg")
        ref.putFile(capturedImageUri!!)
            .addOnSuccessListener {
                progressBar.visibility = View.GONE
                completeRegistrationButton.isEnabled = true
                Toast.makeText(this, "User Registered Successfully", Toast.LENGTH_SHORT).show()
                finish() // Go back to LoginActivity
            }
            .addOnFailureListener {
                progressBar.visibility = View.GONE
                completeRegistrationButton.isEnabled = true
                Toast.makeText(this, "Failed to upload face image: ${it.message}", Toast.LENGTH_SHORT).show()
            }
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
    }
}