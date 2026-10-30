package com.example

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.RingtoneManager
import android.net.VpnService
import android.os.Build
import android.webkit.WebResourceResponse
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import android.provider.Settings
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

  companion object {
    const val UNITY_GAME_ID = "6134087"
    const val REWARDED_PLACEMENT_ID = "Rewarded_Android"
    const val TAG = "EarnDaily_UnityAds"
  }

  var webViewInstance: WebView? = null
  private var isUnityInitialized = false
  private var isAdLoaded = false

  private var pendingVpnServer: String = "US - New York"
  private val vpnPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      startVpnServiceInternal(pendingVpnServer)
    } else {
      Toast.makeText(this, "VPN permission cancelled", Toast.LENGTH_SHORT).show()
      notifyVpnStatusToWeb(false, "", "")
    }
  }

  fun requestConnectVpn(server: String) {
    pendingVpnServer = if (server.isBlank()) "US - New York" else server
    val prepareIntent = VpnService.prepare(this)
    if (prepareIntent != null) {
      vpnPermissionLauncher.launch(prepareIntent)
    } else {
      startVpnServiceInternal(pendingVpnServer)
    }
  }

  private fun startVpnServiceInternal(server: String) {
    try {
      val intent = Intent(this, EarnDailyVpnService::class.java).apply {
        action = EarnDailyVpnService.ACTION_CONNECT
        putExtra(EarnDailyVpnService.EXTRA_SERVER, server)
      }
      startService(intent)
      val ip = EarnDailyVpnService.getIpForServer(server)
      notifyVpnStatusToWeb(true, server, ip)
      Toast.makeText(this, "Connected to $server (Free US VPN)", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
      Log.e(TAG, "Error starting VPN: ${e.message}")
      notifyVpnStatusToWeb(false, "", "")
    }
  }

  fun disconnectUSVpn() {
    try {
      val intent = Intent(this, EarnDailyVpnService::class.java).apply {
        action = EarnDailyVpnService.ACTION_DISCONNECT
      }
      startService(intent)
      notifyVpnStatusToWeb(false, "", "")
      Toast.makeText(this, "US VPN Disconnected", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping VPN: ${e.message}")
    }
  }

  fun notifyVpnStatusToWeb(connected: Boolean, server: String, ip: String) {
    webViewInstance?.post {
      webViewInstance?.evaluateJavascript(
        "window.onVpnStatusChanged && window.onVpnStatusChanged($connected, '$server', '$ip');",
        null
      )
    }
  }

  fun playNotificationSound() {
    try {
      val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
      ringtone?.play()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to play notification ringtone: ${e.message}")
    }
  }

  fun showNotification(title: String, message: String) {
    try {
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channelId = "earndaily_alerts"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
          channelId,
          "EarnDaily Alerts",
          NotificationManager.IMPORTANCE_HIGH
        ).apply {
          description = "EarnDaily alerts and updates"
          enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
      }

      val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
      val pendingIntent = PendingIntent.getActivity(
        this,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val builder = NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(message)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)

      notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    } catch (e: Exception) {
      Log.e(TAG, "Error posting notification: ${e.message}")
    }
  }

  fun downloadAndInstallUpdate(urlStr: String) {
    if (urlStr.isBlank()) {
      Toast.makeText(this, "अपडेट लिंक उपलब्ध नहीं है", Toast.LENGTH_SHORT).show()
      return
    }

    // If it is not a direct APK, open in system browser
    if (!urlStr.endsWith(".apk", ignoreCase = true) && !urlStr.contains(".apk?")) {
      try {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(browserIntent)
      } catch (e: Exception) {
        Toast.makeText(this, "अपडेट लिंक खोलने में त्रुटि: ${e.message}", Toast.LENGTH_SHORT).show()
      }
      return
    }

    // Direct APK link: download in background thread and launch package installer
    Thread {
      try {
        runOnUiThread {
          Toast.makeText(this, "नया अपडेट डाउनलोड हो रहा है...", Toast.LENGTH_SHORT).show()
        }
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.connect()

        val fileLength = connection.contentLength
        val outputFile = File(cacheDir, "earndaily_update.apk")
        if (outputFile.exists()) {
          outputFile.delete()
        }

        connection.inputStream.use { input ->
          FileOutputStream(outputFile).use { output ->
            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            var lastPercent = 0
            while (input.read(data).also { count = it } != -1) {
              total += count
              output.write(data, 0, count)
              if (fileLength > 0) {
                val percent = (total * 100 / fileLength).toInt()
                if (percent != lastPercent) {
                  lastPercent = percent
                  runOnUiThread {
                    webViewInstance?.evaluateJavascript("window.onUpdateDownloadProgress && window.onUpdateDownloadProgress($percent);", null)
                  }
                }
              }
            }
          }
        }

        runOnUiThread {
          webViewInstance?.evaluateJavascript("window.onUpdateDownloadProgress && window.onUpdateDownloadProgress(100);", null)
          installDownloadedApk(outputFile)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Download failed: ${e.message}")
        runOnUiThread {
          Toast.makeText(this, "डाउनलोड विफल, ब्राउज़र में खोला जा रहा है...", Toast.LENGTH_SHORT).show()
          try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(browserIntent)
          } catch (ex: Exception) {
            // ignore
          }
        }
      }
    }.start()
  }

  private var pendingApkFile: File? = null

  override fun onResume() {
    super.onResume()
    // Auto-resume pending APK installation once user grants unknown sources permission
    pendingApkFile?.let { apk ->
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
        val apkToInstall = apk
        pendingApkFile = null
        launchPackageInstaller(apkToInstall)
      }
    }
  }

  fun canInstallUnknownPackages(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  fun openInstallPermissionSettings() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent = Intent(
          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.parse("package:$packageName")
        ).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Cannot open install settings: ${e.message}")
    }
  }

  private fun installDownloadedApk(apkFile: File) {
    runOnUiThread {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        pendingApkFile = apkFile
        Toast.makeText(this, "इंस्टॉल करने के लिए 'Allow from this source' को चालू करें", Toast.LENGTH_LONG).show()
        openInstallPermissionSettings()
      } else {
        launchPackageInstaller(apkFile)
      }
    }
  }

  private fun launchPackageInstaller(apkFile: File) {
    try {
      val uri = FileProvider.getUriForFile(
        this,
        "${packageName}.fileprovider",
        apkFile
      )
      val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        putExtra(Intent.EXTRA_RETURN_RESULT, true)
      }
      startActivity(installIntent)
    } catch (e: Exception) {
      Log.e(TAG, "Install intent failed: ${e.message}")
      Toast.makeText(this, "इंस्टॉल करने में समस्या: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        val processName = Application.getProcessName()
        if (packageName != processName) {
          WebView.setDataDirectorySuffix(processName)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Process suffix: ${e.message}")
      }
    }

    // Initialize Unity Ads SDK with Game ID 6134087
    initializeUnityAds()

    setContent {
      EarnDailyApp(this)
    }
  }

  private fun initializeUnityAds() {
    try {
      UnityAds.initialize(
        applicationContext,
        UNITY_GAME_ID,
        false, // production / live mode
        object : IUnityAdsInitializationListener {
          override fun onInitializationComplete() {
            Log.d(TAG, "Unity Ads initialized successfully with Game ID: $UNITY_GAME_ID")
            isUnityInitialized = true
            loadRewardedAd()
          }

          override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError?, message: String?) {
            Log.w(TAG, "Unity Ads initialization failed: $message. Will fallback to web player.")
            isUnityInitialized = false
          }
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Exception initializing Unity Ads: ${e.message}")
    }
  }

  fun loadRewardedAd() {
    if (!isUnityInitialized) return
    try {
      UnityAds.load(
        REWARDED_PLACEMENT_ID,
        object : IUnityAdsLoadListener {
          override fun onUnityAdsAdLoaded(placementId: String?) {
            Log.d(TAG, "Unity Rewarded Ad loaded: $placementId")
            isAdLoaded = true
          }

          override fun onUnityAdsFailedToLoad(
            placementId: String?,
            error: UnityAds.UnityAdsLoadError?,
            message: String?
          ) {
            Log.w(TAG, "Unity Ad failed to load: $message")
            isAdLoaded = false
          }
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Exception loading Unity Ad: ${e.message}")
    }
  }

  fun showUnityRewardedAd(rewardAmount: Int, onComplete: (Boolean) -> Unit) {
    if (isUnityInitialized && isAdLoaded) {
      UnityAds.show(
        this,
        REWARDED_PLACEMENT_ID,
        UnityAdsShowOptions(),
        object : IUnityAdsShowListener {
          override fun onUnityAdsShowFailure(
            placementId: String?,
            error: UnityAds.UnityAdsShowError?,
            message: String?
          ) {
            Log.w(TAG, "Unity Ad show failure: $message")
            onComplete(false)
            loadRewardedAd()
          }

          override fun onUnityAdsShowStart(placementId: String?) {
            Log.d(TAG, "Unity Ad started: $placementId")
          }

          override fun onUnityAdsShowClick(placementId: String?) {
            Log.d(TAG, "Unity Ad clicked: $placementId")
          }

          override fun onUnityAdsShowComplete(
            placementId: String?,
            state: UnityAds.UnityAdsShowCompletionState?
          ) {
            Log.d(TAG, "Unity Ad completed with state: $state")
            val rewarded = state == UnityAds.UnityAdsShowCompletionState.COMPLETED
            onComplete(rewarded)
            loadRewardedAd()
          }
        }
      )
    } else {
      // Fallback: Notify caller that native ad wasn't ready so in-app web player runs smoothly
      onComplete(false)
      loadRewardedAd()
    }
  }
}

const val USER_APP_URL = "file:///android_asset/EarnDaily_Earndaily.html"

class WebAppInterface(
  private val activity: MainActivity,
  private val webViewProvider: () -> WebView?
) {
  @JavascriptInterface
  fun getUnityGameId(): String {
    return MainActivity.UNITY_GAME_ID
  }

  @JavascriptInterface
  fun showUnityAd(placementId: String, rewardAmount: Int) {
    Handler(Looper.getMainLooper()).post {
      activity.showUnityRewardedAd(rewardAmount) { success ->
        val webView = webViewProvider()
        webView?.post {
          if (success) {
            webView.evaluateJavascript("window.onUnityAdCompleted && window.onUnityAdCompleted($rewardAmount);", null)
            Toast.makeText(activity, "Unity Ad Completed! ₹$rewardAmount added!", Toast.LENGTH_SHORT).show()
          } else {
            // Signal webview to display the interactive Unity Web Player
            webView.evaluateJavascript("window.playUnityWebAd && window.playUnityWebAd('$placementId', $rewardAmount);", null)
          }
        }
      }
    }
  }

  @JavascriptInterface
  fun connectUSVpn(server: String) {
    Handler(Looper.getMainLooper()).post {
      activity.requestConnectVpn(server)
    }
  }

  @JavascriptInterface
  fun disconnectUSVpn() {
    Handler(Looper.getMainLooper()).post {
      activity.disconnectUSVpn()
    }
  }

  @JavascriptInterface
  fun isVpnConnected(): Boolean {
    return EarnDailyVpnService.isConnected
  }

  @JavascriptInterface
  fun getVpnServer(): String {
    return EarnDailyVpnService.currentServer
  }

  @JavascriptInterface
  fun getVpnIp(): String {
    return EarnDailyVpnService.currentIp
  }

  @JavascriptInterface
  fun playNotificationSound() {
    Handler(Looper.getMainLooper()).post {
      activity.playNotificationSound()
    }
  }

  @JavascriptInterface
  fun showSystemNotification(title: String, message: String) {
    Handler(Looper.getMainLooper()).post {
      activity.showNotification(title, message)
    }
  }

  @JavascriptInterface
  fun getInstalledVersionName(): String {
    return try {
      val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
      pInfo.versionName ?: "1.1"
    } catch (e: Exception) {
      "1.1"
    }
  }

  @JavascriptInterface
  fun getInstalledVersionCode(): Int {
    return try {
      val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pInfo.longVersionCode.toInt()
      } else {
        @Suppress("DEPRECATION")
        pInfo.versionCode
      }
    } catch (e: Exception) {
      1
    }
  }

  @JavascriptInterface
  fun downloadAndInstallUpdate(url: String) {
    Handler(Looper.getMainLooper()).post {
      activity.downloadAndInstallUpdate(url)
    }
  }

  @JavascriptInterface
  fun canInstallPackages(): Boolean {
    return activity.canInstallUnknownPackages()
  }

  @JavascriptInterface
  fun openInstallPermissionSettings() {
    Handler(Looper.getMainLooper()).post {
      activity.openInstallPermissionSettings()
    }
  }

  @JavascriptInterface
  fun shareApp(text: String, title: String) {
    Handler(Looper.getMainLooper()).post {
      try {
        val intent = Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_TEXT, text)
          putExtra(Intent.EXTRA_SUBJECT, title)
        }
        activity.startActivity(Intent.createChooser(intent, title))
      } catch (e: Exception) {
        Log.e("WebAppInterface", "Failed to share: ${e.message}")
      }
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EarnDailyApp(activity: MainActivity? = null) {
  var webViewInstance by remember { mutableStateOf<WebView?>(null) }
  var canGoBack by remember { mutableStateOf(false) }

  BackHandler(enabled = canGoBack) {
    webViewInstance?.let { webView ->
      if (webView.canGoBack()) {
        webView.goBack()
      }
    }
  }

  Surface(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing),
    color = Color(0xFF0D0F14)
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      AndroidView(
        factory = { context ->
          WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#0D0F14"))
            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              databaseEnabled = true
              allowFileAccess = true
              allowContentAccess = true
              allowFileAccessFromFileURLs = true
              allowUniversalAccessFromFileURLs = true
              mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
              javaScriptCanOpenWindowsAutomatically = true
              cacheMode = WebSettings.LOAD_DEFAULT
              useWideViewPort = false
              loadWithOverviewMode = false
              textZoom = 100
              mediaPlaybackRequiresUserGesture = false
              builtInZoomControls = false
              displayZoomControls = false
            }

            webChromeClient = object : WebChromeClient() {
              override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("EarnDaily_JS", "${consoleMessage?.message()} [${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}]")
                return true
              }
            }

            // Register Unity Ads Bridge
            activity?.let { act ->
              addJavascriptInterface(WebAppInterface(act) { webViewInstance }, "Android")
            }

            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
              ): Boolean {
                val url = request?.url?.toString() ?: ""
                if (url.startsWith("file:///android_asset/")) {
                  return false
                }
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.contains("wa.me") || url.contains("t.me")) {
                  try {
                    val intent = Intent(Intent.ACTION_VIEW, request?.url)
                    context.startActivity(intent)
                    return true
                  } catch (e: Exception) {
                    Log.w("EarnDaily", "Failed to start intent for: $url")
                  }
                }
                return false
              }

              override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
              ): WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                if (url.endsWith("favicon.ico") || url.contains("variations")) {
                  return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    ByteArrayInputStream(ByteArray(0))
                  )
                }
                return super.shouldInterceptRequest(view, request)
              }

              override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
              ) {
                super.onReceivedError(view, request, error)
                Log.w("EarnDaily_Web", "WebView Error: ${error?.description} for ${request?.url}")
              }

              override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                canGoBack = view?.canGoBack() ?: false
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                canGoBack = view?.canGoBack() ?: false
              }
            }

            loadUrl(USER_APP_URL)
            webViewInstance = this
            activity?.webViewInstance = this
          }
        },
        update = { webView ->
          if (webView.url.isNullOrEmpty()) {
            webView.loadUrl(USER_APP_URL)
          }
        },
        modifier = Modifier
          .fillMaxSize()
          .testTag("earndaily_webview")
      )
    }
  }
}
