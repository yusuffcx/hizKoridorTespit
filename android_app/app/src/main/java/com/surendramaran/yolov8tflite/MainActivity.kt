package com.surendramaran.yolov8tflite

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hız Koridoru Tespit butonuna tıklama olayını ekleyelim
        binding.speedCameraDetectionButton.setOnClickListener {
            // DetectionActivity'yi başlat
            val intent = Intent(this, DetectionActivity::class.java)
            startActivity(intent)
        }

        // Ayarlar butonuna tıklama olayını ekleyelim
        binding.settingsButton.setOnClickListener {
            // SettingsActivity'yi başlat
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
}