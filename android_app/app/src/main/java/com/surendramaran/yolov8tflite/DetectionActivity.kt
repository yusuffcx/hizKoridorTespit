package com.surendramaran.yolov8tflite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityDetectionBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Timer
import java.util.TimerTask

class DetectionActivity : AppCompatActivity(), Detector.DetectorListener, LocationListener {
    private lateinit var binding: ActivityDetectionBinding
    private val isFrontCamera = false

    private var speedCorridorDetectedBeep: MediaPlayer? = null
    private var speedExceededBeep: MediaPlayer? = null
    private var cameraBeep: MediaPlayer? = null  // Kamera algılandığında çalacak ses
    private var speedValues = mutableListOf<Float>()  // Hız değerlerini saklamak için liste
    private var avgSpeed = 0f  // Ortalama hız değeri
    private var cameraDetectedTimestamp = 0L
    private val boundingBoxTimer = Handler(Looper.getMainLooper())
    private val clearBoundingBoxRunable = Runnable{
        binding.overlay.clear()
        binding.overlay.invalidate()
    }

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var selectedVehicleType: String

    private lateinit var locationManager : LocationManager
    private var currentSpeed = 0f
    private var isSpeedingWarningShown = false

    // Yeni eklenen değişken
    private var currentDetectedSpeedLimit = 0
    // Hız göstergesi güncelleme zamanı
    private var lastSpeedUpdateTime = 0L

    private var firstCameraDetectedTime: Long = 0
    private var firstCameraLocation: Location? = null
    private var isBetweenCameras: Boolean = false
    private var detectedCamerasCount : Int = 0
    private var currentAvgSpeed : Float = 0f
    private var speedCheckTimer: Timer? = null
    private var isSpeedCorridorDetected : Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hız göstergesini başlangıçta görünür yap ve 0 değeri göster
        binding.speedMeterCard.visibility = View.VISIBLE
        binding.avgSpeedMeterCard.visibility = View.VISIBLE
        binding.currentSpeedText.text = "0"
        binding.currentSpeedTextMS.text = "0.00"
        binding.avgSpeedText.text = "0"

        cameraBeep = MediaPlayer.create(this, R.raw.kamera_algilandi)  // raw klasörüne beep_sound.mp3 ekleyin
        speedCorridorDetectedBeep = MediaPlayer.create(this, R.raw.hiz_koridor_algilandi)
        speedExceededBeep = MediaPlayer.create(this, R.raw.hiz_asimi)


        if (allPermissionsGranted()) {
            startCamera()
            startLocationUpdates() // Konum güncellemelerini başlat
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        // SharedPreferences'dan araç tipini al
        sharedPreferences = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE)
        selectedVehicleType = sharedPreferences.getString(
            SettingsActivity.KEY_VEHICLE_TYPE,
            SettingsActivity.VEHICLE_TYPE_CAR
        ) ?: SettingsActivity.VEHICLE_TYPE_CAR

      //  mediaPlayer = MediaPlayer.create(this, R.raw.hiz_koridor_algilandi)

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                Log.d(TAG, "Konum güncellemeleri başlatılıyor")
                locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

