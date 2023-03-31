package kz.qbox.widget.webview.core.ui.presentation

import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Html
import android.text.Html.FROM_HTML_MODE_LEGACY
import android.text.method.LinkMovementMethod
import android.util.Rational
import android.view.*
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebView.*
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kz.garage.image.preview.ImagePreviewDialogFragment
import kz.garage.image.preview.showImagePreview
import kz.qbox.widget.webview.core.Logger
import kz.qbox.widget.webview.core.R
import kz.qbox.widget.webview.core.device.Provider
import kz.qbox.widget.webview.core.models.*
import kz.qbox.widget.webview.core.multimedia.receiver.DownloadStateReceiver
import kz.qbox.widget.webview.core.multimedia.selection.GetContentDelegate
import kz.qbox.widget.webview.core.multimedia.selection.GetContentResultContract
import kz.qbox.widget.webview.core.multimedia.selection.MimeType
import kz.qbox.widget.webview.core.multimedia.selection.StorageAccessFrameworkInteractor
import kz.qbox.widget.webview.core.ui.components.JSBridge
import kz.qbox.widget.webview.core.ui.components.ProgressView
import kz.qbox.widget.webview.core.ui.components.WebView
import kz.qbox.widget.webview.core.ui.dialogs.DownloadProgressDialog
import kz.qbox.widget.webview.core.ui.dialogs.showError
import kz.qbox.widget.webview.core.utils.*
import kz.qbox.widget.webview.core.utils.Constants.FILE_EXTENSIONS
import kz.qbox.widget.webview.core.utils.Constants.LOCATION_PERMISSIONS
import kz.qbox.widget.webview.core.utils.Constants.STORAGE_PERMISSIONS
import kz.qbox.widget.webview.core.utils.Constants.URL_SCHEMES
import org.json.JSONObject
import java.io.File
import java.util.*

private val TAG = WebViewActivity::class.java.simpleName

class WebViewActivity : AppCompatActivity(), WebView.Listener, JSBridge.Listener {

    companion object {
        fun newIntent(
            context: Context,
            flavor: Flavor,
            url: String,
            language: String?,
            call: Call?,
            user: User?,
            dynamicAttrs: DynamicAttrs?
        ): Intent = Intent(context, WebViewActivity::class.java)
            .putExtra("flavor", flavor)
            .putExtra("url", url)
            .putExtra("language", language)
            .putExtra("call", call)
            .putExtra("user", user)
            .putExtra("dynamic_attrs", dynamicAttrs)
    }

    private var toolbar: Toolbar? = null
    private var webView: WebView? = null
    private var progressView: ProgressView? = null

    private var progressDialog: DownloadProgressDialog? = null

    private var errorDialog: AlertDialog? = null

    private var interactor: StorageAccessFrameworkInteractor? = null

    /**
     * [DownloadManager] download ids list (which has downloading status)
     */
    private var pendingDownloads: MutableList<Pair<Long, String>>? = null

    /**
     * Files that already downloaded by [DownloadManager]
     */
    private var downloadedFiles: MutableList<Pair<String, Uri>>? = null

    private var downloadStateReceiver: DownloadStateReceiver? = null

    private var asynchronousTask: Thread? = null

    private val requestedPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Logger.debug(TAG, "requestedPermissionsLauncher() -> permissions: $permissions")

            webView?.setPermissionRequestResult(
                PermissionRequestMapper.fromAndroidToWebClient(permissions)
            )

