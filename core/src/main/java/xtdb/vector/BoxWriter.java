package xtdb.vector;

import org.apache.arrow.vector.types.pojo.Field;

import java.nio.ByteBuffer;

abstract class BoxWriter implements IValueWriter {
    abstract IValueWriter box();

    @Override
    public void writeNull(Void nullValue) {
        box().writeNull(nullValue);
    }

    @Override
    public void writeBoolean(boolean booleanValue) {
        box().writeBoolean(booleanValue);
    }

    @Override
    public void writeByte(byte byteValue) {
        box().writeByte(byteValue);
    }

    @Override
    public void writeShort(short shortValue) {
        box().writeShort(shortValue);
    }

    @Override
    public void writeInt(int intValue) {
        box().writeInt(intValue);
    }

    @Override
    public void writeLong(long longValue) {
        box().writeLong(longValue);
    }

    @Override
    public void writeFloat(float floatValue) {
        box().writeFloat(floatValue);
    }

    @Override
    public void writeDouble(double doubleValue) {
        box().writeDouble(doubleValue);
    }

    @Override
    public void writeBytes(ByteBuffer bytesValue) {
        box().writeBytes(bytesValue);
    }

    @Override
    public void writeObject(Object objectValue) {
        box().writeObject(objectValue);
    }

    @Override
    public IValueWriter structKeyWriter(String key) {
        return box().structKeyWriter(key);
    }

    @Override
    public void startStruct() {
        box().startStruct();
    }

    @Override
    public void endStruct() {
        box().endStruct();
    }

    @Override
    public IValueWriter listElementWriter() {
        return box().listElementWriter();
    }

    @Override
    public void startList() {
        box().startList();
    }

    @Override
    public void endList() {
        box().endList();
    }

    @Override
    public IValueWriter writerForType(Object colType) {
        return box().writerForType(colType);
    }

    @Override
    public byte registerNewType(Field field) {
        return box().registerNewType(field);
    }

    @Override
    public IValueWriter writerForTypeId(byte typeId) {
        return box().writerForTypeId(typeId);
    }
}