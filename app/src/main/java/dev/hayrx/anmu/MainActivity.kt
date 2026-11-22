package dev.hayrx.anmu

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.text.InputType
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/**
 * AnMu 0.4.0 UI. QemuVmController owns the native QEMU process and VNC connection;
 * this activity displays the framebuffer and forwards guest input.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("anmu_settings", Context.MODE_PRIVATE) }
    private val nativeEngine by lazy { NativeVmEngine() }
    private var selectedIsoUri: Uri? = null
    private var selectedIsoName: String = ""
    private var vmController: QemuVmController? = null
    private var vmStarting = false
    private var vmRunning = false
    private var typingDialogOpen = false
    private var guestDisplayView: GuestDisplayView? = null

    private lateinit var page: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var headerStatus: TextView

    private val accent = Color.rgb(113, 155, 255)
    private val background = Color.rgb(12, 15, 22)
    private val panel = Color.rgb(23, 29, 40)
    private val muted = Color.rgb(150, 162, 181)
    private val textColor = Color.rgb(241, 245, 252)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedIsoUri = prefs.getString(KEY_ISO_URI, null)?.let(Uri::parse)
        selectedIsoName = prefs.getString(KEY_ISO_NAME, "") ?: ""
        validateSavedIso()
        buildPage()
        renderContent()
    }

    private fun buildPage() {
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
            setBackgroundColor(this@MainActivity.background)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(14))
        }

        val title = label("AnMu", 25f, Color.WHITE, true)
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))

        headerStatus = label("READY", 11f, Color.rgb(151, 190, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(28, 47, 77), 20f)
        }
        header.addView(headerStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        contentFrame = FrameLayout(this)
        page.addView(header)
        page.addView(contentFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(page)
    }

    private fun renderContent() {
        contentFrame.removeAllViews()
        if (selectedIsoUri == null) {
            headerStatus.text = "NO ISO"
            headerStatus.setTextColor(Color.rgb(255, 202, 126))
            showEmptyState()
        } else {
            headerStatus.text = "ISO SELECTED"
            headerStatus.setTextColor(Color.rgb(151, 220, 177))
            showSelectedState()
        }
    }

    /**
     * Initial state: only the two requested actions are shown in the center.
     */
    private fun showEmptyState() {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // Keep the initial screen deliberately minimal: only Settings and Select ISO.
        val settingsButton = actionButton("Settings", false) { showSettings() }
        val isoButton = actionButton("Select ISO", true) { openIsoPicker() }

        group.addView(settingsButton, LinearLayout.LayoutParams(
            dp(240), dp(54)
        ).apply { bottomMargin = dp(12) })
        group.addView(isoButton, LinearLayout.LayoutParams(dp(240), dp(54)))

        contentFrame.addView(group, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /**
     * After an ISO is selected, the guest display takes the available upper area.
     * The keyboard and mouse panels remain docked at the bottom.
     */
    private fun showSelectedState() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val isoRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(3), dp(4), dp(10))
        }
        val isoInfo = label(selectedIsoName.ifBlank { "Selected ISO image" }, 13f, textColor, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        isoRow.addView(isoInfo, LinearLayout.LayoutParams(0, dp(38), 1f))
        val changeButton = smallButton("CHANGE") { openIsoPicker() }
        isoRow.addView(changeButton)
        layout.addView(isoRow)

        val display = GuestDisplayView(this)
        guestDisplayView = display
        layout.addView(display, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val inputDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(10))
        }
        inputDock.addView(buildKeyboardPanel(), LinearLayout.LayoutParams(0, dp(112), 1f).apply {
            marginEnd = dp(8)
        })
        inputDock.addView(buildMousePanel(), LinearLayout.LayoutParams(0, dp(112), 1f))
        layout.addView(inputDock)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lateinit var startButton: Button
        startButton = actionButton(if (vmRunning) "Stop OS" else "Start OS", true) {
            if (vmRunning || vmController?.isRunning == true) {
                stopVm(startButton)
            } else {
                startVm(display, startButton)
            }
        }
        startButton.isEnabled = !vmStarting
        val settingsButton = actionButton("Settings", false) { showSettings() }
        footer.addView(startButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginEnd = dp(8)
        })
        footer.addView(settingsButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        layout.addView(footer)

        contentFrame.addView(layout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun startVm(display: GuestDisplayView, button: Button) {
        val iso = selectedIsoUri ?: run {
            Toast.makeText(this, "Select an ISO image first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (vmStarting) return
        vmStarting = true
        button.isEnabled = false
        headerStatus.text = "STARTING"
        val ramMb = prefs.getInt(KEY_RAM_GB, 6).coerceIn(1, 6) * 1024
        val cores = prefs.getInt(KEY_CORES, 2).coerceAtLeast(1)
        val diskGb = prefs.getInt(KEY_DISK_GB, 25).coerceIn(20, 30)
        val arch = prefs.getString(KEY_GUEST_ARCH, "x86_64") ?: "x86_64"
        val controller = QemuVmController(
            applicationContext,
            onFrame = { bitmap -> runOnUiThread { display.updateFrame(bitmap) } },
            onStatus = { status ->
                runOnUiThread {
                    if (!isFinishing) {
                        headerStatus.text = status.uppercase().take(22)
                        if (status.startsWith("QEMU stopped")) {
                            vmRunning = false
                            vmStarting = false
                            vmController = null
                            button.text = "Start OS"
                            button.isEnabled = true
                        }
                    }
                }
            }
        )
        vmController = controller
        Thread({
            try {
                controller.start(iso, ramMb, cores, diskGb, arch)
                runOnUiThread {
                    vmStarting = false
                    vmRunning = true
                    button.text = "Stop OS"
                    button.isEnabled = true
                    headerStatus.text = "VM RUNNING"
                    Toast.makeText(this, "QEMU is running. The guest may still be booting.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                controller.stop()
                runOnUiThread {
                    vmController = null
                    vmStarting = false
                    vmRunning = false
                    button.text = "Start OS"
                    button.isEnabled = true
                    headerStatus.text = "START FAILED"
                    Toast.makeText(
                        this,
                        error.localizedMessage ?: "Could not start QEMU.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, "anmu-vm-start").start()
    }

    private fun stopVm(button: Button) {
        if (vmStarting) return
        val controller = vmController
        if (controller == null) {
            vmRunning = false
            button.text = "Start OS"
            return
        }
        button.isEnabled = false
        headerStatus.text = "STOPPING"
        Thread({
            controller.stop()
            runOnUiThread {
                vmController = null
                vmRunning = false
                vmStarting = false
                button.text = "Start OS"
                button.isEnabled = true
                guestDisplayView?.clearFrame()
                headerStatus.text = "ISO SELECTED"
            }
        }, "anmu-vm-stop").start()
    }

    private fun showTextInput() {
        val editor = EditText(this).apply {
            hint = "Type into the guest system"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Guest keyboard")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                vmController?.sendText(editor.text?.toString().orEmpty())
            }
            .create()
        typingDialogOpen = true
        dialog.setOnDismissListener { typingDialogOpen = false }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        editor.requestFocus()
        editor.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendSpecialKey(name: String) {
        val symbol = when (name) {
            "ESC" -> 0xff1b
            "TAB" -> 0xff09
            "CTRL" -> 0xffe3
            "ALT" -> 0xffe9
            else -> return
        }
        vmController?.sendKey(symbol, true)
        vmController?.sendKey(symbol, false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vmRunning && !typingDialogOpen) {
            val symbol = keySym(event)
            if (symbol != null && event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                vmController?.sendKey(symbol, event.action == KeyEvent.ACTION_DOWN)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun keySym(event: KeyEvent): Int? {
        val code = event.keyCode
        return when {
            code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                (if (event.isShiftPressed) 'A'.code else 'a'.code) + (code - KeyEvent.KEYCODE_A)
            code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (code - KeyEvent.KEYCODE_0)
            code == KeyEvent.KEYCODE_SPACE -> 0x20
            code == KeyEvent.KEYCODE_ENTER -> 0xff0d
            code == KeyEvent.KEYCODE_DEL -> 0xff08
            code == KeyEvent.KEYCODE_TAB -> 0xff09
            code == KeyEvent.KEYCODE_ESCAPE -> 0xff1b
            code == KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
            code == KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
            code == KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            code == KeyEvent.KEYCODE_HOME -> 0xff50
            code == KeyEvent.KEYCODE_MOVE_END -> 0xff57
            code == KeyEvent.KEYCODE_PAGE_UP -> 0xff55
            code == KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
            code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe1
            code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe3
            code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT -> 0xffe9
            code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xffbe + (code - KeyEvent.KEYCODE_F1)
            else -> event.unicodeChar.takeIf { it > 0 }?.let { if (it <= 0xff) it else 0x01000000 or it }
        }
    }

    private fun buildKeyboardPanel(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 16f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        card.addView(label("KEYBOARD", 10f, muted, true))
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("ESC", "TAB", "CTRL", "ALT", "TYPE").forEach { key ->
            val keyView = label(key, 9f, textColor, true).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(39, 48, 64), 7f)
                setOnClickListener {
                    if (key == "TYPE") showTextInput() else sendSpecialKey(key)
                }
            }
            keys.addView(keyView, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                if (key != "TYPE") marginEnd = dp(4)
                topMargin = dp(12)
            })
        }
        card.addView(keys)
        card.addView(label("Touch a key or use your device keyboard", 9f, muted).apply {
            setPadding(0, dp(8), 0, 0)
        })
        return card
    }

    private fun buildMousePanel(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 16f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        card.addView(label("MOUSE", 10f, muted, true))
        val touchArea = MousePadView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(7) }
        }
        card.addView(touchArea)
        return card
    }

    private fun openIsoPicker() {
        if (vmRunning || vmStarting) {
            Toast.makeText(this, "Stop the guest OS before changing its ISO.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_ISO)
        } catch (error: Exception) {
            Toast.makeText(this, "Could not open the system file picker: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Legacy activity result API is used to keep the app dependency-free.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ISO || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val name = queryDisplayName(uri)
        if (name.isNotBlank() && !name.lowercase().endsWith(".iso")) {
            Toast.makeText(this, "Please select an .iso image file.", Toast.LENGTH_LONG).show()
            return
        }

        val grantedFlags = (data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        try {
            contentResolver.takePersistableUriPermission(uri, grantedFlags)
        } catch (_: SecurityException) {
            // Some document providers grant only temporary access. The selected image is
            // still usable for the current process, but may need to be selected again later.
        }

        selectedIsoUri = uri
        selectedIsoName = name.ifBlank { "Selected ISO image" }
        prefs.edit()
            .putString(KEY_ISO_URI, uri.toString())
            .putString(KEY_ISO_NAME, selectedIsoName)
            .apply()
        renderContent()
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index).orEmpty() else ""
                } else ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun validateSavedIso() {
        val uri = selectedIsoUri ?: return
        val canRead = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
        if (!canRead) {
            selectedIsoUri = null
            selectedIsoName = ""
            prefs.edit().remove(KEY_ISO_URI).remove(KEY_ISO_NAME).apply()
        }
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(4))
        }

        val savedRam = prefs.getInt(KEY_RAM_GB, 6).coerceIn(1, 6)
        val savedDisk = prefs.getInt(KEY_DISK_GB, 25).coerceIn(20, 30)
        val savedArch = prefs.getString(KEY_GUEST_ARCH, "x86_64") ?: "x86_64"
        content.addView(label("Guest ISO architecture", 13f, muted, true).apply {
            setPadding(0, dp(14), 0, dp(4))
        })
        val archOptions = listOf("x86_64", "aarch64")
        val archSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, archOptions)
            setSelection(archOptions.indexOf(savedArch).coerceAtLeast(0))
        }
        content.addView(archSpinner)
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val savedCores = prefs.getInt(KEY_CORES, min(4, cpuCount)).coerceIn(1, cpuCount)

        val ramValue = label("", 14f, textColor, true)
        content.addView(label("RAM allocation", 13f, muted, true))
        val ramSeek = SeekBar(this).apply {
            max = 5
            progress = savedRam - 1
        }
        ramValue.text = "$savedRam GB"
        content.addView(ramValue)
        content.addView(ramSeek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ))
        ramSeek.setOnSeekBarChangeListener(simpleSeekListener { value ->
            ramValue.text = "$value GB"
        })

        val diskValue = label("$savedDisk GB", 14f, textColor, true)
        content.addView(label("Virtual disk size", 13f, muted, true).apply {
            setPadding(0, dp(14), 0, dp(2))
        })
        content.addView(diskValue)
        val diskSeek = SeekBar(this).apply {
            max = 10
            progress = savedDisk - 20
        }
        content.addView(diskSeek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ))
        diskSeek.setOnSeekBarChangeListener(simpleSeekListener { value ->
            diskValue.text = "$value GB"
        })

        content.addView(label("CPU cores", 13f, muted, true).apply {
            setPadding(0, dp(14), 0, dp(4))
        })
        val coreOptions = (1..cpuCount).map { "$it core" + if (it == 1) "" else "s" }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, coreOptions)
            setSelection(savedCores - 1)
        }
        content.addView(spinner)

        val memoryInfo = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
        val physicalGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val recommended = max(1, min(6, physicalGb.toInt() - 1))
        content.addView(label(
            "Device RAM: %.1f GB. Recommended VM setting: up to $recommended GB. " +
                "The final limit is checked again before QEMU starts. " +
                "A 20–30 GB virtual disk should be sparse and grow as data is written."
                    .format(physicalGb),
            11f, muted
        ).apply { setPadding(0, dp(14), 0, dp(8)) })

        AlertDialog.Builder(this)
            .setTitle("AnMu Settings")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val ram = ramSeek.progress + 1
                val disk = diskSeek.progress + 20
                val cores = spinner.selectedItemPosition + 1
                prefs.edit()
                    .putInt(KEY_RAM_GB, ram)
                    .putInt(KEY_DISK_GB, disk)
                    .putInt(KEY_CORES, cores)
                    .putString(KEY_GUEST_ARCH, archOptions[archSpinner.selectedItemPosition])
                    .apply()
                Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroy() {
        if (isFinishing) vmController?.stop()
        super.onDestroy()
    }

    private fun simpleSeekListener(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            onValue(progress + if (seekBar?.max == 10) 20 else 1)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun actionButton(text: String, primary: Boolean, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else textColor)
            background = rounded(
                if (primary) accent else Color.rgb(37, 45, 60),
                13f
            )
            stateListAnimator = null
            setOnClickListener { action() }
        }
    }

    private fun smallButton(text: String, action: () -> Unit): TextView {
        return label(text, 10f, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(Color.rgb(28, 41, 63), 9f)
            setOnClickListener { action() }
        }
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private inner class GuestDisplayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var frame: Bitmap? = null
        private val destination = RectF()

        init {
            isFocusable = true
            isClickable = true
        }

        fun updateFrame(bitmap: Bitmap) {
            val old = frame
            frame = bitmap
            if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
            invalidate()
        }

        fun clearFrame() {
            frame?.let { if (!it.isRecycled) it.recycle() }
            frame = null
            invalidate()
        }

        fun frameSize(): Pair<Int, Int>? = frame?.let { it.width to it.height }

        private fun imageBounds(bitmap: Bitmap): RectF {
            val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            return RectF(left, top, left + w, top + h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(4, 7, 12))
            val bitmap = frame
            if (bitmap != null && !bitmap.isRecycled) {
                destination.set(imageBounds(bitmap))
                canvas.drawBitmap(bitmap, null, destination, paint)
                return
            }
            paint.color = Color.rgb(23, 31, 46)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            val inset = dp(1).toFloat()
            canvas.drawRoundRect(
                RectF(inset, inset, width - inset, height - inset),
                dp(15).toFloat(), dp(15).toFloat(), paint
            )
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(116, 157, 244)
            paint.textSize = dp(18).toFloat()
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("ANMU DISPLAY", width / 2f, height / 2f - dp(18), paint)
            paint.color = Color.rgb(153, 165, 185)
            paint.textSize = dp(12).toFloat()
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("QEMU guest display will appear here", width / 2f, height / 2f + dp(12), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bitmap = frame ?: return true
            val bounds = imageBounds(bitmap)
            if (bounds.width() <= 0f || bounds.height() <= 0f) return true
            val x = (((event.x - bounds.left) / bounds.width()) * bitmap.width)
                .toInt().coerceIn(0, bitmap.width - 1)
            val y = (((event.y - bounds.top) / bounds.height()) * bitmap.height)
                .toInt().coerceIn(0, bitmap.height - 1)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    requestFocus()
                    vmController?.sendPointer(x, y, 1)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    vmController?.sendPointer(x, y, 1)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    vmController?.sendPointer(x, y, 0)
                    performClick()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private inner class MousePadView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pointerX = -1f
        private var pointerY = -1f

        init {
            background = rounded(Color.rgb(34, 43, 58), 10f)
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (pointerX >= 0 && pointerY >= 0) {
                paint.color = accent
                canvas.drawCircle(pointerX, pointerY, dp(5).toFloat(), paint)
            } else {
                paint.color = Color.rgb(132, 146, 168)
                paint.textSize = dp(10).toFloat()
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("TOUCHPAD", width / 2f, height / 2f + dp(4), paint)
            }
        }

        private fun forward(event: MotionEvent, mask: Int) {
            val size = vmController?.displaySize() ?: return
            val x = (event.x / width.coerceAtLeast(1) * size.first).toInt().coerceIn(0, size.first - 1)
            val y = (event.y / height.coerceAtLeast(1) * size.second).toInt().coerceIn(0, size.second - 1)
            vmController?.sendPointer(x, y, mask)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pointerX = event.x
                    pointerY = event.y
                    forward(event, 1)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    pointerX = event.x
                    pointerY = event.y
                    forward(event, 1)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    pointerX = event.x
                    pointerY = event.y
                    forward(event, 0)
                    invalidate()
                    performClick()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    companion object {
        private const val REQUEST_ISO = 4102
        private const val KEY_ISO_URI = "iso_uri"
        private const val KEY_ISO_NAME = "iso_name"
        private const val KEY_RAM_GB = "ram_gb"
        private const val KEY_DISK_GB = "disk_gb"
        private const val KEY_CORES = "cpu_cores"
        private const val KEY_GUEST_ARCH = "guest_arch"
    }
}
