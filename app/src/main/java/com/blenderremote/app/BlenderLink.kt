package com.blenderremote.app

import android.util.Log
import org.zeromq.SocketType
import org.zeromq.ZMQ
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BlenderLink"

private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

/** DEALER socket with a UNIQUE identity (a fixed identity made the 2nd recording fail). */
internal fun newDealer(ctx: ZMQ.Context, host: String, port: Int, name: String, recvTimeoutMs: Int): ZMQ.Socket {
    val s = ctx.socket(SocketType.DEALER)
    s.setIdentity(bytes(name + "-" + UUID.randomUUID().toString()))
    s.setLinger(0)
    s.setImmediate(true)
    s.setReceiveTimeOut(recvTimeoutMs)
    s.setSendTimeOut(3000)
    s.connect("tcp://" + host + ":" + port)
    return s
}

internal fun sendParts(s: ZMQ.Socket, parts: List<ByteArray>): Boolean {
    for (i in parts.indices) {
        val flags = if (i < parts.size - 1) ZMQ.SNDMORE else 0
        if (!s.send(parts[i], flags)) return false
    }
    return true
}

/** Receives one multipart message and returns its last frame (null on timeout). */
internal fun recvLast(s: ZMQ.Socket): ByteArray? {
    var last: ByteArray = s.recv(0) ?: return null
    while (s.hasReceiveMore()) {
        last = s.recv(0) ?: break
    }
    return last
}

internal fun localIpv4(): String {
    return try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "phone"
    } catch (e: Exception) {
        "phone"
    }
}

/**
 * Heartbeat (so Blender knows the phone is alive) + non-blocking frame sender.
 * The render thread only drops the latest frame in a mailbox; a separate thread
 * does the (possibly slow) network send, so Blender being busy can never freeze the app.
 */
class BlenderLink(
    val host: String,
    val port: Int,
    private val onState: (online: Boolean, rttMs: Long) -> Unit
) {
    private val ctx: ZMQ.Context = ZMQ.context(1)
    private val running = AtomicBoolean(false)
    private val mailbox = ArrayBlockingQueue<List<ByteArray>>(1)
    private var heartbeatThread: Thread? = null
    private var senderThread: Thread? = null

    @Volatile
    var online: Boolean = false
        private set

    @Volatile
    var rttMs: Long = 0
        private set

    @Volatile
    var sentFrames: Long = 0
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        heartbeatThread = Thread({ heartbeatLoop() }, "blender-heartbeat").apply { isDaemon = true; start() }
        senderThread = Thread({ senderLoop() }, "blender-sender").apply { isDaemon = true; start() }
    }

    /** Latest frame wins: an older frame still waiting is dropped. */
    fun submit(parts: List<ByteArray>) {
        mailbox.poll()
        mailbox.offer(parts)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Thread({
            try {
                heartbeatThread?.join(4000)
                senderThread?.join(4000)
            } catch (e: InterruptedException) {
                // ignore
            }
            try {
                ctx.close()
            } catch (e: Exception) {
                Log.w(TAG, "context close: " + e)
            }
        }, "blender-link-close").apply { isDaemon = true; start() }
    }

    private fun heartbeatLoop() {
        val ip = localIpv4()
        var sock: ZMQ.Socket? = null
        try {
            while (running.get()) {
                try {
                    if (sock == null) sock = newDealer(ctx, host, port, "ttl", 2000)
                    val s = sock!!
                    val t0 = System.nanoTime()
                    val sent = sendParts(s, listOf(bytes("STATE"), bytes(ip)))
                    val reply = if (sent) recvLast(s) else null
                    if (reply != null) {
                        rttMs = (System.nanoTime() - t0) / 1_000_000
                        setOnline(true)
                    } else {
                        setOnline(false)
                        s.close()
                        sock = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "heartbeat: " + e)
                    setOnline(false)
                    try { sock?.close() } catch (x: Exception) { }
                    sock = null
                }
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        } finally {
            try { sock?.close() } catch (x: Exception) { }
            online = false
        }
    }

    private fun setOnline(value: Boolean) {
        online = value
        onState(value, rttMs)
    }

    private fun senderLoop() {
        var sock: ZMQ.Socket? = null
        try {
            val s = ctx.socket(SocketType.PUSH)
            sock = s
            s.setLinger(0)
            s.setImmediate(true)
            s.setSndHWM(2)
            s.setSendTimeOut(200)
            s.connect("tcp://" + host + ":" + (port + 1))
            while (running.get()) {
                val parts = mailbox.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!online) continue
                if (sendParts(s, parts)) sentFrames++
            }
        } catch (e: Exception) {
            Log.w(TAG, "sender: " + e)
        } finally {
            try { sock?.close() } catch (x: Exception) { }
        }
    }
}

