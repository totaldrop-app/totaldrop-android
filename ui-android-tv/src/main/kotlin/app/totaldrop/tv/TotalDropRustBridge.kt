// SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package app.totaldrop.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Error categories surfaced by `totaldrop_app_rust` (`crates/totaldrop-core` via
 * `ui-m3-expressive/rust/src/api/transfer.rs`). No protocol or cryptographic
 * decisions happen in Kotlin.
 */
enum class TvErrorKind {
    WrongCode,
    PeerLeft,
    Declined,
    Cancelled,
    Stopped,
    InvalidCode,
    CodeNotInUse,
    CodeExpired,
    BadServer,
    Server,
    Connect,
    InvalidOffer,
    Storage,
    TextDamaged,
    Other,
}

data class TvTransferError(
    val kind: TvErrorKind,
    val detail: String,
    val causeName: String = "",
) : Exception(detail)

data class TvTransferSummary(
    val filesOk: Long,
    val filesFailed: Long,
    val bytes: Long,
    val saved: List<String>,
    val text: String?,
)

enum class TvEventKind {
    Waiting,
    Connected,
    Progress,
    Finished,
    Failed,
}

data class TvTransferEvent(
    val kind: TvEventKind,
    val words: List<String> = emptyList(),
    val done: Long = 0L,
    val total: Long = 0L,
    val summary: TvTransferSummary? = null,
    val error: TvTransferError? = null,
)

data class TvShownText(
    val text: String,
    val short: Boolean,
    val hidden: Boolean,
)

/**
 * Opaque handle to a Rust `Stopper` (`totaldrop_core::Stop`).
 */
class TvStopperHandle internal constructor(
    internal val ptr: Long,
    private val onStop: (Long) -> Unit,
    private val onDispose: (Long) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun stop() {
        if (!closed.get() && ptr != 0L) {
            onStop(ptr)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true) && ptr != 0L) {
            onDispose(ptr)
        }
    }
}

/**
 * Opaque handle to a prepared Rust `SendSession`.
 */
data class TvSendSession(
    internal val handle: Long,
    val code: String,
    val items: Long,
    val totalBytes: Long,
) {
    /** Splits `7-harbor-rocket` into `["7", "harbor", "rocket"]` for 10-foot display pills. */
    val segments: List<String>
        get() = code.split('-').filter { it.isNotEmpty() }

    /**
     * Fragment-isolated pairing URL (#445, T19): the transfer secret stays after `#`
     * so it is never transmitted in an HTTP request to `totaldrop.app`.
     */
    val pairingUrl: String
        get() = "https://totaldrop.app/r#$code"
}

/**
 * Opaque handle to an incoming Rust `Offer` awaiting user confirmation.
 */
data class TvIncomingOffer(
    internal val handle: Long,
    val files: Long,
    val folders: Long,
    val totalBytes: Long,
    val names: List<String>,
    val words: List<String>,
    val isText: Boolean,
)

/**
 * Thin Kotlin interface over `totaldrop_app_rust` (`librust_lib_totaldrop.so`).
 * All CPace key agreement, ML-KEM-768 hybrid post-quantum handshakes, ChaCha20-Poly1305
 * chunk verification, transfer-code parsing, and BiDi control-character unveiling
 * execute inside the Rust core.
 */
interface TotalDropRustBridge {
    fun defaultServer(): String
    fun maxTextBytes(): Long
    fun normalizeCode(input: String): String?
    fun codeFromLink(link: String): String?
    fun revealControls(name: String): String
    fun shownText(text: String): TvShownText
    fun negotiateLocale(preferences: List<String>, available: List<String>): String?
    fun newStopper(): TvStopperHandle

    suspend fun prepareSend(
        server: String,
        paths: List<String>,
        strong: Boolean,
        privacy: Boolean,
        stopper: TvStopperHandle,
    ): Result<TvSendSession>

    suspend fun prepareSendText(
        server: String,
        text: String,
        strong: Boolean,
        privacy: Boolean,
        stopper: TvStopperHandle,
    ): Result<TvSendSession>

    fun runSend(
        session: TvSendSession,
        stopper: TvStopperHandle,
    ): Flow<TvTransferEvent>

    suspend fun connectReceive(
        server: String,
        code: String,
        privacy: Boolean,
        stopper: TvStopperHandle,
    ): Result<TvIncomingOffer>

    fun acceptOffer(
        offer: TvIncomingOffer,
        destination: String,
        stopper: TvStopperHandle,
    ): Flow<TvTransferEvent>

    fun acceptText(
        offer: TvIncomingOffer,
        stopper: TvStopperHandle,
    ): Flow<TvTransferEvent>

