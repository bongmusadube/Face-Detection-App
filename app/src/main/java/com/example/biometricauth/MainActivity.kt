package com.example.biometricauth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var welcomeTextView: TextView
    private lateinit var emailTextView: TextView
    private lateinit var logoutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()

        welcomeTextView = findViewById(R.id.welcomeTextView)
        emailTextView = findViewById(R.id.emailTextView)
        logoutButton = findViewById(R.id.logoutButton)

        // Check if user is signed in
        val currentUser = auth.currentUser
        if (currentUser == null) {
            // Not signed in, launch the LoginActivity activity
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // User is signed in, display email
        emailTextView.text = currentUser.email

        // Set up logout button
        logoutButton.setOnClickListener {
            logoutUser()
        }
    }

    private fun logoutUser() {
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}