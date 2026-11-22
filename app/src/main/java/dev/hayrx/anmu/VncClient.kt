package dev.hayrx.anmu

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small loopback-only RFB 3.8 client for QEMU's VNC display.
 * It negotiates 32-bit little-endian pixels and the Raw encoding.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val onFrame: (Bitmap) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var reader: Thread? = null
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set

    fun connect(timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Exception? = null
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            try {
                val candidate = Socket()
                candidate.tcpNoDelay = true
                candidate.connect(InetSocketAddress(host, port), 1200)
                candidate.soTimeout = 0
                socket = candidate
                val input = DataInputStream(BufferedInputStream(candidate.getInputStream(), 64 * 1024))
                val out = DataOutputStream(BufferedOutputStream(candidate.getOutputStream(), 16 * 1024))
                val version = ByteArray(12)
                input.readFully(version)
                if (!String(version, Charsets.US_ASCII).startsWith("RFB ")) {
                    throw IOException("QEMU returned an invalid VNC greeting.")
                }
                out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val count = input.readUnsignedByte()
                if (count == 0) throw IOException("QEMU VNC rejected the connection.")
                val security = ByteArray(count)
                input.readFully(security)
                if (security.none { it.toInt() == 1 }) {
                    throw IOException("QEMU VNC requires an unsupported authentication method.")
                }
                out.writeByte(1)
                out.flush()
                if (input.readInt() != 0) throw IOException("VNC security handshake failed.")
                out.writeByte(1) // shared session
                out.flush()

                width = input.readUnsignedShort()
                height = input.readUnsignedShort()
                if (width !in 1..2560 || height !in 1..1600) {
                    throw IOException("QEMU returned an invalid framebuffer size: ${width}x${height}.")
                }
                skipFully(input, 16) // server pixel format; we set ours below
                val nameLength = input.readInt()
                if (nameLength !in 0..(1024 * 1024)) throw IOException("Invalid VNC desktop name.")
                skipFully(input, nameLength)

                // SetPixelFormat: 32bpp, depth 24, little-endian, RGB shifts 16/8/0.
                out.write(byteArrayOf(
                    0, 0, 0, 0,
                    32, 24, 0, 1,
                    0, -1, 0, -1, 0, -1,
                    16, 8, 0,
                    0, 0, 0
                ))
                // SetEncodings: Raw only.
                out.write(byteArrayOf(2, 0, 0, 1, 0, 0, 0, 0))
                writeFramebufferRequest(out, false)
                out.flush()
                output = out
                reader = Thread({ readLoop(input, out) }, "anmu-vnc-reader").apply {
                    isDaemon = true
                    start()
                }
                return
            } catch (error: Exception) {
                lastError = error
                runCatching { socket?.close() }
                socket = null
                if (!closed.get()) Thread.sleep(250)
            }
        }
        throw IOException(
            "Could not connect to QEMU's VNC display: ${lastError?.localizedMessage ?: "timeout"}",
            lastError
        )
    }

    private fun readLoop(input: DataInputStream, out: DataOutputStream) {
        val pixels = IntArray(width * height)
        try {
            while (!closed.get()) {
                when (input.readUnsignedByte()) {
                    0 -> {
                        input.readUnsignedByte() // padding
                        val rectangles = input.readUnsignedShort()
                        repeat(rectangles) {
                            val x = input.readUnsignedShort()
                            val y = input.readUnsignedShort()
                            val w = input.readUnsignedShort()
                            val h = input.readUnsignedShort()
                            val encoding = input.readInt()
                            if (encoding != 0) throw IOException("Unsupported VNC rectangle encoding: $encoding")
                            if (w <= 0 || h <= 0 || x + w > width || y + h > height) {
                                throw IOException("QEMU sent an invalid framebuffer rectangle.")
                            }
                            val row = ByteArray(w * 4)
                            repeat(h) { line ->
                                input.readFully(row)
                                val base = (y + line) * width + x
                                for (column in 0 until w) {
                                    val p = column * 4
                                    val blue = row[p].toInt() and 0xff
                                    val green = row[p + 1].toInt() and 0xff
                                    val red = row[p + 2].toInt() and 0xff
                                    pixels[base + column] =
                                        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                                }
                            }
                        }
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                        onFrame(bitmap)
                        synchronized(writeLock) {
                            if (!closed.get()) {
                                writeFramebufferRequest(out, true)
                                out.flush()
                            }
                        }
                    }
                    1 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                        val count = input.readUnsignedShort()
                        skipFully(input, count * 6)
                    }
                    2 -> Unit // Bell
                    3 -> {
                        skipFully(input, 3)
                        val length = input.readInt()
                        if (length !in 0..(1024 * 1024)) throw IOException("Invalid VNC clipboard payload.")
                        skipFully(input, length)
                    }
                    else -> throw IOException("Unexpected message from QEMU's VNC server.")
                }
            }
        } catch (_: Exception) {
            if (!closed.get()) closed.set(true)
            runCatching { socket?.close() }
        }
    }

    fun sendKey(keySym: Int, down: Boolean) {
        val out = output ?: return
        synchronized(writeLock) {
            if (closed.get()) return
            out.writeByte(4)
            out.writeByte(if (down) 1 else 0)
            out.writeShort(0)
            out.writeInt(keySym)
            out.flush()
        }
    }

    fun sendText(value: String) {
        value.codePoints().forEach { code ->
            val symbol = when (code) {
                10, 13 -> 0xff0d
                9 -> 0xff09
                8 -> 0xff08
                else -> if (code <= 0xff) code else 0x01000000 or code
            }
            sendKey(symbol, true)
            sendKey(symbol, false)
        }
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            if (closed.get()) return
            out.writeByte(5)
            out.writeByte(buttonMask)
            out.writeShort(x.coerceIn(0, width - 1))
            out.writeShort(y.coerceIn(0, height - 1))
            out.flush()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun writeFramebufferRequest(out: DataOutputStream, incremental: Boolean) {
        out.writeByte(3)
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(width)
        out.writeShort(height)
    }

    private fun skipFully(input: DataInputStream, count: Int) {
        var left = count
        val buffer = ByteArray(minOf(8192, count.coerceAtLeast(1)))
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(left, buffer.size))
            if (read < 0) throw IOException("Unexpected end of VNC stream.")
            left -= read
        }
    }
}