    suspend fun declineOffer(offer: TvIncomingOffer): Result<Unit>
}

/**
 * Callback interface invoked from JNI/FRB stream sinks as Rust emits `TransferEvent`s.
 */
interface TvNativeEventSink {
    fun onEvent(
        kindOrdinal: Int,
        words: Array<String>,
        done: Long,
        total: Long,
        filesOk: Long,
        filesFailed: Long,
        bytes: Long,
        saved: Array<String>,
        text: String?,
        errorKindOrdinal: Int,
        errorDetail: String?,
        errorCause: String?,
    )
}

/**
 * Production JNI/FRB bridge binding to `libtotaldrop_app_rust.so` (or `librust_lib_totaldrop.so`
 * built from `ui-m3-expressive/rust`).
 */
class NativeTotalDropRustBridge : TotalDropRustBridge {
    init {
        ensureLoaded()
    }

    override fun defaultServer(): String =
        if (nativeAvailable) nativeDefaultServer() else DEFAULT_RENDEZVOUS_SERVER

    override fun maxTextBytes(): Long =
        if (nativeAvailable) nativeMaxTextBytes() else 1_048_576L

    override fun normalizeCode(input: String): String? =
        if (nativeAvailable) nativeNormalizeCode(input) else null

    override fun codeFromLink(link: String): String? =
        if (nativeAvailable) nativeCodeFromLink(link) else null

    override fun revealControls(name: String): String =
        if (nativeAvailable) nativeRevealControls(name) else revealControlsFallback(name)

    override fun shownText(text: String): TvShownText =
        if (nativeAvailable) {
            nativeShownText(text)
        } else {
            val normalized = text.replace("\r\n", "\n")
            val revealed = revealControlsFallback(normalized)
            val truncated = if (revealed.length > SHOWN_CHARS_LIMIT) {
                revealed.substring(0, SHOWN_CHARS_LIMIT)
            } else {
                revealed
            }
            TvShownText(
                text = truncated,
                short = revealed.length > SHOWN_CHARS_LIMIT,
                hidden = revealed != normalized,
            )
        }

    override fun negotiateLocale(preferences: List<String>, available: List<String>): String? =
        if (nativeAvailable) {
            nativeNegotiateLocale(preferences.toTypedArray(), available.toTypedArray())
        } else {
            null
        }

    override fun newStopper(): TvStopperHandle {
        if (!nativeAvailable) {
            return TvStopperHandle(0L, {}, {})
        }
        val ptr = nativeNewStopper()
        return TvStopperHandle(
            ptr = ptr,
            onStop = ::nativeStopTransfer,
            onDispose = ::nativeDisposeStopper,
        )
    }

    override suspend fun prepareSend(
        server: String,
        paths: List<String>,
        strong: Boolean,
        privacy: Boolean,
        stopper: TvStopperHandle,
    ): Result<TvSendSession> = withContext(Dispatchers.IO) {
        ensureBridgeReady {
            nativePrepareSend(server, paths.toTypedArray(), strong, privacy, stopper.ptr)
        }
    }

    override suspend fun prepareSendText(
        server: String,
        text: String,
        strong: Boolean,
        privacy: Boolean,
        stopper: TvStopperHandle,
    ): Result<TvSendSession> = withContext(Dispatchers.IO) {
        ensureBridgeReady {
            nativePrepareSendText(server, text, strong, privacy, stopper.ptr)
        }
    }

    override fun runSend(
        session: TvSendSession,
        stopper: TvStopperHandle,
    ): Flow<TvTransferEvent> = streamEvents { sink ->
        nativeRunSend(session.handle, stopper.ptr, sink)
    }

    override suspend fun connectReceive(
        server: String,
        code: String,
        privacy: Boolean,
        stopper: TvStopperHandle,
    ): Result<TvIncomingOffer> = withContext(Dispatchers.IO) {
        ensureBridgeReady {
            nativeConnectReceive(server, code, privacy, stopper.ptr)
        }
    }

    override fun acceptOffer(
        offer: TvIncomingOffer,
        destination: String,
        stopper: TvStopperHandle,
    ): Flow<TvTransferEvent> = streamEvents { sink ->
        nativeAcceptOffer(offer.handle, destination, stopper.ptr, sink)
    }

    override fun acceptText(
        offer: TvIncomingOffer,
        stopper: TvStopperHandle,
    ): Flow<TvTransferEvent> = streamEvents { sink ->
        nativeAcceptText(offer.handle, stopper.ptr, sink)
    }

