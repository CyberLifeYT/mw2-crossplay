package com.mw2.recomp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import java.io.File
import kotlin.math.roundToInt

/**
 * Everything the player can change outside a run. Each setting is written
 * the moment it is touched: there is no "apply", because there is nothing
 * to apply to -- the values are read when a run starts.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "mw2.tab"
        const val TAB_GRAPHICS = 0
        const val TAB_CONTROLS = 1
        const val TAB_NETWORK = 2
        const val TAB_FILES = 3
    }

    private lateinit var prefs: Prefs
    private val tabButtons = mutableListOf<Button>()
    private val tabPages = mutableListOf<android.view.View>()

    private val modBrowse = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_PATH)
            ?: return@registerForActivityResult
        installModFolder(File(path))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        title = getString(R.string.settings)

        // ---- tab bar (plain views, no new deps): one page visible at a time ----
        tabButtons.add(findViewById(R.id.tab_graphics))
        tabButtons.add(findViewById(R.id.tab_controls))
        tabButtons.add(findViewById(R.id.tab_network))
        tabButtons.add(findViewById(R.id.tab_files))
        tabPages.add(findViewById(R.id.page_graphics))
        tabPages.add(findViewById(R.id.page_controls))
        tabPages.add(findViewById(R.id.page_network))
        tabPages.add(findViewById(R.id.page_files))
        tabButtons.forEachIndexed { index, button ->
            button.setOnClickListener { selectTab(index) }
        }
        selectTab(intent.getIntExtra(EXTRA_TAB, TAB_GRAPHICS).coerceIn(0, 3))

        // ---- picture ----
        val resolutionLabel: TextView = findViewById(R.id.resolution_label)
        val resolution: SeekBar = findViewById(R.id.resolution)
        resolution.max = 65                       // 35% to 100%
        resolution.progress = ((prefs.resolutionScale * 100f) - 35f).roundToInt().coerceIn(0, 65)
        resolutionLabel.text = getString(R.string.resolution, resolution.progress + 35)
        resolution.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                resolutionLabel.text = getString(R.string.resolution, value + 35)
                if (fromUser) prefs.resolutionScale = (value + 35) / 100f
            }
        })

        val renderScaleLabel: TextView = findViewById(R.id.render_scale_label)
        val renderScale: SeekBar = findViewById(R.id.render_scale)
        renderScale.max = 2 // 0 = 1x, 1 = 2x, 2 = 3x
        renderScale.progress = (prefs.renderScale - 1).coerceIn(0, 2)
        renderScaleLabel.text = getString(R.string.render_scale, renderScale.progress + 1)
        renderScale.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                renderScaleLabel.text = getString(R.string.render_scale, value + 1)
                if (fromUser) prefs.renderScale = value + 1
            }
        })

        switch(R.id.msaa, prefs.multisampling) { prefs.multisampling = it }
        switch(R.id.prefer60, prefs.prefer60Hz) { prefs.prefer60Hz = it }
        switch(R.id.prewarm_all, prefs.prewarmAllShaders) { prefs.prewarmAllShaders = it }

        val fovLabel: TextView = findViewById(R.id.fov_label)
        val fovSlider: SeekBar = findViewById(R.id.fov_slider)
        fovSlider.max = 45 // 65 to 110
        fovSlider.progress = (prefs.fov - 65).coerceIn(0, 45)
        fovLabel.text = getString(R.string.fov, fovSlider.progress + 65)
        fovSlider.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                fovLabel.text = getString(R.string.fov, value + 65)
                if (fromUser) prefs.fov = value + 65
            }
        })

        switch(R.id.stats, prefs.showStats) { prefs.showStats = it }

        val textureLabel: TextView = findViewById(R.id.texture_label)
        val texture: SeekBar = findViewById(R.id.texture_budget)
        // 0 means "let the runtime decide"; then 128 MB steps to 2 GB.
        texture.max = 15
        texture.progress = if (prefs.textureBudgetMB <= 0) 0
        else (prefs.textureBudgetMB / 128).coerceIn(1, 15)
        textureLabel.text = textureText(texture.progress)
        texture.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                textureLabel.text = textureText(value)
                if (fromUser) prefs.textureBudgetMB = if (value == 0) 0 else value * 128
            }
        })

        // ---- sound ----
        switch(R.id.audio, prefs.audioEnabled) { prefs.audioEnabled = it }

        // ---- controls ----
        switch(R.id.touch, prefs.touchControls) { prefs.touchControls = it }
        switch(R.id.gamepad, prefs.gamepadEnabled) { prefs.gamepadEnabled = it }
        switch(R.id.lan_accept, prefs.lanAccept) { prefs.lanAccept = it }
        val lanIface: EditText = findViewById(R.id.lan_iface)
        lanIface.setText(prefs.lanIface)
        lanIface.doAfterTextChanged { prefs.lanIface = it.toString() }
        val playerName: EditText = findViewById(R.id.player_name)
        playerName.setText(prefs.playerName)
        playerName.doAfterTextChanged { prefs.playerName = it.toString() }
        switch(R.id.vibration, prefs.vibration) { prefs.vibration = it }
        switch(R.id.invert, prefs.invertLook) { prefs.invertLook = it }

        slider(
            R.id.look_x, R.id.look_x_label, prefs.lookSensitivityX,
            { getString(R.string.look_x, it) }, { prefs.lookSensitivityX = it },
        )
        slider(
            R.id.look_y, R.id.look_y_label, prefs.lookSensitivityY,
            { getString(R.string.look_y, it) }, { prefs.lookSensitivityY = it },
        )

        val smoothingLabel: TextView = findViewById(R.id.smoothing_label)
        val smoothing: SeekBar = findViewById(R.id.smoothing)
        smoothing.max = 90
        smoothing.progress = (prefs.lookSmoothing * 100f).roundToInt().coerceIn(0, 90)
        smoothingLabel.text = getString(R.string.smoothing, smoothing.progress)
        smoothing.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                smoothingLabel.text = getString(R.string.smoothing, value)
                if (fromUser) prefs.lookSmoothing = value / 100f
            }
        })

        // ---- the game files ----
        val paths: TextView = findViewById(R.id.paths)
        paths.text = if (prefs.gameDirectory.isEmpty()) getString(R.string.paths_none)
        else getString(R.string.paths_line, prefs.gameDirectory)

        findViewById<Button>(R.id.forget).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.forget_install)
                .setMessage(R.string.forget_explained)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset) { _, _ ->
                    prefs.gameDirectory = ""
                    paths.text = getString(R.string.paths_none)
                }
                .show()
        }

        // ---- storage ----
        val storage: TextView = findViewById(R.id.storage)
        refreshStorage(storage)
        findViewById<Button>(R.id.clear_shaders).setOnClickListener {
            shaderCacheFiles().forEach { try { it.delete() } catch (_: Throwable) {} }
            storage.text = getString(R.string.shaders_cleared)
            refreshStorage(storage)
        }
        findViewById<Button>(R.id.clear_logs).setOnClickListener {
            listOf("game.log", "launcher.log", "last-crash-game.txt", "last-crash-launcher.txt").forEach {
                try {
                    java.io.File(filesDir, it).delete()
                } catch (_: Throwable) {
                }
            }
            storage.text = getString(R.string.logs_cleared)
            refreshStorage(storage)
        }
        findViewById<Button>(R.id.install_mods).setOnClickListener {
            modBrowse.launch(Intent(this, FileBrowserActivity::class.java))
        }
    }

    /** Custom tab bar: black-bar highlight on the chosen tab, one page shown. */
    private fun selectTab(index: Int) {
        tabPages.forEachIndexed { i, page ->
            page.visibility = if (i == index) android.view.View.VISIBLE else android.view.View.GONE
        }
        tabButtons.forEachIndexed { i, button ->
            if (i == index) {
                button.setBackgroundResource(R.drawable.bg_entry_selected)
                button.setTextColor(0xFFE8B234.toInt())
            } else {
                button.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                button.setTextColor(0xFFE8EAED.toInt())
            }
        }
    }

    /** Extra maps/weapons/modes from an extracted mod folder into game/. */
    private fun installModFolder(folder: File) {
        Thread {
            val text = try {
                val gameDir = prefs.gameDirectory.ifEmpty {
                    (getExternalFilesDir(null) ?: filesDir).absolutePath + "/game"
                }
                val game = File(gameDir)
                if (!folder.isDirectory) throw IllegalStateException(folder.name)
                game.mkdirs()
                val copied = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                folder.listFiles()
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { file ->
                        if (!file.isFile) return@forEach
                        val lower = file.name.lowercase()
                        val loadable = lower.endsWith(".ff") || lower.endsWith(".pak") || lower.endsWith(".bik")
                        if (!loadable) {
                            val why =
                                if (lower.endsWith(".xex") || lower.endsWith(".exe") ||
                                    lower.endsWith(".dll") || lower.endsWith(".so")
                                ) "executables are never taken from a mod"
                                else "needs the CoD Xe plugin, which this port does not run"
                            skipped.add("${file.name} ($why)")
                            return@forEach
                        }
                        val to = File(game, file.name)
                        if (to.isFile && to.length() == file.length()) return@forEach
                        val part = File(game, file.name + ".part")
                        try {
                            file.inputStream().use { input ->
                                part.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (!part.renameTo(to)) throw IllegalStateException("write failed")
                            copied.add(file.name)
                        } catch (_: Throwable) {
                            try {
                                part.delete()
                            } catch (_: Throwable) {
                            }
                            skipped.add("${file.name} (could not be written)")
                        }
                    }
                if (copied.isEmpty() && skipped.isEmpty()) getString(R.string.mods_nothing)
                else getString(
                    R.string.mods_installed, copied.size,
                    if (skipped.isEmpty()) "" else getString(
                        R.string.mods_skipped, skipped.joinToString("\n"),
                    ),
                ) + if (copied.isNotEmpty()) "\n\n" + copied.joinToString("\n") else ""
            } catch (e: Throwable) {
                getString(R.string.install_failed, e.message ?: "")
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.install_mods)
                    .setMessage(text)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }.start()
    }

    private fun gameBase(): java.io.File = getExternalFilesDir(null) ?: filesDir

    private fun shaderCacheFiles(): List<java.io.File> {
        val base = gameBase()
        return listOf(
            java.io.File(base, "shader_cache.bin"),
            java.io.File(filesDir, "shader_cache.bin"),
            java.io.File(filesDir, "pipeline.cache"),
        )
    }

    private fun refreshStorage(view: TextView) {        view.text = getString(R.string.storage_computing)
        Thread {
            fun sizeOf(dir: java.io.File): Long = try {
                if (dir.isFile) dir.length()
                else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } catch (_: Throwable) {
                0L
            }
            val gameDir = prefs.gameDirectory.ifEmpty { null }?.let { java.io.File(it) }
            val base = gameBase()
            val saves = java.io.File(
                (gameDir?.parent ?: base.absolutePath) + "/saves",
            )
            val rows = listOf(
                getString(R.string.install_game) to (gameDir?.let { sizeOf(it) } ?: 0L),
                getString(R.string.saves_files) to sizeOf(saves),
                getString(R.string.clear_shaders) to shaderCacheFiles().sumOf {
                    try {
                        it.length()
                    } catch (_: Throwable) {
                        0L
                    }
                },
                getString(R.string.view_log) to listOf(
                    "game.log", "launcher.log", "last-crash-game.txt", "last-crash-launcher.txt",
                ).sumOf {
                    try {
                        java.io.File(filesDir, it).length()
                    } catch (_: Throwable) {
                        0L
                    }
                },
            )
            val text = rows.joinToString("\n") { (label, bytes) ->
                getString(R.string.storage_line, label, formatSize(bytes))
            }
            runOnUiThread { view.text = text }
        }.start()
    }

    private fun formatSize(bytes: Long): String =
        android.text.format.Formatter.formatFileSize(this, bytes)

    private fun textureText(step: Int): String =
        if (step == 0) getString(R.string.texture_budget_auto)
        else getString(R.string.texture_budget, step * 128)

    private fun switch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val view: SwitchCompat = findViewById(id)
        view.isChecked = initial
        view.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    /** A 0.2 to 4.0 slider with a label that follows it. */
    private fun slider(
        sliderId: Int,
        labelId: Int,
        initial: Float,
        label: (Float) -> String,
        onChange: (Float) -> Unit,
    ) {
        val text: TextView = findViewById(labelId)
        val bar: SeekBar = findViewById(sliderId)
        bar.max = 38                                     // 0.2 .. 4.0 in tenths
        bar.progress = ((initial - 0.2f) * 10f).roundToInt().coerceIn(0, 38)
        text.text = label(initial)
        bar.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                val amount = 0.2f + value / 10f
                text.text = label(amount)
                if (fromUser) onChange(amount)
            }
        })
    }

    /** The two methods nobody ever needs, out of the way. */
    private abstract class Simple : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }
}
