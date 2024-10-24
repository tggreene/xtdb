package xtdb.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.Types.MinorType
import org.apache.arrow.vector.types.pojo.ArrowType
import xtdb.api.query.IKeyFn

class ShortVector(
    allocator: BufferAllocator,
    override val name: String,
    override var nullable: Boolean
) : FixedWidthVector(allocator, Short.SIZE_BYTES) {

    override val arrowType: ArrowType = MinorType.SMALLINT.type

    override fun getShort(idx: Int) = getShort0(idx)
    override fun writeShort(value: Short) = writeShort0(value)

    override fun getObject0(idx: Int, keyFn: IKeyFn<*>) = getShort(idx)

    override fun writeObject0(value: Any) {
        if (value is Short) writeShort(value) else TODO("not a Short")
    }
}