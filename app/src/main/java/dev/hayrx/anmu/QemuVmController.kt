package dev.hayrx.anmu

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one QEMU process and its loopback VNC display.
 *
 * QEMU is an AArch64 Android executable. It can emulate AArch64 or x86_64
 * guests using TCG; no KVM/root access is assumed.
 */
class QemuVmController(
    private val context: Context,
    private val onFrame: (android.graphics.Bitmap) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var process: Process? = null
    @Volatile private var vncClient: VncClient? = null
    @Volatile private var launchThread: Thread? = null
    @Volatile private var exitCode: Int? = null
    @Volatile private var lastLaunchError: Throwable? = null
    @Volatile private var stopRequested = false

    val isRunning: Boolean
        get() = process?.isAlive == true

    fun start(
        isoUri: Uri,
        requestedRamMb: Int,
        requestedCpus: Int,
        diskGb: Int,
        guestArchitecture: String
    ) {
        check(!isRunning) { "A virtual machine is already running." }
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            throw IOException("This QEMU runtime currently requires an ARM64 Android device.")
        }
        val arch = guestArchitecture.lowercase()
        if (arch != "aarch64" && arch != "x86_64") {
            throw IOException("Unsupported guest architecture: $guestArchitecture")
        }

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val qemu = File(nativeDir, "libqemu-system-$arch.so")
        if (!qemu.isFile) throw IOException("QEMU for $arch is missing from this APK.")
        val launcher = File(nativeDir, "libpodroid-launcher.so")
        val slirp = File(nativeDir, "libslirp.so")
        if (!slirp.isFile) throw IOException("QEMU network library libslirp.so is missing.")

        onStatus("Preparing QEMU firmware...")
        val firmwareDir = File(context.filesDir, "qemu")
        copyAssetTree("qemu", firmwareDir)
        if (arch == "aarch64") {
            val efi = File(firmwareDir, "QEMU_EFI.fd")
            if (!efi.isFile) {
                throw IOException("AArch64 UEFI firmware is missing. Rebuild the QEMU runtime with the EDK2 firmware step.")
            }
        }

        onStatus("Copying ISO into app storage...")
        val iso = copyIsoToPrivateStorage(isoUri)
        onStatus("Preparing sparse virtual disk...")
        val disk = prepareSparseDisk(diskGb.coerceIn(20, 30), arch)
        if (stopRequested) throw IOException("VM startup was cancelled.")

        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val reserveMb = 1024L
        val availableAfterReserveMb = (memory.availMem / (1024L * 1024L)) - reserveMb
        val halfPhysicalRamMb = (memory.totalMem / (1024L * 1024L) / 2L)
        val safeRamMb = minOf(availableAfterReserveMb, halfPhysicalRamMb).toInt()
        if (safeRamMb < 1024) {
            throw IOException("Not enough free RAM to safely start a 1 GB guest. Close other apps and try again.")
        }
        val actualRam = requestedRamMb.coerceIn(1024, 6144).coerceAtMost(safeRamMb)
        val actualCpus = requestedCpus.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        if (actualRam < requestedRamMb) {
            onStatus("RAM limited to ${actualRam / 1024} GB to protect Android")
        }

        val display = reserveVncDisplay()
        val serialLog = File(context.filesDir, "qemu-serial.log")
        val stderrLog = File(context.filesDir, "qemu-stderr.log")
        val args = mutableListOf<String>()
        args += listOf("-machine", if (arch == "aarch64") "virt,gic-version=3" else "q35")
        args += listOf("-cpu", "max")
        args += listOf("-accel", "tcg,thread=multi")
        args += listOf("-smp", actualCpus.toString())
        args += listOf("-m", actualRam.toString())
        args += listOf("-L", firmwareDir.absolutePath)
        if (arch == "aarch64") {
            args += listOf("-bios", File(firmwareDir, "QEMU_EFI.fd").absolutePath)
            args += listOf("-device", "virtio-gpu-pci")
        } else {
            val bios = File(firmwareDir, "bios-256k.bin")
            if (bios.isFile) args += listOf("-bios", bios.absolutePath)
            args += listOf("-vga", "std")
        }
        args += listOf("-drive", "if=none,id=cd0,file=${iso.absolutePath},media=cdrom,readonly=on")
        args += listOf("-device", "virtio-scsi-pci,id=scsi0")
        args += listOf("-device", "scsi-cd,drive=cd0")
        args += listOf("-drive", "if=none,id=hd0,file=${disk.absolutePath},format=raw")
        args += listOf("-device", "virtio-blk-pci,drive=hd0")
        args += listOf("-boot", "order=d,menu=on")
        args += listOf("-netdev", "user,id=net0")
        args += listOf("-device", "virtio-net-pci,netdev=net0")
        args += listOf("-device", "qemu-xhci,id=usb")
        args += listOf("-device", "usb-kbd,bus=usb.0")
        args += listOf("-device", "usb-tablet,bus=usb.0")
        args += listOf("-vnc", "127.0.0.1:$display")
        args += listOf("-serial", "file:${serialLog.absolutePath}")
        args += listOf("-monitor", "none")
        args += listOf("-rtc", "base=utc")
        args += listOf("-no-reboot")
        args += listOf("-name", "AnMu-Guest")

        val command = if (launcher.isFile) {
            listOf(launcher.absolutePath, qemu.absolutePath) + args
        } else {
            listOf(qemu.absolutePath) + args
        }
        val builder = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectOutput(File("/dev/null"))
            .redirectError(stderrLog)
        val env = builder.environment()
        env["LD_LIBRARY_PATH"] = nativeDir.absolutePath + ":" + (env["LD_LIBRARY_PATH"] ?: "")

        if (stopRequested) throw IOException("VM startup was cancelled.")
        onStatus("Starting QEMU ($arch guest, TCG software emulation)...")
        val started = CountDownLatch(1)
        val launchError = AtomicReference<Throwable?>(null)
        val thread = Thread({
            try {
                val child = builder.start()
                process = child
                started.countDown()
                exitCode = child.waitFor()
                onStatus("QEMU stopped (exit code ${exitCode})")
            } catch (error: Throwable) {
                launchError.set(error)
                lastLaunchError = error
                started.countDown()
                Log.e(TAG, "QEMU launch failed", error)
            }
        }, "anmu-qemu-process").apply {
            isDaemon = true
            start()
        }
        launchThread = thread

        if (!started.await(20, TimeUnit.SECONDS)) {
            throw IOException("QEMU process did not start within 20 seconds.")
        }
        launchError.get()?.let { throw IOException("Could not launch QEMU: ${it.localizedMessage}", it) }
        val child = process ?: throw IOException("QEMU process was not created.")
        if (stopRequested) {
            child.destroy()
            throw IOException("VM startup was cancelled.")
        }
        if (!child.isAlive) {
            throw IOException("QEMU exited during startup (code ${exitCode}).\n${readErrorTail(stderrLog)}")
        }

        onStatus("Connecting to guest display...")
        val vnc = VncClient("127.0.0.1", 5900 + display, onFrame)
        try {
            vnc.connect()
        } catch (error: Exception) {
            if (!child.isAlive) {
                throw IOException("QEMU stopped before the display was ready.\n${readErrorTail(stderrLog)}", error)
            }
            throw IOException("QEMU started, but its VNC display could not connect: ${error.localizedMessage}", error)
        }
        if (stopRequested) {
            vnc.close()
            child.destroy()
            throw IOException("VM startup was cancelled.")
        }
        if (!child.isAlive) {
            vnc.close()
            throw IOException("QEMU exited while the guest display was connecting.\\n${readErrorTail(stderrLog)}")
        }
        vncClient = vnc
        onStatus("Virtual machine is running")
    }

    fun sendKey(keySym: Int, down: Boolean) {
        vncClient?.sendKey(keySym, down)
    }

    fun sendText(text: String) {
        vncClient?.sendText(text)
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        vncClient?.sendPointer(x, y, buttonMask)
    }

    fun displaySize(): Pair<Int, Int>? {
        val client = vncClient ?: return null
        return client.width to client.height
    }

    fun stop() {
        stopRequested = true
        vncClient?.close()
        vncClient = null
        val child = process
        if (child != null && child.isAlive) {
            child.destroy()
            try {
                if (!child.waitFor(3, TimeUnit.SECONDS)) {
                    child.destroyForcibly()
                    child.waitFor(2, TimeUnit.SECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                child.destroyForcibly()
            }
        }
        process = null
        onStatus("Virtual machine stopped")
    }

    private fun reserveVncDisplay(): Int {
        for (display in 0..99) {
            val port = 5900 + display
            try {
                ServerSocket(port).use { return display }
            } catch (_: IOException) {
                // Try the next display number.
            }
        }
        throw IOException("No free local VNC display port is available.")
    }

    private fun copyIsoToPrivateStorage(uri: Uri): File {
        val displayName = queryDisplayName(uri).ifBlank { "guest.iso" }
        if (!displayName.lowercase().endsWith(".iso")) {
            throw IOException("The selected file does not have an .iso extension.")
        }
        val expectedSize = querySize(uri)
        val free = StatFs(context.filesDir.absolutePath).availableBytes
        if (expectedSize > 0 && free < expectedSize + 512L * 1024L * 1024L) {
            throw IOException("Not enough free storage to copy this ISO into AnMu's private storage.")
        }
        val target = File(context.filesDir, "guest.iso")
        val temporary = File(context.filesDir, "guest.iso.part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output, 1024 * 1024)
                output.fd.sync()
            }
        } ?: throw IOException("Android could not open the selected ISO.")
        if (expectedSize > 0 && temporary.length() != expectedSize) {
            temporary.delete()
            throw IOException("ISO copy was incomplete.")
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target
    }

    private fun queryDisplayName(uri: Uri): String {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }

    private fun querySize(uri: Uri): Long {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }

    private fun prepareSparseDisk(sizeGb: Int, architecture: String): File {
        val file = File(context.filesDir, "anmu-guest-$architecture.raw")
        val targetLength = sizeGb.toLong() * 1024L * 1024L * 1024L
        RandomAccessFile(file, "rw").use { disk ->
            if (disk.length() < targetLength) disk.setLength(targetLength)
        }
        return file
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }

    private fun readErrorTail(file: File): String {
        if (!file.isFile) return "No QEMU diagnostic log was created."
        return file.readLines().takeLast(24).joinToString("\n").takeLast(4000)
    }

    companion object {
        private const val TAG = "AnMuQEMU"
    }
}