            if (permissions.any { !it.value }) {
                showRequestPermissionsAlertDialog()
            }
        }

    private val locationPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Logger.debug(TAG, "locationPermissionsLauncher() -> permissions: $permissions")

            val isAllPermissionsGranted = permissions.all { it.value }

            webView?.setGeolocationPermissionsShowPromptResult(isAllPermissionsGranted)

            if (!isAllPermissionsGranted) {
                showRequestPermissionsAlertDialog()
            }
        }

    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Logger.debug(TAG, "locationSettingsLauncher() -> resultCode: ${result.resultCode}")

            onGeolocationPermissionsShowPrompt()
        }

    private val storagePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Logger.debug(TAG, "storagePermissionsLauncher() -> permissions: $permissions")

            val isAllPermissionsGranted = permissions.all { it.value }

            if (isAllPermissionsGranted) {
                onSelectFileRequest()
            } else {
                showRequestPermissionsAlertDialog()
            }
        }

    private val provider: Provider by lazy { Provider(applicationContext) }

    private val uri by lazy(LazyThreadSafetyMode.NONE) {
        try {
            Uri.parse(intent.getStringExtra("url"))
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalStateException()
        }
    }

    private val language by lazy(LazyThreadSafetyMode.NONE) {
        intent.getStringExtra("language") ?: Locale.getDefault().language
    }

    private val flavor by lazy(LazyThreadSafetyMode.NONE) {
        IntentCompat.getEnum<Flavor>(intent, "flavor") ?: throw IllegalStateException()
    }

    private val call by lazy(LazyThreadSafetyMode.NONE) {
        IntentCompat.getSerializable<Call>(intent, "call")
    }

    private val user by lazy(LazyThreadSafetyMode.NONE) {
        IntentCompat.getSerializable<User>(intent, "user")
    }

    private val dynamicAttrs by lazy(LazyThreadSafetyMode.NONE) {
        IntentCompat.getSerializable<DynamicAttrs>(intent, "dynamic_attrs")
    }

    private val jsBridge by lazy {
        JSBridge(
            device = Device(
                os = provider.os,
                osVersion = provider.osVersion,
                appVersion = provider.versionName,
                name = provider.name,
                mobileOperator = provider.operator,
                battery = Device.Battery(
                    percentage = provider.batteryPercent,
                    isCharging = provider.isPhoneCharging,
                    temperature = provider.batteryTemperature
                )
            ),
            call = call,
            user = user,
            dynamicAttrs = dynamicAttrs,
            listener = this
        )
    }

    private var callState: CallState? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.qbox_widget_activity_webview)

        toolbar = findViewById(R.id.toolbar)
        webView = findViewById(R.id.webView)
        progressView = findViewById(R.id.progressView)

        var uri = uri

        when (flavor) {
            Flavor.FULL_SUITE -> {
                uri = uri.buildUpon()
                    .apply {
                        if (!language.isNullOrBlank()) {
                            appendQueryParameter("lang", language)
                        }
                    }
                    .build()
            }
            Flavor.VIDEO_CALL -> {
                val call = call ?: throw NullPointerException("Call information is not provided!")

                uri = uri.buildUpon()
                    .apply {
                        if (!language.isNullOrBlank()) {
                            appendQueryParameter("lang", language)
                        }

                        appendQueryParameter("topic", call.topic)
                    }
                    .build()
            }
            else -> {
                throw IllegalStateException()
            }
        }

        setupActionBar()
        setupWebView()

        interactor = StorageAccessFrameworkInteractor(this) { result ->
            when (result) {
                is GetContentDelegate.Result.Success -> {
                    webView?.setFileSelectionPromptResult(result.uri)
                }
                is GetContentDelegate.Result.Error.NullableUri -> {
                    Toast.makeText(this, R.string.qbox_widget_error_basic, Toast.LENGTH_SHORT)
                        .show()
                    webView?.setFileSelectionPromptResult(uri = null)
                }
                is GetContentDelegate.Result.Error.SizeLimitExceeds -> {
                    Toast.makeText(
                        this,
                        getString(R.string.qbox_widget_error_files_exceeds_limit, result.maxSize),
                        Toast.LENGTH_SHORT
                    ).show()
                    webView?.setFileSelectionPromptResult(uri = null)
                }
                else -> {
                    Toast.makeText(this, R.string.qbox_widget_error_basic, Toast.LENGTH_SHORT)
                        .show()
                    webView?.setFileSelectionPromptResult(uri = null)
                }
            }
        }
        webView?.loadUrl(uri.toString())

    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val fragments = supportFragmentManager.fragments

        val imagePreviewDialogFragments =
            fragments.filterIsInstance<ImagePreviewDialogFragment>()
