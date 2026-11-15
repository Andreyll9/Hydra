package com.teleghost.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

class MainActivity : AppCompatActivity(), mobile.PlatformBridge {

    private lateinit var webView: WebView
    private var webViewFileCallback: ValueCallback<Array<Uri>>? = null
    private var isOpeningSystemPicker = false
    private var pausedAtMs: Long = 0L

    companion object {
        private const val TAG = "Hydra"
        private const val SERVER_URL = "http://127.0.0.1:8080"
        private const val HEALTH_URL = "$SERVER_URL/health"
        private const val MAX_RETRIES = 30
        private const val RETRY_DELAY_MS = 1000L
        private const val NOTIFICATION_CHANNEL_ID = "hydra_heads_up_messages"
        private const val PREFS_NAME = "hydra_biometric_prefs"
        var currentInstance: MainActivity? = null
        fun handleDirectReplyFromNotification(senderNick: String, replyText: String) {
            currentInstance?.runOnUiThread {
                val sNick = JSONObject.quote(senderNick)
                val sText = JSONObject.quote(replyText)
                currentInstance?.webView?.evaluateJavascript(
                    "if(window.__onHydraDirectReply) window.__onHydraDirectReply($sNick, $sText);",
                    null
                )
            }
        }
    }

    fun pushNativeLog(tag: String, msg: String) {
        android.util.Log.i(TAG, "[$tag] $msg")
        runOnUiThread {
            if (::webView.isInitialized) {
                val safeTag = JSONObject.quote(tag)
                val safeMsg = JSONObject.quote(msg)
                webView.evaluateJavascript("if(window.__addHydraLog) window.__addHydraLog($safeTag, $safeMsg);", null)
            }
        }
    }

