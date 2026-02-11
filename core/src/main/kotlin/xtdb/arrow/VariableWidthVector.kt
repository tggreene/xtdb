package xtdb.arrow

import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.util.ArrowBufPointer
import org.apache.arrow.vector.BaseVariableWidthVector
import org.apache.arrow.vector.ValueVector
import org.apache.arrow.vector.ipc.message.ArrowFieldNode
import xtdb.arrow.VectorIndirection.Companion.Selection
import xtdb.arrow.VectorIndirection.Companion.Slice
import xtdb.arrow.VectorType.Scalar
import xtdb.util.Hasher
import java.nio.ByteBuffer

abstract class VariableWidthVector : MonoVector() {

    override val vectors: Iterable<Vector> = emptyList()
    override val monoType get() = Scalar(arrowType)

    internal abstract val al: BufferAllocator
    internal abstract var validityBuffer: BitBuffer?
    internal abstract val offsetBuffer: ExtensibleBuffer
    internal abstract val dataBuffer: ExtensibleBuffer

    override var nullable: Boolean
        get() = validityBuffer != null
        set(value) {
            if (value && validityBuffer == null)
                BitBuffer(al).also { validityBuffer = it }.writeOnes(valueCount)
        }

    private var lastOffset: Int = 0

    // When non-null, stores per-value (start, end) pairs into the data buffer,
    // bypassing the standard arrow offset buffer. Used by setBytes for random-access writes
    // (e.g. GROUP BY accumulators for MIN/MAX on strings).
    private var setValuesOffsets: IntArray? = null

    override fun isNull(idx: Int) = nullable && !validityBuffer!!.getBoolean(idx)

    private fun writeOffset(newOffset: Int) {
        if (valueCount == 0) offsetBuffer.writeInt(0)
        offsetBuffer.writeInt(newOffset)
        lastOffset = newOffset
    }

    override fun writeUndefined() {
        writeOffset(lastOffset)
        validityBuffer?.writeBit(valueCount, 0)
        valueCount++
    }

    private fun writeNotNull(len: Int) {
        writeOffset(lastOffset + len)
        validityBuffer?.writeBit(valueCount, 1)
        valueCount++
    }

    override fun ensureCapacity(valueCount: Int) {
        if (valueCount > this.valueCount) {
            validityBuffer?.ensureCapacity(valueCount)
            val svo = setValuesOffsets
            if (svo != null && svo.size < valueCount * 2) {
                setValuesOffsets = svo.copyOf(maxOf(svo.size * 2, valueCount * 2))
            }
            this.valueCount = valueCount
        }
    }

    override fun setNull(idx: Int) {
        ensureCapacity(idx + 1)
        validityBuffer?.setBit(idx, 0)
    }

    override fun setBytes(idx: Int, v: ByteBuffer) {
        ensureCapacity(idx + 1)
        validityBuffer?.setBit(idx, 1)

        var svo = setValuesOffsets
        if (svo == null) {
            svo = IntArray(maxOf(valueCount * 2, 16))
            setValuesOffsets = svo
        } else if (svo.size < (idx + 1) * 2) {
            svo = svo.copyOf(maxOf(svo.size * 2, (idx + 1) * 2))
            setValuesOffsets = svo
        }

        val start = lastOffset
        val len = v.remaining()
        dataBuffer.writeBytes(v.duplicate())
        lastOffset = start + len

        svo[idx * 2] = start
        svo[idx * 2 + 1] = start + len
    }

    override fun getBytes(idx: Int): ByteBuffer {
        val svo = setValuesOffsets
        if (svo != null) {
            val start = svo[idx * 2]
            val end = svo[idx * 2 + 1]
            return dataBuffer.getBytes(start, end - start)
        }
        val start = offsetBuffer.getInt(idx)
        val end = offsetBuffer.getInt(idx + 1)
        return dataBuffer.getBytes(start, end - start)
    }

    override fun writeBytes(v: ByteBuffer) {
        writeNotNull(v.remaining())
        dataBuffer.writeBytes(v.duplicate())
    }

    override fun writeBytes(v: ByteArray) {
        writeNotNull(v.size)
        dataBuffer.writeBytes(v)
    }

    override fun writeValue0(v: ValueReader) = writeBytes(v.readBytes())

    override fun getPointer(idx: Int, reuse: ArrowBufPointer): ArrowBufPointer {
        val svo = setValuesOffsets
        if (svo != null) {
            val start = svo[idx * 2]
            val end = svo[idx * 2 + 1]
            return dataBuffer.getPointer(start, end - start, reuse)
        }
        val start = offsetBuffer.getInt(idx)
        return dataBuffer.getPointer(start, offsetBuffer.getInt(idx + 1) - start, reuse)
    }

    protected fun getByteArray(idx: Int): ByteArray {
        val buf = getBytes(idx)
        return ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
    }

    override fun hashCode0(idx: Int, hasher: Hasher): Int {
        val svo = setValuesOffsets
        if (svo != null) {
            val start = svo[idx * 2]
            val end = svo[idx * 2 + 1]
            return dataBuffer.hashCode(hasher, start, end - start)
        }
        val start = offsetBuffer.getInt(idx)
        val end = offsetBuffer.getInt(idx + 1)
        return dataBuffer.hashCode(hasher, start, end - start)
    }