/** Downloads the exported OBJ from Blender (chunked, written straight to disk). */
object SceneClient {
    /** @return size in bytes (0 = Blender sent no model). */
    fun download(
        host: String,
        port: Int,
        dest: File,
        cancel: AtomicBoolean,
        progress: (received: Long, waitedMs: Long) -> Unit
    ): Long {
        val ctx = ZMQ.context(1)
        var sock: ZMQ.Socket? = null
        val part = File(dest.path + ".part")
        try {
            val s = newDealer(ctx, host, port + 2, "scene", 1000)
            sock = s
            var offset = 0L
            val start = System.currentTimeMillis()
            FileOutputStream(part).use { out ->
                while (true) {
                    val request = listOf(bytes("SCENE"), bytes(offset.toString()), bytes(Protocol.CHUNK_SIZE.toString()))
                    if (!sendParts(s, request)) throw IOException("Cannot reach Blender")

                    // Blender may need minutes to export the first chunk; later chunks are fast.
                    val limitMs = if (offset == 0L) 300_000L else 30_000L
                    val deadline = System.currentTimeMillis() + limitMs
                    var chunk: ByteArray? = null
                    while (chunk == null) {
                        if (cancel.get()) throw IOException("Cancelled")
                        chunk = recvLast(s)
                        if (chunk == null) {
                            if (System.currentTimeMillis() > deadline) {
                                throw IOException("Blender did not answer in time")
                            }
                            progress(offset, System.currentTimeMillis() - start)
                        }
                    }
                    val data: ByteArray = chunk!!
                    out.write(data)
                    offset += data.size
                    progress(offset, System.currentTimeMillis() - start)
                    if (data.size < Protocol.CHUNK_SIZE) break
                }
            }
            if (dest.exists()) dest.delete()
            if (offset == 0L) {
                part.delete()
                return 0L
            }
            if (!part.renameTo(dest)) throw IOException("Cannot save the model")
            return dest.length()
        } finally {
            try { sock?.close() } catch (x: Exception) { }
            try { ctx.close() } catch (x: Exception) { }
            if (part.exists()) part.delete()
        }
    }
}

/** Camera recording: START, poll the frame number, STOP. */
class RecordClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener
) {
    interface Listener {
        fun onStarted()
        fun onFrame(frame: Int)
        fun onFinished(message: String?)
    }

    private val stopRequested = AtomicBoolean(false)

    fun start() {
        Thread({ run() }, "blender-record").apply { isDaemon = true; start() }
    }

    fun requestStop() {
        stopRequested.set(true)
    }

    private fun run() {
        val ctx = ZMQ.context(1)
        var sock: ZMQ.Socket? = null
        var message: String? = null
        try {
            val s = newDealer(ctx, host, port + 2, "record", 4000)
            sock = s
            if (!sendParts(s, listOf(bytes("RECORD"), bytes("START")))) {
                throw IOException("Cannot reach Blender")
            }
            val started = recvLast(s)?.toString(Charsets.UTF_8) ?: ""
            if (!started.contains("STARTED")) throw IOException("Blender did not start the recording")
            listener.onStarted()

            while (!stopRequested.get()) {
                sendParts(s, listOf(bytes("RECORD"), bytes("STATE")))
                val answer = recvLast(s)?.toString(Charsets.UTF_8)
                    ?: throw IOException("Blender stopped answering")
                if (answer.contains("STOPPED")) {
                    message = "Recording ended in Blender"
                    break
                }
                answer.trim().toIntOrNull()?.let { listener.onFrame(it) }
                Thread.sleep(300)
            }
        } catch (e: Exception) {
            message = e.message ?: "Recording failed"
        } finally {
            try {
                sock?.let {
                    sendParts(it, listOf(bytes("RECORD"), bytes("STOP")))
                    recvLast(it)
                }
            } catch (x: Exception) { }
            try { sock?.close() } catch (x: Exception) { }
            try { ctx.close() } catch (x: Exception) { }
            listener.onFinished(message)
        }
    }
}