    inner class HydraNativeBridge {

        @JavascriptInterface
        fun getLocalWifiInfo(): String {
            return try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var localIp = ""
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: ""
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                localIp = ip
                                break
                            }
                        }
                    }
                    if (localIp.isNotEmpty()) break
                }
                val res = org.json.JSONObject()
                res.put("lanIp", localIp)
                res.put("lanActive", localIp.isNotEmpty())
                res.put("directPort", 8080)
                res.toString()
            } catch (e: Exception) {
                "{\"lanIp\":\"\",\"lanActive\":false}"
            }
        }
    

        @JavascriptInterface
        fun saveLogsToDownloads(logText: String): String {
            return try {
                val filename = "hydra_log_${System.currentTimeMillis()}.txt"
                val resolver = applicationContext.contentResolver
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (!dlDir.exists()) dlDir.mkdirs()
                    val outFile = File(dlDir, filename)
                    outFile.writeText(logText, Charsets.UTF_8)
                    runOnUiThread {
                        android.widget.Toast.makeText(this@MainActivity, "✅ Лог сохранён в Загрузки: $filename", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return filename
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(logText.toByteArray(Charsets.UTF_8)) }
                    runOnUiThread {
                        android.widget.Toast.makeText(this@MainActivity, "✅ Лог сохранён в Загрузки: $filename", android.widget.Toast.LENGTH_LONG).show()
                    }
                    pushNativeLog("LOG-FILE", "Сохранено в Downloads/$filename")
                    filename
                } else "error"
            } catch (e: Exception) {
                pushNativeLog("LOG-ERR", "Ошибка записи файла: ${e.message}")
                "error"
            }
        }
    

        @JavascriptInterface
        fun checkSystemStatus(profileId: String): String {
            val cam = if (Build.VERSION.SDK_INT >= 23) checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED else true
            val mic = if (Build.VERSION.SDK_INT >= 23) checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED else true
            val notif = if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hasBioBound = prefs.contains("bio_pin_$profileId") || prefs.contains("bio_pin_default")
            val obj = JSONObject()
            obj.put("camera", cam)
            obj.put("microphone", mic)
            obj.put("notifications", notif)
            obj.put("biometricBound", hasBioBound)
            obj.put("sdkInt", Build.VERSION.SDK_INT)
            obj.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            pushNativeLog("SYS-CHECK", "Устройство ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}): CAM=$cam, MIC=$mic, BIO_BOUND=$hasBioBound (profile=$profileId)")
            return obj.toString()
        }

        @JavascriptInterface
        fun requestMissingPermissions() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val needed = mutableListOf<String>()
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(android.Manifest.permission.RECORD_AUDIO)
                    }
                    if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(android.Manifest.permission.CAMERA)
                    }
                    if (Build.VERSION.SDK_INT <= 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (needed.isNotEmpty()) {
                        pushNativeLog("ANDROID-PERM", "Запрос разрешений: ${needed.joinToString()}")
                        requestPermissions(needed.toTypedArray(), 101)
                    } else {
                        pushNativeLog("ANDROID-PERM", "Все разрешения уже выданы")
                    }
                }
            }
        }

        @JavascriptInterface
        fun enrollBiometric(profileId: String, pin: String) {
            runOnUiThread {
                pushNativeLog("BIO-HW", "Запуск аппаратного сканера отпечатка для ПРИВЯЗКИ (profile=$profileId)...")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    pushNativeLog("BIO-ERR", "Android SDK < 28, BiometricPrompt недоступен")
                    webView.evaluateJavascript("if(window.__onBiometricEnrollResult) window.__onBiometricEnrollResult(false, 'Версия Android ниже 9.0');", null)
                    return@runOnUiThread
                }
                try {
                    val cancelSignal = CancellationSignal()
                    val prompt = BiometricPrompt.Builder(this@MainActivity)
                        .setTitle("Привязка отпечатка пальца")
                        .setSubtitle("Приложите палец к сканеру для подтверждения")
                        .setDescription("Ваш ПИН-код будет зашифрован и доступен только по вашему отпечатку пальца.")
                        .setNegativeButton("Отмена", mainExecutor) { _, _ ->
                            pushNativeLog("BIO-HW", "Пользователь отменил привязку отпечатка")
                            webView.evaluateJavascript("if(window.__onBiometricEnrollResult) window.__onBiometricEnrollResult(false, 'Отменено пользователем');", null)
                        }
                        .build()

                    prompt.authenticate(cancelSignal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            super.onAuthenticationSucceeded(result)
                            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit()
                                .putString("bio_pin_$profileId", pin)
                                .putString("bio_pin_default", pin)
                                .apply()
                            pushNativeLog("BIO-OK", "Аппаратный отпечаток УСПЕШНО считан и привязан к профилю $profileId!")
                            webView.evaluateJavascript("if(window.__onBiometricEnrollResult) window.__onBiometricEnrollResult(true, 'Отпечаток успешно подтверждён и привязан!');", null)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            super.onAuthenticationError(errorCode, errString)
                            pushNativeLog("BIO-ERR", "Ошибка сканера при привязке (код $errorCode): $errString")
                            val safeErr = JSONObject.quote(errString?.toString() ?: "Ошибка сканера")
                            webView.evaluateJavascript("if(window.__onBiometricEnrollResult) window.__onBiometricEnrollResult(false, $safeErr);", null)
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            pushNativeLog("BIO-FAIL", "Отпечаток не распознан датчиком (попробуйте ещё раз)")
                        }
                    })
                } catch (e: Exception) {
                    pushNativeLog("BIO-EXCEPTION", "Исключение BiometricPrompt: ${e.message}")
                    val safeErr = JSONObject.quote(e.message ?: "Ошибка биометрии")
                    webView.evaluateJavascript("if(window.__onBiometricEnrollResult) window.__onBiometricEnrollResult(false, $safeErr);", null)
                }
            }
        }

        @JavascriptInterface
        fun authenticateBiometric(profileId: String) {
            runOnUiThread {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedPin = prefs.getString("bio_pin_$profileId", null) ?: prefs.getString("bio_pin_default", null)
                if (savedPin.isNullOrEmpty()) {
                    pushNativeLog("BIO-AUTH", "Для профиля $profileId отпечаток ещё не привязан")
                    webView.evaluateJavascript("if(window.__onBiometricAuthResult) window.__onBiometricAuthResult(false, '', 'Сначала привяжите отпечаток пальца в Настройки -> Приватность');", null)
                    return@runOnUiThread
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    pushNativeLog("BIO-ERR", "Android SDK < 28")
                    return@runOnUiThread
                }
                pushNativeLog("BIO-HW", "Запрос аппаратного сканера отпечатка для ВХОДА в профиль $profileId...")
                try {
                    val cancelSignal = CancellationSignal()
                    val prompt = BiometricPrompt.Builder(this@MainActivity)
                        .setTitle("Вход в Hydra")
                        .setSubtitle("Приложите палец к сканеру отпечатков")
                        .setNegativeButton("Ввести ПИН-код вручную", mainExecutor) { _, _ ->
                            pushNativeLog("BIO-HW", "Выбран ручной ввод ПИН-кода")
                            webView.evaluateJavascript("if(window.__onBiometricAuthResult) window.__onBiometricAuthResult(false, '', 'Выбран ручной ввод ПИН-кода');", null)
                        }
                        .build()

                    prompt.authenticate(cancelSignal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            super.onAuthenticationSucceeded(result)
                            pushNativeLog("BIO-OK", "Отпечаток пальца подтверждён аппаратно! Вход разрешён.")
                            val safePin = JSONObject.quote(savedPin)
                            webView.evaluateJavascript("if(window.__onBiometricAuthResult) window.__onBiometricAuthResult(true, $safePin, 'OK');", null)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            super.onAuthenticationError(errorCode, errString)
                            pushNativeLog("BIO-ERR", "Отмена/ошибка биометрии при входе (код $errorCode): $errString")
                            val safeErr = JSONObject.quote(errString?.toString() ?: "Отменено")
                            webView.evaluateJavascript("if(window.__onBiometricAuthResult) window.__onBiometricAuthResult(false, '', $safeErr);", null)
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            pushNativeLog("BIO-FAIL", "Неверный отпечаток пальца!")
                        }
                    })
                } catch (e: Exception) {
                    pushNativeLog("BIO-EXCEPTION", "Ошибка вызова сканера: ${e.message}")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Messages"
            val descriptionText = "New messages from contacts"
            val importance = android.app.NotificationManager.IMPORTANCE_HIGH
            val channel = android.app.NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                Thread {
                    try {
                        val path = copyFileToInternalStorage(uri)
                        pushNativeLog("FILE-PICKER", "Файл скопирован для Go: $path")
                        mobile.Mobile.onFileSelected(path)
                    } catch (e: Exception) {
                        pushNativeLog("FILE-ERR", "Ошибка копирования файла: ${e.message}")
                        mobile.Mobile.onFileSelected("")
                    }
                }.start()
            } else {
                mobile.Mobile.onFileSelected("")
            }
        } else {
            mobile.Mobile.onFileSelected("")
        }
    }

    private val webViewFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = webViewFileCallback
        webViewFileCallback = null
        if (cb == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val dataUri = result.data?.data
            if (dataUri != null) {
                pushNativeLog("WEB-FILE", "Выбран файл в Галерее: $dataUri")
                cb.onReceiveValue(arrayOf(dataUri))
                return@registerForActivityResult
            }
        }
        pushNativeLog("WEB-FILE", "Выбор файла отменён")
        cb.onReceiveValue(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = this

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.statusBarColor = android.graphics.Color.parseColor("#121212")
        window.navigationBarColor = android.graphics.Color.parseColor("#121212")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#121212"))
        }
        setContentView(webView)

        setupWebView()
        startGoService()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.RECORD_AUDIO)
            }
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.CAMERA)
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 101)
            }
        }

        mobile.Mobile.setPlatformBridge(this)
    }

    override fun pickFile() {
        runOnUiThread {
            try {
                pushNativeLog("GO-PICK", "Открытие системного выбора файла Android...")
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf", "text/*", "application/zip"))
                }
                isOpeningSystemPicker = true
                webView.evaluateJavascript("window.__hydraSkipLock = true;", null)
                filePickerLauncher.launch(intent)
            } catch (e: Exception) {
                pushNativeLog("GO-PICK-ERR", "Ошибка запуска пикера: ${e.message}")
                mobile.Mobile.onFileSelected("")
            }
        }
    }

    override fun shareFile(path: String) {
        runOnUiThread {
            try {
                val file = File(path)
                if (!file.exists()) return@runOnUiThread
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "com.teleghost.app.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Hydra Backup"))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to share file", e)
            }
        }
    }

    override fun clipboardSet(text: String) {
        runOnUiThread {
            try {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Hydra", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Clipboard set failed", e)
            }
        }
    }

    override fun showNotification(title: String, message: String) {
        runOnUiThread {
            try {
                val notifId = (System.currentTimeMillis() and 0xFFFFFFF).toInt()
                val senderNick = title.removePrefix("Hydra - ").trim()
                val remoteInput = androidx.core.app.RemoteInput.Builder("key_text_reply")
                    .setLabel("Быстрый ответ...")
                    .build()
                val replyIntent = Intent(this, DirectReplyReceiver::class.java).apply {
                    putExtra("sender_nick", senderNick)
                    putExtra("notif_id", notifId)
                }
                val replyFlags = if (Build.VERSION.SDK_INT >= 31) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
                val replyPendingIntent = android.app.PendingIntent.getBroadcast(this, notifId, replyIntent, replyFlags)
                val replyAction = androidx.core.app.NotificationCompat.Action.Builder(
                    R.mipmap.ic_launcher,
                    "Ответить",
                    replyPendingIntent
                ).addRemoteInput(remoteInput).build()

                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)

                val builder = androidx.core.app.NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                    .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .addAction(replyAction)

                with(androidx.core.app.NotificationManagerCompat.from(this)) {
                    notify(notifId, builder.build())
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Notification failed", e)
            }
        }
    }

    override fun saveFile(path: String, filename: String) {
        runOnUiThread {
            try {
                val file = File(path)
                if (!file.exists()) return@runOnUiThread
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    val mime = try {
                        val ext = MimeTypeMap.getFileExtensionFromUrl(filename)
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    } catch(e: Exception) { "application/octet-stream" }
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val resolver = applicationContext.contentResolver
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                } else {
                    resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), contentValues)
                }
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        java.io.FileInputStream(file).use { input ->
                            input.copyTo(output)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                    android.widget.Toast.makeText(this, "Сохранено в Загрузки", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Save file failed", e)
            }
        }
    }

    private fun copyFileToInternalStorage(uri: Uri): String {
        val contentResolver = applicationContext.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
        val filename = "upload_${UUID.randomUUID()}.$ext"
        val tempDir = File(filesDir, "tmp_uploads")
        if (!tempDir.exists()) tempDir.mkdirs()
        val destFile = File(tempDir, filename)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Cannot open input stream")
        return destFile.absolutePath
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.addJavascriptInterface(HydraNativeBridge(), "HydraNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith(SERVER_URL)) return false
                startActivity(Intent(Intent.ACTION_VIEW, request?.url))
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    view?.postDelayed({ waitForServerAndLoad() }, RETRY_DELAY_MS)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    pushNativeLog("WEB-PERM", "Выдача WebRTC доступа: ${request?.resources?.joinToString()}")
                    request?.grant(request.resources)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                webViewFileCallback?.onReceiveValue(null)
                webViewFileCallback = filePathCallback
                return try {
                    pushNativeLog("WEB-CHOOSER", "Открытие системной Галереи для выбора изображения...")
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    isOpeningSystemPicker = true
                        webView?.evaluateJavascript("window.__hydraSkipLock = true;", null)
                        webViewFileChooserLauncher.launch(Intent.createChooser(intent, "Выберите изображение или QR-код"))
                    true
                } catch (e: Exception) {
                    pushNativeLog("WEB-CHOOSER-ERR", "Ошибка открытия галереи: ${e.message}")
                    webViewFileCallback = null
                    false
                }
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                android.util.Log.d(TAG, "JS: ${msg?.message()} [${msg?.sourceId()}:${msg?.lineNumber()}]")
                return true
            }
        }
    }

    private fun startGoService() {
        val intent = Intent(this, TeleGhostService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        waitForServerAndLoad()
    }

    private fun waitForServerAndLoad(retryCount: Int = 0) {
        if (retryCount >= MAX_RETRIES) {
            runOnUiThread {
                webView.loadData(
                    "<html><body style='background:#121212;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;'><div style='text-align:center;'><h2>⚠️ Не удалось запустить сервер</h2><p>Попробуйте перезапустить приложение</p></div></body></html>",
                    "text/html", "UTF-8"
                )
            }
            return
        }

        Thread {
            try {
                val url = java.net.URL(HEALTH_URL)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 1000
                connection.readTimeout = 1000
                connection.requestMethod = "GET"
                val responseCode = connection.responseCode
                connection.disconnect()
                if (responseCode == 200) {
                    runOnUiThread {
                        webView.loadUrl(SERVER_URL)
                    }
                    return@Thread
                }
            } catch (e: Exception) {
                android.util.Log.d(TAG, "Health check failed: ${e.message}")
            }
            runOnUiThread {
                webView.postDelayed({ waitForServerAndLoad(retryCount + 1) }, RETRY_DELAY_MS)
            }
        }.start()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        mobile.Mobile.setPlatformBridge(this)
        if (isOpeningSystemPicker) {
            isOpeningSystemPicker = false
            pausedAtMs = 0L
            webView.evaluateJavascript("window.__setHydraBlur && window.__setHydraBlur(false); setTimeout(function(){ window.__hydraSkipLock = false; }, 1000);", null)
            pushNativeLog("LIFECYCLE", "Возврат из Галереи (без блокировки)")
            return
        }
        val awayMs = if (pausedAtMs > 0L) (System.currentTimeMillis() - pausedAtMs) else 0L
        pausedAtMs = 0L
        if (awayMs >= 30000L) {
            pushNativeLog("LIFECYCLE", "Отсутствие ${awayMs / 1000} сек (>= 30с) — запрос разблокировки!")
            webView.evaluateJavascript("if(window.__lockHydraNow) window.__lockHydraNow(); if(window.__onHydraAppResume) window.__onHydraAppResume();", null)
        } else {
            pushNativeLog("LIFECYCLE", "Быстрый возврат (${awayMs / 1000} сек < 30с) — сессия сохранена без блокировки")
            webView.evaluateJavascript("if(window.__setHydraBlur) window.__setHydraBlur(false);", null)
        }
    }

    override fun onPause() {
        if (!isOpeningSystemPicker) {
            pausedAtMs = System.currentTimeMillis()
            webView.evaluateJavascript("if(window.__setHydraBlur) window.__setHydraBlur(true);", null)
        }
        webView.onPause()
        super.onPause()
    }
}
