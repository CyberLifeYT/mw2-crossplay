package com.mw2.recomp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.InputDevice
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONArray

/**
 * The launcher: is the game installed, which driver will be used, and the
 * button that starts it. It runs in its own process, separate from the game
 * (see the manifest), so that ending a run -- which means killing the
 * game's process -- leaves this one standing.
 *
 * It is written so that it cannot fail to appear. The native library is not
 * needed to draw this screen and is loaded in a guarded step afterwards: a
 * library that will not load leaves a launcher that says so, with the log
 * and the last crash still reachable, rather than an app that vanishes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var footer: TextView
    private lateinit var play: Button
    private lateinit var installButton: Button
    private lateinit var directLaunchSwitch: androidx.appcompat.widget.SwitchCompat
    private var detectedIsoFile: File? = null
    private var nativeReady = false
    private var hasAutoLaunched = false
    private var pendingInstallId: Long = -1
    private var pendingUpdateUrl: String? = null

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != pendingInstallId) return
            pendingInstallId = -1
            installDownloadedApk(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        footer = findViewById(R.id.footer)
        play = findViewById(R.id.play)
        installButton = findViewById(R.id.install)
        directLaunchSwitch = findViewById(R.id.direct_launch_switch)

        directLaunchSwitch.isChecked = prefs.autoLaunch
        directLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoLaunch = isChecked
        }

        play.setOnClickListener {
            val gameDirectory = prefs.gameDirectory
            val installed = gameDirectory.isNotEmpty() && safely(false) {
                NativeBridge.nativeGameInstalled(gameDirectory)
            }
            if (installed) {
                startGame()
            } else if (detectedIsoFile != null) {
                val intent = Intent(this, InstallActivity::class.java).apply {
                    putExtra(InstallActivity.EXTRA_INITIAL_PATH, detectedIsoFile!!.absolutePath)
                    putExtra(InstallActivity.EXTRA_AUTO_START, true)
                }
                startActivity(intent)
            } else {
                startActivity(Intent(this, InstallActivity::class.java))
            }
        }
        installButton.setOnClickListener {
            startActivity(Intent(this, InstallActivity::class.java))
        }
        findViewById<Button>(R.id.driver).setOnClickListener {
            startActivity(Intent(this, DriverActivity::class.java))
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.log).setOnClickListener { showLog() }

        // Whatever killed the app last time, shown before anything else is
        // attempted -- it may well be about to happen again.
        val crash = Trail.lastCrash(this)
        if (crash.isNotEmpty()) showLastCrash(crash)

        // The library is loaded after the screen exists, so a failure here
        // is reported rather than fatal. The breadcrumb is what identifies a
        // native crash inside the load itself, which no handler can catch.
        Trail.note(this, "launcher: loading the runtime library")
        nativeReady = NativeBridge.load()
        if (nativeReady) {
            Trail.note(this, "launcher: library loaded, initialising")
            try {
                NativeBridge.nativeInit(
                    filesDir.absolutePath,
                    (getExternalFilesDir(null) ?: filesDir).absolutePath,
                    cacheDir.absolutePath,
                    applicationInfo.nativeLibraryDir,
                    null,
                )
            } catch (error: Throwable) {
                nativeReady = false
                NativeBridge.noteInitFailure(error.message ?: error.toString())
            }
        }
        Trail.clear(this)
        ContextCompat.registerReceiver(
            this, downloadDone,
            android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        checkForUpdates()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadDone)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Back from the install-permission screen with a download waiting.
        pendingUpdateUrl?.let { url ->
            pendingUpdateUrl = null
            if (packageManager.canRequestPackageInstalls()) startUpdateDownload(url)
        }
    }

    private fun refresh() {
        if (!nativeReady) {
            status.text = getString(
                R.string.status_library_missing,
                NativeBridge.loadError() ?: getString(R.string.unknown_reason),
            )
            play.isEnabled = false
            footer.text = getString(
                R.string.footer, device(), getString(R.string.driver_system),
                BuildConfig.BUILD_SHA, BuildConfig.BUILD_STAMP,
            )
            return
        }

        val gameDirectory = prefs.gameDirectory
        val installed = gameDirectory.isNotEmpty() && safely(false) {
            NativeBridge.nativeGameInstalled(gameDirectory)
        }

        if (installed) {
            play.isEnabled = true
            play.text = "▶ LAUNCH GAME"
            status.text = "Status: Game Ready\nLocation: $gameDirectory"
            installButton.visibility = android.view.View.GONE
            directLaunchSwitch.visibility = android.view.View.VISIBLE

            if (prefs.autoLaunch && !hasAutoLaunched) {
                hasAutoLaunched = true
                startGame()
                return
            }
        } else {
            // MP needs the FULL disc: mw2_campaign_lean.iso pruned the
            // multiplayer fastfiles to save 2.7 GB, so it can never satisfy
            // a multiplayer install. SP accepts either, preferring lean.
            val isMp = packageName.endsWith(".mp")
            val candidateIsos = if (isMp) {
                listOf(
                    File("/sdcard/Download/mw2.iso"),
                    File("/sdcard/Download/Call of Duty - Modern Warfare 2 (USA, Europe).iso"),
                    File(getExternalFilesDir(null) ?: filesDir, "mw2.iso"),
                    // Lean last: detected but installer will report missing MP zones.
                    File("/sdcard/Download/mw2_campaign_lean.iso"),
                )
            } else {
                listOf(
                    File("/sdcard/Download/mw2_campaign_lean.iso"),
                    File("/sdcard/Download/mw2.iso"),
                    File("/sdcard/Download/Call of Duty - Modern Warfare 2 (USA, Europe).iso"),
                    File(getExternalFilesDir(null) ?: filesDir, "mw2_campaign_lean.iso"),
                    File(getExternalFilesDir(null) ?: filesDir, "mw2.iso")
                )
            }
            val downloadFolder = File("/sdcard/Download")
            val anyDownloadIso = if (downloadFolder.exists() && downloadFolder.isDirectory) {
                downloadFolder.listFiles { f ->
                    f.isFile && f.name.endsWith(".iso", ignoreCase = true) && f.length() > 500_000_000L &&
                        // Lean ISO has no MP zones; don't offer it as fallback for MP.
                        (!isMp || !f.name.contains("lean", ignoreCase = true))
                }?.firstOrNull()
            } else null

            detectedIsoFile = candidateIsos.firstOrNull { it.exists() && it.length() > 500_000_000L } ?: anyDownloadIso

            if (detectedIsoFile != null) {
                play.isEnabled = true
                play.text = "⚡ INSTALL & PLAY"
                status.text = "Game Disc Detected:\n${detectedIsoFile!!.name}\nReady for one-click setup."
                installButton.visibility = android.view.View.VISIBLE
                installButton.text = "Choose Another Source"
            } else {
                play.isEnabled = false
                play.text = getString(R.string.play)
                status.text = getString(R.string.status_not_installed)
                installButton.visibility = android.view.View.VISIBLE
                installButton.text = getString(R.string.install_game)
            }
            directLaunchSwitch.visibility = android.view.View.GONE
        }

        applyDriverChoice()
        footer.text = getString(
            R.string.footer, device(), currentDriverName(),
            BuildConfig.BUILD_SHA, BuildConfig.BUILD_STAMP,
        )
    }

    private fun device(): String {
        val vkVersion = packageManager.systemAvailableFeatures
            .firstOrNull { it.name == "android.hardware.vulkan.version" }?.version ?: 0
        val vkMajor = vkVersion shr 22
        val vkMinor = (vkVersion shr 12) and 0x3ff
        val vkStr = if (vkVersion > 0) "Vulkan $vkMajor.$vkMinor" else "No Vulkan"
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} ($vkStr), " +
            "Android ${android.os.Build.VERSION.RELEASE}"
    }

    /** A native call that must not take the launcher with it if it fails. */
    private fun <T> safely(fallback: T, call: () -> T): T = try {
        call()
    } catch (_: Throwable) {
        fallback
    }

    private fun applyDriverChoice() {
        val directory = prefs.driverDirectory
        val library = prefs.driverLibrary
        if (directory.isNotEmpty() && library.isNotEmpty() && File(directory, library).exists()) {
            safely(Unit) { NativeBridge.nativeSelectDriver(directory, library) }
        } else {
            // A driver that was deleted from underneath the setting.
            if (directory.isNotEmpty()) {
                prefs.driverDirectory = ""
                prefs.driverLibrary = ""
            }
            safely(Unit) { NativeBridge.nativeSelectDriver("", "") }
        }
    }

    private fun currentDriverName(): String {
        val directory = prefs.driverDirectory
        if (directory.isEmpty()) return getString(R.string.driver_system)
        return File(directory).name
    }

    private fun startGame() {
        if (!nativeReady) return
        // A physical pad means a second player can join (split-screen Spec
        // Ops / multiplayer). Warn once: two viewports cost frames and heat.
        if (!prefs.seenPadWarning && physicalPadCount() > 0) {
            showSplitscreenWarning()
            return
        }
        GameActivity.start(this)
    }

    private fun physicalPadCount(): Int {
        var count = 0
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isVirtual) continue
            val sources = device.sources
            if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
                count++
            }
        }
        return count
    }

    private fun showSplitscreenWarning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.splitscreen_title)
            .setMessage(R.string.splitscreen_warning)
            .setPositiveButton(R.string.play_anyway) { _, _ -> GameActivity.start(this) }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.dont_show_again) { _, _ ->
                prefs.seenPadWarning = true
                GameActivity.start(this)
            }
            .show()
    }

    /** A newer published APK, if there is one: silent when offline. */
    private fun checkForUpdates() {
        thread(name = "update-check", isDaemon = true) {
            try {
                val connection = (URL("https://api.github.com/repos/CyberLifeYT/mw2-crossplay/releases?per_page=10")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (connection.responseCode != 200) return@thread
                val releases = JSONArray(connection.inputStream.bufferedReader().readText())
                val want = if (packageName.endsWith(".mp")) "multiplayer" else "campaign"
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    if (release.optBoolean("draft")) continue
                    val assets = release.optJSONArray("assets") ?: continue
                    val matches = (0 until assets.length()).any {
                        assets.getJSONObject(it).optString("name").contains(want, ignoreCase = true)
                    }
                    if (!matches) continue
                    val tag = release.optString("tag_name")
                    val code = Regex("APK versionCode:\\s*(\\d+)")
                        .find(release.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val installed = packageManager.getPackageInfo(packageName, 0).let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
                        else it.versionCode
                    }
                    // Newest matching release only: older ones stay buried.
                    if (code > installed && tag.isNotEmpty() && tag != prefs.seenUpdateTag) {
                        val page = release.optString("html_url")
                        val apkUrl = (0 until assets.length())
                            .map { assets.getJSONObject(it) }
                            .firstOrNull {
                                it.optString("name").endsWith(".apk", ignoreCase = true) &&
                                    it.optString("name").contains(want, ignoreCase = true)
                            }?.optString("browser_download_url") ?: ""
                        val notes = release.optString("body").take(1500)
                        runOnUiThread { showUpdateDialog(tag, page, apkUrl, notes, installed, code) }
                    }
                    return@thread
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun showUpdateDialog(tag: String, page: String, apkUrl: String, notes: String, installed: Int, code: Int) {
        val body = TextView(this)
        body.setPadding(32, 8, 32, 8)
        body.textSize = 12f
        body.text = getString(R.string.update_message, installed, code) + "\n\n" + notes
        val scroll = ScrollView(this)
        scroll.addView(body)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, tag))
            .setView(scroll)
            .setPositiveButton(R.string.update_download) { _, _ ->
                if (apkUrl.isNotEmpty()) downloadAndInstall(apkUrl)
                else openPage(page)
            }
            .setNeutralButton(R.string.skip_version) { _, _ -> prefs.seenUpdateTag = tag }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPage(page: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
        } catch (_: Throwable) {
        }
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            // System sends the user to allow installs from this app; the
            // download starts when they come back (onResume).
            pendingUpdateUrl = apkUrl
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Throwable) {
                pendingUpdateUrl = null
            }
            return
        }
        startUpdateDownload(apkUrl)
    }

    private fun startUpdateDownload(apkUrl: String) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("MW2 update")
                .setDescription("Downloading APK")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "mw2-update.apk")
                .setAllowedOverMetered(true)
            pendingInstallId = manager.enqueue(request)
            Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            openPage("https://github.com/CyberLifeYT/mw2-crossplay/releases")
        }
    }

    private fun installDownloadedApk(id: Long) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(id) ?: return
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            AlertDialog.Builder(this)
                .setMessage(R.string.update_failed)
                .setPositiveButton(R.string.open_page) { _, _ ->
                    openPage("https://github.com/CyberLifeYT/mw2-crossplay/releases")
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showLastCrash(crash: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.last_crash)
            .setMessage(crash.take(4000))
            .setPositiveButton(R.string.close) { _, _ -> Trail.forgetCrash(this) }
            .setNeutralButton(R.string.share) { _, _ -> share(crash) }
            .show()
    }

    private fun showLog() {
        val text = buildString {
            // The run's log first: when something goes wrong it is almost
            // always the interesting one, and the launcher's own is short.
            for (name in listOf("game.log", "launcher.log")) {
                val file = File(filesDir, name)
                if (!file.exists()) continue
                append("===== ").append(name).append(" =====\n")
                append(file.readTextTail(60_000)).append("\n\n")
            }
            for (name in listOf("last-crash-game.txt", "last-crash-launcher.txt")) {
                val file = File(filesDir, name)
                if (file.exists()) {
                    append("===== ").append(name).append(" =====\n")
                    append(file.readTextTail(8_000)).append("\n\n")
                }
            }
            if (nativeReady) {
                val tail = safely("") { NativeBridge.nativeLogTail() }
                if (tail.isNotBlank()) append("===== this session =====\n").append(tail)
            }
        }.ifBlank { getString(R.string.log_empty) }

        val body = TextView(this)
        body.setPadding(32, 24, 32, 24)
        body.textSize = 10f
        body.setTextIsSelectable(true)
        body.text = text
        val scroll = ScrollView(this)
        scroll.addView(body)
        AlertDialog.Builder(this)
            .setTitle(R.string.view_log)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.share) { _, _ -> share(text) }
            .show()
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, "MW2 log")
        intent.putExtra(Intent.EXTRA_TEXT, text.takeLast(200_000))
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}

/** The end of a file, which is the part of a log anyone wants. */
fun File.readTextTail(bytes: Int): String = try {
    val length = length()
    if (length <= bytes) readText()
    else inputStream().use { stream ->
        stream.skip(length - bytes)
        stream.readBytes().toString(Charsets.UTF_8)
    }
} catch (_: Throwable) {
    ""
}