    override suspend fun declineOffer(offer: TvIncomingOffer): Result<Unit> =
        withContext(Dispatchers.IO) {
            ensureBridgeReady {
                nativeDeclineOffer(offer.handle)
            }
        }

    private inline fun <T> ensureBridgeReady(block: () -> T): Result<T> {
        if (!nativeAvailable) {
            return Result.failure(
                TvTransferError(
                    kind = TvErrorKind.Other,
                    detail = "Native library totaldrop_app_rust is not packaged in jniLibs for this ABI.",
                ),
            )
        }
        return runCatching(block).recoverCatching { err ->
            if (err is TvTransferError) throw err
            throw TvTransferError(TvErrorKind.Other, err.message ?: "Native bridge failure")
        }
    }

    private fun streamEvents(block: (TvNativeEventSink) -> Unit): Flow<TvTransferEvent> =
        callbackFlow {
            if (!nativeAvailable) {
                trySend(
                    TvTransferEvent(
                        kind = TvEventKind.Failed,
                        error = TvTransferError(
                            kind = TvErrorKind.Other,
                            detail = "Native library totaldrop_app_rust is not packaged in jniLibs for this ABI.",
                        ),
                    ),
                )
                close()
                return@callbackFlow
            }
            val sink = object : TvNativeEventSink {
                override fun onEvent(
                    kindOrdinal: Int,
                    words: Array<String>,
                    done: Long,
                    total: Long,
                    filesOk: Long,
                    filesFailed: Long,
                    bytes: Long,
                    saved: Array<String>,
                    text: String?,
                    errorKindOrdinal: Int,
                    errorDetail: String?,
                    errorCause: String?,
                ) {
                    val kind = TvEventKind.entries.getOrElse(kindOrdinal) { TvEventKind.Failed }
                    val summary = if (kind == TvEventKind.Finished) {
                        TvTransferSummary(
                            filesOk = filesOk,
                            filesFailed = filesFailed,
                            bytes = bytes,
                            saved = saved.toList(),
                            text = text,
                        )
                    } else {
                        null
                    }
                    val error = if (kind == TvEventKind.Failed) {
                        TvTransferError(
                            kind = TvErrorKind.entries.getOrElse(errorKindOrdinal) { TvErrorKind.Other },
                            detail = errorDetail.orEmpty(),
                            causeName = errorCause.orEmpty(),
                        )
                    } else {
                        null
                    }
                    trySend(
                        TvTransferEvent(
                            kind = kind,
                            words = words.toList(),
                            done = done,
                            total = total,
                            summary = summary,
                            error = error,
                        ),
                    )
                    if (kind == TvEventKind.Finished || kind == TvEventKind.Failed) {
                        close()
                    }
                }
            }
            withContext(Dispatchers.IO) {
                runCatching { block(sink) }.onFailure { err ->
                    trySend(
                        TvTransferEvent(
                            kind = TvEventKind.Failed,
                            error = (err as? TvTransferError)
                                ?: TvTransferError(TvErrorKind.Other, err.message.orEmpty()),
                        ),
                    )
                    close()
                }
            }
            awaitClose { }
        }

