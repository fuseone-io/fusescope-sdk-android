package io.fuseone.fusescope.android

internal data class NativeTombstone(
    val signal: String,
    val stack: String,
)

private const val MAX_NATIVE_PROTO_BYTES = 4 * 1024 * 1024
private const val MAX_NATIVE_STACK_CHARS = 65537
private const val MAX_NATIVE_FRAMES = 201
private val NATIVE_BUILD_ID = Regex("[0-9a-fA-F]{1,128}")
private val NATIVE_LINE_BREAKS = Regex("[\\r\\n]")
private val NATIVE_WHITESPACE = Regex("\\s+")

/**
 * Reads the public Android tombstone schema clean-room, retaining only signal and code frames.
 * Schema: https://android.googlesource.com/platform/system/core/+/main/debuggerd/proto/tombstone.proto
 * The extra frame/character lets the backend mark its 200-frame/64-KiB response as truncated.
 */
internal fun parseNativeTombstone(bytes: ByteArray): NativeTombstone? {
    if (bytes.size > MAX_NATIVE_PROTO_BYTES) return null
    return try {
        decodeNativeTombstone(NativeProtoReader(bytes))
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun decodeNativeTombstone(root: NativeProtoReader): NativeTombstone {
    var tid = 0L
    var signal = ""
    val threads = mutableMapOf<Long, NativeProtoReader>()
    while (true) {
        val field = root.next() ?: break
        when (field.number) {
            6 -> {
                tid = field.scalar
            }

            10 -> {
                signal = signalName(field.message)
            }

            16 -> {
                val entry = field.message ?: continue
                var id = 0L
                var thread: NativeProtoReader? = null
                while (true) {
                    val member = entry.next() ?: break
                    if (member.number == 1) id = member.scalar
                    if (member.number == 2) thread = member.message
                }
                if (thread != null && threads.size < 256) threads[id] = thread
            }
        }
    }
    val crashed = threads[tid]
    val stack = StringBuilder()
    var frames = 0
    if (crashed != null && tid > 0) {
        while (frames < MAX_NATIVE_FRAMES && stack.length < MAX_NATIVE_STACK_CHARS) {
            val field = crashed.next() ?: break
            if (field.number != 4) continue
            val frame = field.message ?: continue
            val line = nativeFrame(frame, frames) ?: continue
            stack.append(line).append('\n')
            frames++
        }
    }
    return NativeTombstone(signal, stack.toString().take(MAX_NATIVE_STACK_CHARS))
}

private fun signalName(signal: NativeProtoReader?): String {
    if (signal == null) return ""
    while (true) {
        val field = signal.next() ?: return ""
        if (field.number == 2) return field.message?.text(32).orEmpty()
    }
}

private fun nativeFrame(
    frame: NativeProtoReader,
    index: Int,
): String? {
    var pc = 0L
    var file = ""
    var function = ""
    var offset = 0L
    var build = ""
    while (true) {
        val field = frame.next() ?: break
        when (field.number) {
            1 -> pc = field.scalar
            4 -> function = nativeText(field, 256).replace(NATIVE_LINE_BREAKS, " ")
            5 -> offset = field.scalar
            6 -> file = nativeText(field, 1024).replace(NATIVE_WHITESPACE, "_")
            8 -> build = nativeText(field, 128)
        }
    }
    if (file.isBlank()) return null
    val symbol = if (function.isBlank()) "" else " ($function+${offset.toULong()})"
    val id = if (NATIVE_BUILD_ID.matches(build)) " (BuildId: $build)" else ""
    return "#${index.toString().padStart(2, '0')} pc ${pc.toULong().toString(16)} $file$symbol$id"
}

private fun nativeText(
    field: NativeProtoField,
    limit: Int,
): String = field.message?.text(limit).orEmpty()