//        val videoPreviewDialogFragments =
//            fragments.filterIsInstance<VideoPreviewDialogFragment>()

        when {
            imagePreviewDialogFragments.isNotEmpty() -> {
                imagePreviewDialogFragments.forEach {
                    it.dismiss()
                    supportFragmentManager.fragments.remove(it)
                }
            }
//            videoPreviewDialogFragments.isNotEmpty() -> {
//                videoPreviewDialogFragments.forEach {
//                    it.dismiss()
//                    supportFragmentManager.fragments.remove(it)
//                }
//            }
            else -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.qbox_widget_alert_title_exit)
                    .setMessage(R.string.qbox_widget_alert_message_exit)
                    .setNegativeButton(R.string.qbox_widget_cancel) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setPositiveButton(R.string.qbox_widget_exit) { dialog, _ ->
                        dialog.dismiss()
                        super.onBackPressed()
                    }
                    .show()
            }
        }
    }

    override fun onStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (callState == CallState.START) {
                evaluateJS(JSONObject().apply { put("app_state", AppState.START.toString()) })
            }
        }
        super.onStart()
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (callState == CallState.START) {
                evaluateJS(JSONObject().apply { put("app_state", AppState.STOP.toString()) })
            }
        }
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.qbox_widget_webview, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.reload -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.qbox_widget_alert_title_reload)
                    .setMessage(R.string.qbox_widget_alert_message_reload)
                    .setNegativeButton(R.string.qbox_widget_cancel) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setPositiveButton(R.string.qbox_widget_reload) { dialog, _ ->
                        dialog.dismiss()
                        webView?.loadUrl("javascript:window.location.reload(true)")
                    }
                    .show()
                true
            }
            else ->
                super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        jsBridge.dispose()

        interactor?.dispose()
        interactor = null

        if (downloadStateReceiver != null) {
            try {
                unregisterReceiver(downloadStateReceiver)
            } catch (_: IllegalArgumentException) {
            }
            downloadStateReceiver = null
        }

        pendingDownloads?.clear()
        pendingDownloads = null

        downloadedFiles?.clear()
        downloadedFiles = null

        asynchronousTask = null

        super.onDestroy()

        webView?.destroy()
    }


    override fun onUserLeaveHint() {
        Logger.debug(TAG, "onUserLeaveHint()")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (callState == CallState.START) {
                if (!isInPictureInPictureMode) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        enterPictureInPictureMode(
                            PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(2, 3))
//                                .setAutoEnterEnabled(true)
                                .build()
                        )
                    } else {
                        enterPictureInPictureMode()
                    }
                }
            }
        }
        super.onUserLeaveHint()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        Logger.debug(TAG, "onPictureInPictureModeChanged() -> $isInPictureInPictureMode")
        if (isInPictureInPictureMode) {
            supportActionBar?.hide()
            evaluateJS(JSONObject().apply { put("app_event", AppEvent.PIP_ENTER.toString()) })
        } else {
            supportActionBar?.show()
            evaluateJS(JSONObject().apply { put("app_event", AppEvent.PIP_EXIT.toString()) })
        }

        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            finishAndRemoveTask()
        }
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private fun setupActionBar() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.transparent)

        setupActionBar(toolbar, isBackButtonEnabled = true) {
            onBackPressed()
        }
    }

    private fun setupWebView() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            webView?.settings?.let {
                WebSettingsCompat.setForceDark(
                    it,
                    WebSettingsCompat.FORCE_DARK_OFF
                )
            }
        }

        webView?.init()
        webView?.setupCookieManager()
        webView?.setMixedContentAllowed(true)
        webView?.setUrlListener { headers, uri ->
            Logger.debug(
                TAG,
                "setUrlListener() -> $headers, $uri, ${uri.scheme}, ${uri.path}, ${uri.encodedPath}, ${uri.authority}"
            )

            return@setUrlListener if (uri.toString().contains("image")) {
                showImagePreview(uri)
                true
            }
//            else if (uri.toString().contains("video")) {
//                VideoPreviewDialogFragment.show(
//                    fragmentManager = supportFragmentManager,
//                    uri = uri,
//                    caption = uri.toString()
//                )
//                true
//            }
            else resolveUri(uri)
        }

        webView?.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Logger.debug(
                TAG, "onDownloadStart() -> " +
                        "url: $url, " +
                        "userAgent: $userAgent, " +
                        "contentDisposition: $contentDisposition, " +
                        "mimetype: $mimetype, " +
                        "contentLength: $contentLength"
            )

            if (mimetype?.startsWith("image") == true &&
                (url.endsWith("png") ||
                        url.endsWith("jpg") ||
                        url.endsWith("jpeg"))
            ) {
                showImagePreview(Uri.parse(url))
                return@setDownloadListener
            }