    companion object {
        private const val DEFAULT_RENDEZVOUS_SERVER = "wss://rendezvous.totaldrop.app"
        private const val SHOWN_CHARS_LIMIT = 16 * 1024

        @Volatile
        private var nativeAvailable: Boolean = false
        private val loadAttempted = AtomicBoolean(false)

        fun ensureLoaded() {
            if (!loadAttempted.compareAndSet(false, true)) return
            nativeAvailable = tryLoad("totaldrop_app_rust") || tryLoad("rust_lib_totaldrop")
        }

        private fun tryLoad(libName: String): Boolean =
            try {
                System.loadLibrary(libName)
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }

        /** Defensive display sanitizer used only if JNI failed to load; names BiDi/control chars. */
        private fun revealControlsFallback(input: String): String = buildString(input.length) {
            input.codePoints().forEach { cp ->
                val keepJoiner = cp == 0x200C || cp == 0x200D
                val isHidden = !keepJoiner && (
                    cp in 0xFDD0..0xFDEF ||
                        (cp and 0xFFFE) == 0xFFFE ||
                        when (Character.getType(cp).toByte()) {
                            Character.CONTROL,
                            Character.FORMAT,
                            Character.LINE_SEPARATOR,
                            Character.PARAGRAPH_SEPARATOR,
                            -> true
                            else -> false
                        }
                    )
                if (isHidden && cp != '\n'.code && cp != '\t'.code) {
                    append(String.format(Locale.ROOT, "<U+%04X>", cp))
                } else {
                    appendCodePoint(cp)
                }
            }
        }

        @JvmStatic
        private external fun nativeDefaultServer(): String

        @JvmStatic
        private external fun nativeMaxTextBytes(): Long

        @JvmStatic
        private external fun nativeNormalizeCode(input: String): String?

        @JvmStatic
        private external fun nativeCodeFromLink(link: String): String?

        @JvmStatic
        private external fun nativeRevealControls(name: String): String

        @JvmStatic
        private external fun nativeShownText(text: String): TvShownText

        @JvmStatic
        private external fun nativeNegotiateLocale(
            preferences: Array<String>,
            available: Array<String>,
        ): String?

        @JvmStatic
        private external fun nativeNewStopper(): Long

        @JvmStatic
        private external fun nativeStopTransfer(stopperPtr: Long)

        @JvmStatic
        private external fun nativeDisposeStopper(stopperPtr: Long)

        @JvmStatic
        private external fun nativePrepareSend(
            server: String,
            paths: Array<String>,
            strong: Boolean,
            privacy: Boolean,
            stopperPtr: Long,
        ): TvSendSession

        @JvmStatic
        private external fun nativePrepareSendText(
            server: String,
            text: String,
            strong: Boolean,
            privacy: Boolean,
            stopperPtr: Long,
        ): TvSendSession

        @JvmStatic
        private external fun nativeRunSend(
            sessionHandle: Long,
            stopperPtr: Long,
            sink: TvNativeEventSink,
        )

        @JvmStatic
        private external fun nativeConnectReceive(
            server: String,
            code: String,
            privacy: Boolean,
            stopperPtr: Long,
        ): TvIncomingOffer

        @JvmStatic
        private external fun nativeAcceptOffer(
            offerHandle: Long,
            destination: String,
            stopperPtr: Long,
            sink: TvNativeEventSink,
        )

        @JvmStatic
        private external fun nativeAcceptText(
            offerHandle: Long,
            stopperPtr: Long,
            sink: TvNativeEventSink,
        )

        @JvmStatic
        private external fun nativeDeclineOffer(offerHandle: Long)
    }
}

/**
 * Formats transfer completion percentages according to #483 and ADR 0008:
 * - Rounds strictly DOWN (`(done * 100) / total`) so `995 / 1000` reads `99%`, never `100%` early.
 * - Uses the device's regional `Locale` for percent symbol placement and numeral script.
 * - Returns both the localized label and the integer value for TalkBack `stateDescription` parity.
 */
data class FloorPercent(
    val wholePercent: Int,
    val regionalLabel: String,
)

fun computeFloorPercent(
    done: Long,
    total: Long,
    regionLocale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): FloorPercent {
    val clampedPercent = when {
        total <= 0L || done <= 0L -> 0
        done >= total -> 100
        else -> ((done.coerceAtMost(Long.MAX_VALUE / 100L) * 100L) / total).toInt().coerceIn(0, 99)
    }
    val formatter = NumberFormat.getPercentInstance(regionLocale).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }
    return FloorPercent(
        wholePercent = clampedPercent,
        regionalLabel = formatter.format(clampedPercent / 100.0),
    )
}

/**
 * Formats byte counts in SI decimal units matching `totaldrop_l10n::Strings::bytes`.
 */
fun formatSiBytes(bytes: Long, regionLocale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String {
    if (bytes < 1000L) return "$bytes B"
    val units = arrayOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1000.0
    var unitIndex = 0
    while (value >= 999.5 && unitIndex < units.lastIndex) {
        value /= 1000.0
        unitIndex++
    }
    val nf = NumberFormat.getNumberInstance(regionLocale).apply {
        if (value < 10.0) {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        } else {
            minimumFractionDigits = 0
            maximumFractionDigits = 0
        }
    }
    return "${nf.format(value)} ${units[unitIndex]}"
}

/**
 * Offline vector QR code rendered directly onto a Compose `Canvas` for 10-foot scanning
 * across a living room on 1080p and 4K televisions. Uses quiet-zone framing and high
 * contrast (`Color.White` card + `Color.Black` modules) regardless of TV dark/light mode.
 */
@Composable
fun TvQrPairingCard(
    content: String,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val matrix = remember(content) {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(16.dp)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Canvas(modifier = Modifier.size(size - 32.dp)) {
            val cols = matrix.width.coerceAtLeast(1)
            val rows = matrix.height.coerceAtLeast(1)
            val cellW = this.size.width / cols
            val cellH = this.size.height / rows
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW + 0.5f, cellH + 0.5f),
                        )
                    }
                }
            }
        }
    }
}
