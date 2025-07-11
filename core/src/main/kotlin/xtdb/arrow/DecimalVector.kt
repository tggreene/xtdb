package xtdb.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.ArrowType.Decimal
import xtdb.api.query.IKeyFn
import xtdb.api.query.IKeyFn.KeyFn.KEBAB_CASE_KEYWORD
import xtdb.arrow.metadata.MetadataFlavour
import xtdb.error.Unsupported
import xtdb.util.Hasher
import xtdb.vector.DECIMAL_ERROR_KEY
import java.math.BigDecimal

class DecimalVector private constructor(
    override var name: String, override var nullable: Boolean, override var valueCount: Int,
    override val validityBuffer: ExtensibleBuffer, override val dataBuffer: ExtensibleBuffer,
    private val decimalType: Decimal
) : FixedWidthVector(), MetadataFlavour.Number {

    companion object {
        private val BIT_WIDTHS = setOf(32, 64, 128, 256)
    }

    val precision = decimalType.precision
    val scale = decimalType.scale
    val bitWidth = decimalType.bitWidth

    init {
        require(bitWidth in BIT_WIDTHS) { "Invalid bit width for DecimalVector: $bitWidth" }
    }

    constructor(al: BufferAllocator, name: String, nullable: Boolean, decimalType: Decimal)
            : this(name, nullable, 0, ExtensibleBuffer(al), ExtensibleBuffer(al), decimalType)

    override val byteWidth = (bitWidth / 8)

    override val type: ArrowType = Decimal(precision, scale, bitWidth)

    override fun getObject0(idx: Int, keyFn: IKeyFn<*>): BigDecimal =
        dataBuffer.readBigDecimal(idx, scale, byteWidth)

    override fun writeObject0(value: Any) {
        if (value is BigDecimal) {
            if (value.precision() > precision || value.scale() != scale) {
                throw InvalidWriteObjectException(fieldType, value)
            }

            // HACK, we throw unsupported here, but it should likely be dealt with in the EE if an object doesn't fit
            try {
                dataBuffer.writeBigDecimal(value, byteWidth)
            } catch (e: UnsupportedOperationException) {
                throw Unsupported(e.message, DECIMAL_ERROR_KEY, cause = e)
            }
            writeNotNull()
        } else throw InvalidWriteObjectException(fieldType, value)
    }

    override fun writeValue0(v: ValueReader) = writeObject(v.readObject())

    override fun getMetaDouble(idx: Int): Double = getObject0(idx, KEBAB_CASE_KEYWORD).toDouble()

    override fun hashCode0(idx: Int, hasher: Hasher) = hasher.hash(getObject0(idx, KEBAB_CASE_KEYWORD).toDouble())

    override fun openSlice(al: BufferAllocator) =
        DecimalVector(name, nullable, valueCount, validityBuffer.openSlice(al), dataBuffer.openSlice(al), decimalType)
}