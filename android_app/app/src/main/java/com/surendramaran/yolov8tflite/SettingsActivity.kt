package com.surendramaran.yolov8tflite

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import com.surendramaran.yolov8tflite.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        const val PREF_NAME = "YoloSettings"
        const val KEY_VEHICLE_TYPE = "vehicleType"
        const val VEHICLE_TYPE_CAR = "araba"
        const val VEHICLE_TYPE_VAN = "panelvan"
        const val VEHICLE_TYPE_MINIBUS = "minibus"
        const val VEHICLE_TYPE_BUS = "otobus"
        const val VEHICLE_TYPE_TRUCK = "kamyon"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // SharedPreferences initialize
        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // Kayıtlı araç tipini al (varsayılan olarak araba)
        val savedVehicleType = sharedPreferences.getString(KEY_VEHICLE_TYPE, VEHICLE_TYPE_CAR)

        // Kayıtlı değere göre radio button'ı seç
        when (savedVehicleType) {
            VEHICLE_TYPE_CAR -> binding.radioAraba.isChecked = true
            VEHICLE_TYPE_VAN -> binding.radioPanelvan.isChecked = true
            VEHICLE_TYPE_MINIBUS -> binding.radioMinibus.isChecked = true
            VEHICLE_TYPE_BUS -> binding.radioOtobus.isChecked = true
            VEHICLE_TYPE_TRUCK -> binding.radioKamyon.isChecked = true
        }

        // Radio group için değişiklik dinleyicisi
        binding.radioGroupVehicles.setOnCheckedChangeListener { _, checkedId ->
            val selectedVehicleType = when (checkedId) {
                R.id.radioAraba -> VEHICLE_TYPE_CAR
                R.id.radioPanelvan -> VEHICLE_TYPE_VAN
                R.id.radioMinibus -> VEHICLE_TYPE_MINIBUS
                R.id.radioOtobus -> VEHICLE_TYPE_BUS
                R.id.radioKamyon -> VEHICLE_TYPE_TRUCK
                else -> VEHICLE_TYPE_CAR
            }

            // Seçilen değeri kaydet
            sharedPreferences.edit().putString(KEY_VEHICLE_TYPE, selectedVehicleType).apply()
        }

        // Geri dönüş butonu
        binding.buttonBack.setOnClickListener {
            finish()
        }
    }
}