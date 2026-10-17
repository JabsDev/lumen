package com.jabs.lumen.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * Receives the camera live view.
 *
 * The feed is not HTTP: the phone binds a UDP port and hands that port number to the camera with
 * `cam.cgi?mode=startstream&value=<port>`. The camera then pushes one datagram per frame, each
 * carrying a short binary header followed by one complete JPEG image. The header also carries the
 * exposure values the camera used, which is how the controls can follow what is really set.
 */
class LiveViewSource(
    private val client: LumixClient,
    private val onFrame: (Bitmap) -> Unit,
) {
    private companion object {
        const val BASE_PORT = 49152
    }

    private var socket: DatagramSocket? = null

    /** Binds a port, tells the camera to send there, then pumps frames until closed. */
    fun open() {
        val bound = bindFreePort() ?: return
        socket = bound

        client.recMode()
        client.startStream(bound.localPort)

        val buffer = ByteArray(96 * 1024)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!bound.isClosed) {
            try {
                bound.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                break
            }
            readFrame(buffer, packet.length)?.let(onFrame)
        }
    }

    private fun bindFreePort(): DatagramSocket? {
        for (port in BASE_PORT until BASE_PORT + 64) {
            val attempt = runCatching { DatagramSocket(port) }.getOrNull()
            if (attempt != null) {
                attempt.reuseAddress = true
                attempt.soTimeout = 5000
                return attempt
            }
        }
        return null
    }

    /**
     * Each datagram carries one JPEG after its header. Rather than depend on the header layout,
     * which differs between models, the JPEG start marker is located directly.
     */
    private fun readFrame(buffer: ByteArray, length: Int): Bitmap? {
        var start = -1
        var i = 0
        while (i < length - 2) {
            if ((buffer[i].toInt() and 0xFF) == 0xFF &&
                (buffer[i + 1].toInt() and 0xFF) == 0xD8 &&
                (buffer[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                start = i
                break
            }
            i++
        }
        if (start < 0) return null

        return runCatching {
            BitmapFactory.decodeByteArray(buffer, start, length - start, BitmapFactory.Options())
        }.getOrNull()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        runCatching { client.stopStream() }
    }
}