//            else if (mimetype?.startsWith("video") == true &&
//                (url.endsWith("mp4") ||
//                        url.endsWith("avi") ||
//                        url.endsWith("mov") ||
//                        url.endsWith("3gp"))
//            ) {
//                VideoPreviewDialogFragment.show(
//                    fragmentManager = supportFragmentManager,
//                    uri = Uri.parse(url),
//                    caption = null
//                )
//                return@setDownloadListener
//            }

            if (pendingDownloads == null) {
                pendingDownloads = mutableListOf()
            }
            if (url in (pendingDownloads ?: mutableListOf()).map { it.second }) {
                Toast.makeText(
                    this,
                    R.string.qbox_widget_error_files_download_in_progress,
                    Toast.LENGTH_SHORT
                ).show()
                return@setDownloadListener
            }

            var isLocalFileFoundAndOpened = false
            val found = downloadedFiles?.find { it.first == url }
            if (found != null && !found.second.path.isNullOrBlank()) {
                val file = File(requireNotNull(found.second.path))
                Logger.debug(TAG, "file: $file")
                isLocalFileFoundAndOpened = openFile(file, mimetype, url)
            }

            if (isLocalFileFoundAndOpened) return@setDownloadListener

            val status = Environment.getExternalStorageState()
            if (status != Environment.MEDIA_MOUNTED) {
                return@setDownloadListener
            }

            val request = try {
                DownloadManager.Request(Uri.parse(url))
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                return@setDownloadListener
            }

            val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)

            val publicDirectory = Environment.DIRECTORY_DOWNLOADS
            val deviceDirectory = Environment.getExternalStorageDirectory()
            if (deviceDirectory.freeSpace / 1000000.0 <= 300.0) {
                val linkMessage = TextView(this).apply {
                    setPadding(65, 0, 65, 0)
                    setTextColor(Color.BLACK)
                    textSize = 15f
                    isClickable = true
                    movementMethod = LinkMovementMethod.getInstance()
                    text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        Html.fromHtml("<a href='$url'>$url</a>", FROM_HTML_MODE_LEGACY)
                    } else {
                        Html.fromHtml("<a href='$url'>$url</a>")
                    }
                }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.qbox_widget_attention))
                    .setMessage(getString(R.string.qbox_widget_alert_message_not_enough_space))
                    .setView(linkMessage)
                    .setPositiveButton(getString(R.string.qbox_widget_copy)) { dialog, _ ->
                        clipboardManager?.setPrimaryClip(ClipData.newPlainText("url", url))

                        Toast.makeText(
                            this,
                            getString(R.string.qbox_widget_toast_message_copied_to_clipboard),
                            Toast.LENGTH_SHORT
                        ).show()

                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.qbox_widget_cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()
                    .show()

                return@setDownloadListener
            }

            request.addRequestHeader("User-Agent", userAgent)
            request.allowScanningByMediaScanner()
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)
            request.setDescription(
                getString(
                    R.string.qbox_widget_label_files_download_in_progress,
                    filename
                )
            )
            request.setDestinationInExternalPublicDir(publicDirectory, filename)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                request.setRequiresCharging(false)
                request.setRequiresDeviceIdle(false)
            }
            request.setTitle(filename)

            downloadFile(request, url, filename)

            if (downloadStateReceiver != null) {
                try {
                    unregisterReceiver(downloadStateReceiver)
                } catch (_: IllegalArgumentException) {
                }
                downloadStateReceiver = null
            }
            downloadStateReceiver = DownloadStateReceiver { downloadId, uri, mimeType ->
                Logger.debug(
                    TAG,
                    "onFileUriReady() -> " +
                            "downloadId: $downloadId, " +
                            "uri: $uri," +
                            " mimeType: $mimeType"
                )

                pendingDownloads?.removeAll { it.first == downloadId }

                progressDialog?.dismiss()
                progressDialog = null

                val path = uri?.path
                if (!path.isNullOrBlank() && !mimeType.isNullOrBlank()) {
                    if (uri.scheme == "file") {
                        val file = File(path)

                        AlertDialog.Builder(this@WebViewActivity)
                            .setCancelable(true)
                            .setTitle(R.string.qbox_widget_alert_title_files_download_completed)
                            .setMessage(
                                getString(
                                    R.string.qbox_widget_alert_message_files_download_completed,
                                    file.name
                                )
                            )
                            .setNegativeButton(R.string.qbox_widget_no) { dialog, _ ->
                                dialog.dismiss()
                            }
                            .setPositiveButton(R.string.qbox_widget_open) { dialog, _ ->
                                dialog.dismiss()

                                // TODO: Handle file open issue
                                openFile(file, mimeType, url)
                            }
                            .show()

                        saveFile(url, uri)
                    }
                }
            }
            registerReceiver(
                downloadStateReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }

        webView?.addJavascriptInterface(jsBridge, "JSBridge")

        webView?.setListener(this)
    }

    private fun evaluateJS(jsonObject: JSONObject) {
        webView?.evaluateJavascript("window.postMessage('$jsonObject', '*');", null)
    }

    private fun resolveUri(uri: Uri): Boolean {
        Logger.debug(TAG, "resolveUri() -> ${this.uri}, $uri")
        if (this.uri == uri) return false
        if (this.uri.toString() in uri.toString()) return false

        URL_SCHEMES.forEach {
            if (uri.scheme?.let { uriScheme -> it.startsWith(uriScheme) } == true) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                    return true
                } catch (e: ActivityNotFoundException) {
                    Logger.error(TAG, "resolveUri() -> $uri, $e")
                }
            }
        }

