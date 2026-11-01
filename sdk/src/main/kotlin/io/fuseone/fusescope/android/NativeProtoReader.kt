package io.fuseone.fusescope.android

internal data class NativeProtoField(
    val number: Int,
    val scalar: Long = 0,
    val message: NativeProtoReader? = null,
)

/** A bounded protobuf wire reader; unknown fields are skipped without copying their payloads. */
internal class NativeProtoReader private constructor(
    private val bytes: ByteArray,
    private var position: Int,
    private val end: Int,
    private val budget: Budget,
) {
    constructor(bytes: ByteArray) : this(bytes, 0, bytes.size, Budget())

    fun next(): NativeProtoField? {
        if (position == end) return null
        require(--budget.remaining >= 0)
        val tag = varint()
        require(tag in 1..0xffffffffL)
        val number = (tag ushr 3).toInt()
        require(number > 0)
        return when ((tag and 7).toInt()) {
            0 -> NativeProtoField(number, scalar = varint())
            1 -> skip(number, 8)
            2 -> {
                val length = varint()
                require(length >= 0 && length <= end - position)
                val child = NativeProtoReader(bytes, position, position + length.toInt(), budget)
                position += length.toInt()
                NativeProtoField(number, message = child)
            }
            5 -> skip(number, 4)
            else -> throw IllegalArgumentException("Unsupported protobuf wire type")
        }
    }

    fun text(limit: Int): String = bytes.decodeToString(position, minOf(end, position + limit))

    private fun skip(
        number: Int,
        length: Int,
    ): NativeProtoField {
        require(length <= end - position)
        position += length
        return NativeProtoField(number)
    }

    private fun varint(): Long {
        var value = 0L
        for (index in 0..9) {
            require(position < end)
            val byte = bytes[position++].toInt() and 255
            require(index < 9 || byte <= 1)
            value = value or ((byte and 127).toLong() shl (7 * index))
            if (byte < 128) return value
        }
        throw IllegalArgumentException("Invalid protobuf varint")
    }

    private class Budget {
        var remaining = 16384
    }
}