                // GPS aktif mi kontrol et
                val isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                Log.d(TAG, "GPS aktif mi: $isGPSEnabled")

                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // 1 saniye
                    0f,    // 1 metre
                    this
                )
                Log.d(TAG, "Konum güncellemeleri için istek yapıldı")
            } catch (e: Exception) {
                Log.e(TAG, "Konum güncellemeleri başlatılamadı: ${e.message}")
            }
        } else {
            Log.d(TAG, "Konum izni verilmedi")
        }
    }

    // LocationListener metodları
    override fun onLocationChanged(location: Location) {
        try {
            // Çok sık güncelleme yapmamak için kontrol
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSpeedUpdateTime < 500) { // 500ms'den kısa sürede güncelleme yapma
                return
            }
            lastSpeedUpdateTime = currentTime

            // Hız m/s cinsinden geliyor, km/h'ye çeviriyoruz
            val speedMS = location.speed
            currentSpeed = speedMS * 3.6f // m/s'yi km/h'ye çevir

            if(isBetweenCameras && firstCameraLocation!= null && isSpeedCorridorDetected)
            {
                calculateAvgSpeed(location,currentTime)
            }

            // Hız değerini ekranda göster
            runOnUiThread {
                // Sadece hız değerini göster (km/h kısmı ayrı bir TextView'da)
                binding.currentSpeedText.text = "${currentSpeed.toInt()}"
                binding.currentSpeedTextMS.text = String.format("%.2f", speedMS)

                // Hız sınırı kontrolü
                val speedLimit = getSpeedLimitFromDetections()
                if (speedLimit > 0 && currentSpeed > speedLimit) {
                    binding.currentSpeedText.setTextColor(Color.RED)
                } else {
                    binding.currentSpeedText.setTextColor(Color.WHITE)
                }
            }

            // Hız sınırı aşıldı mı kontrol et
            checkSpeedLimit()

            Log.d(TAG, "Güncel hız: ${currentSpeed.toInt()} km/h")
        } catch (e: Exception) {
            Log.e(TAG, "Hız güncellemesi sırasında hata: ${e.message}")
        }
    }

    // Diğer LocationListener metodları (Android 9 için gerekli olabilir)
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // Güncellenen metod
    private fun getSpeedLimitFromDetections(): Int {
        return currentDetectedSpeedLimit
    }

    // İzin isteme işlemi için REQUIRED_PERMISSIONS'ı güncelleyelim
    companion object {
        private const val TAG = "Detection"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).toTypedArray()
    }

    // Güncellenen checkSpeedLimit metodu
    private fun checkSpeedLimit() {
        val speedLimit = getSpeedLimitFromDetections()

        Log.d(TAG, "Checking speed limit: Current speed $currentSpeed, Speed limit $speedLimit")

        if (speedLimit > 0 && currentSpeed > speedLimit) {
            // Hız sınırı aşıldı
            Log.d(TAG, "Speed limit exceeded!")
            if (!isSpeedingWarningShown) {
                binding.speedWarningText.visibility = View.VISIBLE
                isSpeedingWarningShown = true
            }
        } else {
            // Hız sınırı aşılmadı
            Log.d(TAG, "Speed within limits")
            if (isSpeedingWarningShown) {
                binding.speedWarningText.visibility = View.INVISIBLE
                isSpeedingWarningShown = false
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun CoridorAlertSound() {
        try {
            speedCorridorDetectedBeep?.seekTo(0)
            speedCorridorDetectedBeep?.start()
        } catch (e: Exception) {
            Log.e("DetectionActivity", "Hız Koridoru ses çalma hatası", e)
        }
    }

    private fun SpeedLimitExceed()
    {
        try {
            speedExceededBeep?.seekTo(0)
            speedExceededBeep?.start()
        } catch (e: Exception) {
            Log.e("DetectionActivity", "Hız Koridoru ses çalma hatası", e)
        }
    }

    private fun CameraAlertSound() {
        try {
            cameraBeep?.seekTo(0)
            cameraBeep?.start()
        } catch (e: Exception) {
            Log.e("DetectionActivity", "Kamera Uyarı Ses çalma hatası", e)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun calculateAvgSpeed(currentLocation: Location, currentTime: Long) {
        try {
            // İlk kamera konumu ve zamanı kontrolü
            if (firstCameraLocation == null || firstCameraDetectedTime == 0L) {
                Log.e(TAG, "İlk kamera verisi yok, ortalama hız hesaplanamıyor")
                return
            }


            // İlk kameradan şu anki konuma olan mesafeyi hesapla (metre)
            val distance = firstCameraLocation!!.distanceTo(currentLocation)

            // İlk kameradan şu ana kadar geçen süreyi hesapla (saniye)
            val timeSeconds = (currentTime - firstCameraDetectedTime) / 1000f

            // Süre çok kısaysa hesaplama yapma (bölme hatası olmaması için)
            if (timeSeconds < 1) {
                return
            }

            // Ortalama hızı hesapla (m/s)
            val avgSpeedMS = distance / timeSeconds

            // km/h'ye çevir
            currentAvgSpeed = avgSpeedMS * 3.6f

            // Eğer hız sınırını kontrol ediyorsak, ortalama hız için de kontrol et
            checkAverageSpeedLimit()
        } catch (e: Exception) {
            Log.e(TAG, "Ortalama hız hesaplanırken hata: ${e.message}")
        }
    }

    private fun checkAverageSpeedLimit()
    {
        val speedLimit = getSpeedLimitFromDetections()

        if(speedLimit >0 && (currentAvgSpeed > speedLimit))
        {
            SpeedLimitExceed()
            Toast.makeText(this, "Ortalama Hızınız , Hız limitini aştı! Hızınızı düşürünüz!", Toast.LENGTH_LONG).show()
            Toast.makeText(this, "Ortalama Hızınız , Hız limitini aştı! Hızınızı düşürünüz!", Toast.LENGTH_LONG).show()

        }
    }


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Kamera izni kontrolü
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        }

        // Konum izni kontrolü (açıkça yapılmalı)
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startLocationUpdates()
            Log.d(TAG, "Konum izni verildi, konum güncellemeleri başlatılıyor")
        } else {
            Log.d(TAG, "Konum izni reddedildi")
        }

        // Tüm izinlerin kontrol edilmesi
        val allGranted = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            Log.d(TAG, "Tüm izinler verildi")
        } else {
            Log.d(TAG, "Bazı izinler hala eksik")
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            // Kaynakları temizle
            speedCorridorDetectedBeep?.release()
            speedCorridorDetectedBeep = null
            camera = null
            cameraBeep?.release()
            cameraBeep = null

            cameraProvider?.unbindAll()
            detector.clear()
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy", e)
        }
    }

    // Geri tuşuna basıldığında
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        try {
            // Kamera kaynaklarını temizle
            camera = null
            cameraProvider?.unbindAll()

            // Ana ekrana dön
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error during back navigation", e)
            super.onBackPressed() // Yine de normal işleme devam et
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            // Kamera işlemlerini durdur
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error during onPause", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // Ayarlar değişmiş olabilir, tekrar kontrol et
            selectedVehicleType = sharedPreferences.getString(
                SettingsActivity.KEY_VEHICLE_TYPE,
                SettingsActivity.VEHICLE_TYPE_CAR
            ) ?: SettingsActivity.VEHICLE_TYPE_CAR

            // Speed görünür olmalı her zaman
            binding.speedMeterCard.visibility = View.VISIBLE

            // Eğer önceden kaydedilmiş bir hız varsa değerini göster, yoksa 0 göster
            if (currentSpeed > 0) {
                binding.currentSpeedText.text = "${currentSpeed.toInt()}"
                val lastMS = currentSpeed / 3.6f
                binding.currentSpeedTextMS.text = String.format("%.2f", lastMS)
            } else {
                binding.currentSpeedText.text = "0"
                binding.currentSpeedTextMS.text = "0.00"

            }

            if (allPermissionsGranted()) {
                startCamera()
            } else {
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during onResume", e)
        }
    }

    override fun onEmptyDetect() {
        try {
            binding.overlay.invalidate()
        } catch (e: Exception) {
            Log.e(TAG, "Error during onEmptyDetect", e)
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        try {
            runOnUiThread {
                binding.inferenceTime.text = "${inferenceTime}ms"

                // Değişkenler tanımlayalım
                var speedLimit = 0
                var isCameraDetected = false


                // Algılanan nesneleri kontrol edelim
                for (box in boundingBoxes) {
                    // Hız koridoru tabelası algılandı mı?
                    if (box.clsName == "hiz_koridoru_tabela") {
                        isSpeedCorridorDetected = true
                    }

                    if (box.clsName == "kamera") {
                        isCameraDetected = true
                    }

                    // Seçilen araç tipine göre hız sınırı tabelalarını kontrol edelim
                    if (box.clsName.startsWith("${selectedVehicleType}_")) {
                        // Etiketin sonundaki sayıyı alalım
                        val parts = box.clsName.split("_")
                        if (parts.size > 1) {
                            try {
                                val limit = parts[1].toInt()
                                speedLimit = limit
                                // Yeni eklenen kod - hız sınırını güncelle
                                currentDetectedSpeedLimit = limit
                                // Hız sınırı kontrolünü çağır
                                checkSpeedLimit()
                            } catch (e: Exception) {
                                Log.e("DetectionActivity", "Hız değeri dönüştürülemedi: ${parts[1]}")
                            }
                        }
                    }
                }

                // UI'ı güncelleyelim
                if (isSpeedCorridorDetected) {
                    //binding.corridorText.visibility = View.VISIBLE
                    Toast.makeText(this, "HIZ KORİDORU KAMERASI ALGILANDI!", Toast.LENGTH_LONG).show()
                    Toast.makeText(this, "HIZ KORİDORU KAMERASI ALGILANDI!", Toast.LENGTH_LONG).show()

                    CoridorAlertSound()
                } /*else {
                    binding.corridorText.visibility = View.INVISIBLE
                }*/

                if (isCameraDetected) {
                    val currentTime = System.currentTimeMillis()

                    // Kamera ilk kez algılandıysa veya son algılamadan 3 saniye geçtiyse
                    if (currentTime - cameraDetectedTimestamp > 3000) {
                        // Mesajı göster ve ses çal

                        runOnUiThread {
                            Toast.makeText(this, "HIZ KORİDORU KAMERASI ALGILANDI!", Toast.LENGTH_LONG).show()
                            Toast.makeText(this, "HIZ KORİDORU KAMERASI ALGILANDI!", Toast.LENGTH_LONG).show()

                        }
                        CameraAlertSound()
                        CameraAlertSound()

                        // Zaman damgasını güncelle
                        cameraDetectedTimestamp = currentTime
                    }
                }

                if (speedLimit > 0) {
                    binding.speedLimitIcon.visibility = View.VISIBLE
                    binding.speedLimitText.text = speedLimit.toString()
                } else {
                    binding.speedLimitIcon.visibility = View.INVISIBLE
                }

                binding.overlay.apply {
                    setResults(boundingBoxes)
                    invalidate()
                }

                boundingBoxTimer.removeCallbacks(clearBoundingBoxRunable)
                boundingBoxTimer.postDelayed(clearBoundingBoxRunable, 3000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDetect", e)
        }
    }
}