    /**
     * Flushes set-mode values into proper arrow offset/data layout.
     * Must be called before openSlice if setBytes has been used.
     */
    protected fun compactSetValues() {
        val svo = setValuesOffsets ?: return

        val values = Array<ByteArray?>(valueCount) { i ->
            if (isNull(i)) null
            else {
                val start = svo[i * 2]
                val end = svo[i * 2 + 1]
                val buf = dataBuffer.getBytes(start, end - start)
                ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
            }
        }

        dataBuffer.clear()
        offsetBuffer.clear()

        var currentOffset = 0
        offsetBuffer.writeInt(currentOffset)

        for (v in values) {
            if (v != null) {
                dataBuffer.writeBytes(v)
                currentOffset += v.size
            }
            offsetBuffer.writeInt(currentOffset)
        }

        lastOffset = currentOffset
        setValuesOffsets = null
    }

    override fun rowCopier0(src: VectorReader): RowCopier {
        check(src is VariableWidthVector)
        nullable = nullable || src.nullable

        return object : RowCopier {
            // duplicated here because accessing in the outer class was non-negligible in the profiler
            private val srcValidity = src.validityBuffer
            private val srcOffset = src.offsetBuffer
            private val srcData = src.dataBuffer
            private val destValidity = this@VariableWidthVector.validityBuffer
            private val destOffset = this@VariableWidthVector.offsetBuffer
            private val destData = this@VariableWidthVector.dataBuffer

            private fun ensureWriteable(len: Int, dataBytes: Int) {
                destValidity?.ensureWritable(len)
                destOffset.ensureWritable(((len + 1) * Integer.BYTES).toLong())
                destData.ensureWritable(dataBytes.toLong())
            }

            private fun unsafeCopyRange(startIdx: Int, len: Int) {
                // offsets
                if (valueCount == 0) destOffset.unsafeWriteInt(0)

                val srcStartOffset = srcOffset.getInt(startIdx)
                val srcEndOffset = srcOffset.getInt(startIdx + len)
                val offsetDelta = lastOffset - srcStartOffset

                repeat(len) {
                    val offset = srcOffset.getInt(startIdx + it + 1) + offsetDelta
                    destOffset.unsafeWriteInt(offset)
                }

                // data
                val dataLen = srcEndOffset - srcStartOffset
                destData.unsafeWriteBytes(srcData, srcStartOffset.toLong(), dataLen.toLong())
                lastOffset += dataLen

                valueCount += len
            }

            override fun copyRow(srcIdx: Int) {
                ensureWriteable(1, srcOffset.getInt(srcIdx + 1) - srcOffset.getInt(srcIdx))
                destValidity?.writeBits(srcValidity, Slice(srcIdx, 1))

                unsafeCopyRange(srcIdx, 1)
            }

            override fun copyRows(sel: IntArray) {
                var totalDataBytes = 0
                for (srcIdx in sel) {
                    val startOffset = srcOffset.getInt(srcIdx)
                    val endOffset = srcOffset.getInt(srcIdx + 1)
                    totalDataBytes += endOffset - startOffset
                }

                ensureWriteable(sel.size, totalDataBytes)

                destValidity?.writeBits(srcValidity, Selection(sel))

                for (srcIdx in sel)
                    unsafeCopyRange(srcIdx, 1)
            }

            override fun copyRange(startIdx: Int, len: Int) {
                if (len <= 0) return

                val totalDataBytes = srcOffset.getInt(startIdx + len) - srcOffset.getInt(startIdx)

                ensureWriteable(len, totalDataBytes)

                destValidity?.writeBits(srcValidity, Slice(startIdx, len))

                unsafeCopyRange(startIdx, len)
            }
        }
    }

    override fun unloadPage(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) {
        compactSetValues()
        nodes.add(ArrowFieldNode(valueCount.toLong(), if (nullable) -1 else 0))
        if (nullable) validityBuffer?.unloadBuffer(buffers) else buffers.add(al.empty)
        offsetBuffer.unloadBuffer(buffers)
        dataBuffer.unloadBuffer(buffers)
    }

    override fun loadPage(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) {
        val node = nodes.removeFirstOrNull() ?: error("missing node")
        valueCount = node.length

        val validityBuf = buffers.removeFirstOrNull() ?: error("missing validity buffer")
        validityBuffer?.loadBuffer(validityBuf, valueCount)
        offsetBuffer.loadBuffer(buffers.removeFirstOrNull() ?: error("missing offset buffer"))
        dataBuffer.loadBuffer(buffers.removeFirstOrNull() ?: error("missing data buffer"))
    }

    override fun loadFromArrow(vec: ValueVector) {
        require(vec is BaseVariableWidthVector)

        validityBuffer?.loadBuffer(vec.validityBuffer, vec.valueCount)
        offsetBuffer.loadBuffer(vec.offsetBuffer)
        dataBuffer.loadBuffer(vec.dataBuffer)

        valueCount = vec.valueCount
    }

    override fun clear() {
        validityBuffer?.clear()
        offsetBuffer.clear()
        dataBuffer.clear()
        valueCount = 0
        lastOffset = 0
        setValuesOffsets = null
    }

    override fun close() {
        validityBuffer?.close()
        offsetBuffer.close()
        dataBuffer.close()
    }
}