//        SHORTEN_LINKS.forEach {
//            if (uri.authority?.equals(it) == true) {
//                val intent = Intent(Intent.ACTION_VIEW).apply {
//                    data = uri
//                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                }
//                try {
//                    startActivity(intent)
//                    return true
//                } catch (e: ActivityNotFoundException) {
//                    Logger.debug(TAG, "resolveUri() -> $uri, $e")
//                }
//            }
//        }

        if (FILE_EXTENSIONS.any { uri.path?.endsWith(it) == true }) return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Logger.error(TAG, "resolveUri() -> $uri, $e")
            false
        }
    }

    private fun showRequestPermissionsAlertDialog() {
        AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.qbox_widget_alert_title_permissions_require)
            .setMessage(R.string.qbox_widget_alert_message_permissions_require)
            .setPositiveButton(R.string.qbox_widget_go_to_settings) { dialog, _ ->
                dialog.dismiss()

                startActivity(
                    Intent().apply {
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .show()
    }

    private fun showGPSDisabledErrorAlertDialog() {
        AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.qbox_widget_alert_title_permissions_require_geolocation)
            .setMessage(R.string.qbox_widget_alert_message_permissions_require_geolocation)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()

                locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .show()
    }

    private fun downloadFile(
        downloadRequest: DownloadManager.Request,
        url: String,
        filename: String
    ) {
        // TODO: Handle DownloadManager absence issue (impossible case, but who knows)
        val downloadManager = downloadManager ?: return

        val id = downloadManager.enqueue(downloadRequest)

        if (pendingDownloads == null) {
            pendingDownloads = mutableListOf()
        }

        val found = pendingDownloads?.indexOfFirst { it.first == id }
        if (found == null || found < 0) {
            pendingDownloads?.add(id to url)
        } else {
            pendingDownloads?.set(found, id to url)
        }

        asynchronousTask = object : Thread() {
            override fun run() {
                var isDownloading = true

                while (isDownloading) {
                    val q = DownloadManager.Query()
                    q.setFilterById(id)
                    val cursor = downloadManager.query(q)
                    if (cursor.moveToFirst()) {
                        when (cursor.getIntOrDefault(DownloadManager.COLUMN_STATUS, 0)) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                isDownloading = false
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                if (cursor.getIntOrDefault(
                                        DownloadManager.COLUMN_REASON,
                                        0
                                    ) != DownloadManager.PAUSED_WAITING_TO_RETRY
                                ) {
                                    isDownloading = false

                                    runOnUiThread {
                                        errorDialog?.dismiss()
                                        errorDialog = null
                                        errorDialog = showError(
                                            url,
                                            getString(R.string.qbox_widget_alert_message_error_occurred)
                                        )
                                        errorDialog?.show()
                                    }
                                }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                isDownloading = false

                                runOnUiThread {
                                    errorDialog?.dismiss()
                                    errorDialog = null
                                    errorDialog = showError(
                                        url,
                                        getString(R.string.qbox_widget_alert_message_error_occurred)
                                    )
                                    errorDialog?.show()
                                }
                            }
                            else -> {
                                val bytesDownloaded = cursor.getIntOrDefault(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                                    0
                                )
                                val bytesTotal = cursor.getIntOrDefault(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                                    0
                                )
                                val progress = if (bytesTotal > 0) {
                                    (bytesDownloaded * 100.0 / bytesTotal)
                                } else {
                                    0.0
                                }

                                runOnUiThread {
                                    errorDialog?.dismiss()
                                    errorDialog = null

                                    if (progressDialog == null) {
                                        progressDialog = DownloadProgressDialog(
                                            context = this@WebViewActivity,
                                            cancelable = true,
                                            cancelListener = null,
                                            params = DownloadProgressDialog.Params(filename)
                                        )
                                        progressDialog?.show()
                                    }
                                    progressDialog?.progress = progress
                                }
                            }
                        }
                    }
                    cursor.close()
                }
            }
        }

        asynchronousTask?.start()

        Toast.makeText(this, R.string.qbox_widget_info_files_download_started, Toast.LENGTH_LONG)
            .show()
    }

    private fun saveFile(url: String, uri: Uri) {
        if (downloadedFiles == null) {
            downloadedFiles = mutableListOf()
        }
        val found = downloadedFiles?.indexOfFirst { it.first == url }
        if (found == null || found < 0) {
            downloadedFiles?.add(url to uri)
        } else {
            downloadedFiles?.set(found, url to uri)
        }
    }

    private fun openFile(file: File, mimeType: String, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        val contentUri = try {
            FileProvider.getUriForFile(
                applicationContext,
                "${packageName}.provider",
                file
            )
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()

            errorDialog?.dismiss()
            errorDialog = null
            errorDialog =
                showError(url = url, getString(R.string.qbox_widget_error_files_open_unable))
            errorDialog?.show()
            return false
        }

        grantUriPermission(packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        intent.setDataAndType(contentUri, mimeType)

        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            errorDialog?.dismiss()
            errorDialog = null
            errorDialog =
                showError(url = url, getString(R.string.qbox_widget_error_files_open_unable))
            errorDialog?.show()
            false
        }
    }

    /**
     * [JSBridge.Listener] implementation
     */

    override fun onClose(): Boolean {
        onBackPressed()
        return true
    }

    override fun onLanguageSet(language: String): Boolean {
        return false
    }

    override fun onCallState(state: CallState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            callState = state

            if (state == CallState.FINISH && isInPictureInPictureMode) {
                Toast.makeText(
                    this,
                    R.string.qbox_widget_alert_message_call_finished,
                    Toast.LENGTH_SHORT
                ).show()

                startActivity(
                    newIntent(
                        context = this,
                        flavor = flavor,
                        url = uri.toString(),
                        language = language,
                        call = call,
                        user = user,
                        dynamicAttrs = dynamicAttrs
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )

//                moveTaskToBack(true)
            }
        }
    }

    /**
     * [WebView.Listener] implementation
     */

    override fun onReceivedSSLError(handler: SslErrorHandler?, error: SslError?) {
    }

    override fun onPageLoadProgress(progress: Int) {
        if (progress < 95) {
            progressView?.show()
            progressView?.showTextView()
            progressView?.setText(getString(R.string.qbox_widget_label_widget_loading, progress))
        } else {
            progressView?.hide()
        }
    }

    override fun onSelectFileRequest(): Boolean {
        if (STORAGE_PERMISSIONS.all { isPermissionGranted(it) }) {
            AlertDialog.Builder(this)
                .setTitle(R.string.qbox_widget_alert_title_media_selection)
                .setItems(
                    arrayOf(
                        getString(R.string.qbox_widget_content_image),
                        getString(R.string.qbox_widget_content_video),
                        getString(R.string.qbox_widget_content_audio),
                        getString(R.string.qbox_widget_content_document)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> {
                            interactor?.launchSelection(GetContentResultContract.Params(MimeType.IMAGE))
                        }
                        1 -> {
                            interactor?.launchSelection(GetContentResultContract.Params(MimeType.VIDEO))
                        }
                        2 -> {
                            interactor?.launchSelection(GetContentResultContract.Params(MimeType.AUDIO))
                        }
                        3 -> {
                            interactor?.launchSelection(GetContentResultContract.Params(MimeType.DOCUMENT))
                        }
                    }
                }
                .setOnCancelListener {
                    webView?.setFileSelectionPromptResult(uri = null)
                }
                .show()
        } else {
            storagePermissionsLauncher.launch(STORAGE_PERMISSIONS)
        }

        return true
    }

    override fun onPermissionRequest(resources: Array<String>) {
        val permissions = PermissionRequestMapper.fromWebClientToAndroid(resources).toTypedArray()
        Logger.debug(TAG, "onPermissionRequest() -> resources: ${resources.contentToString()}")
        Logger.debug(TAG, "onPermissionRequest() -> permissions: ${permissions.contentToString()}")
        requestedPermissionsLauncher.launch(permissions)
    }

    override fun onPermissionRequestCanceled(resources: Array<String>) {
        Logger.debug(
            TAG,
            "onPermissionRequestCanceled() -> resources: ${resources.contentToString()}"
        )
    }

    override fun onGeolocationPermissionsShowPrompt() {
        Logger.debug(TAG, "onGeolocationPermissionsShowPrompt()")
        if (LOCATION_PERMISSIONS.all { isPermissionGranted(it) }) {
            val locationManager = locationManager
            if (locationManager == null) {
                showGPSDisabledErrorAlertDialog()
            } else {
                if (LocationManagerCompat.isLocationEnabled(locationManager)) {
                    webView?.setGeolocationPermissionsShowPromptResult(true)
                } else {
                    showGPSDisabledErrorAlertDialog()
                }
            }
        } else {
            locationPermissionsLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    override fun onGeolocationPermissionsHidePrompt() {
        Logger.debug(TAG, "onGeolocationPermissionsHidePrompt()")
    }

}