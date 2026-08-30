package com.alex.iptvplayer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var client: XtreamClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        binding.editServerUrl.setText(client.serverUrl)
        binding.editUsername.setText(client.username)
        binding.editPassword.setText(client.password)

        binding.btnSaveSettings.setOnClickListener {
            val url = binding.editServerUrl.text.toString().trim()
            val user = binding.editUsername.text.toString().trim()
            val pass = binding.editPassword.text.toString().trim()

            if (url.isNotEmpty()) {
                client.serverUrl = url
                client.username = user
                client.password = pass
                Toast.makeText(this, "Einstellungen gespeichert!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Bitte Server-URL eingeben", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
