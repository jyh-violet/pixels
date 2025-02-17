/*
 * Copyright 2017-2019 PixelsDB.
 *
 * This file is part of Pixels.
 *
 * Pixels is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Pixels is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public
 * License along with Pixels.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package io.pixelsdb.pixels.core.vector;

import io.pixelsdb.pixels.core.utils.Bitmap;

import java.nio.charset.StandardCharsets;

import static com.google.common.base.Preconditions.checkArgument;
import static io.pixelsdb.pixels.common.utils.JvmUtils.unsafe;
import static io.pixelsdb.pixels.core.utils.BitUtils.longBytesToLong;
import static java.util.Objects.requireNonNull;
import static sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;

/**
 * BinaryColumnVector derived from org.apache.hadoop.hive.ql.exec.vector.
 * <p>
 * This class supports string and binary data by value reference -- i.e. each field is
 * explicitly present, as opposed to provided by a dictionary reference.
 * In some cases, all the values will be in the same byte array to begin with,
 * but this need not be the case. If each value is in a separate byte
 * array to start with, or not all of the values are in the same original
 * byte array, you can still assign data by reference into this column vector.
 * This gives flexibility to use this in multiple situations.
 * <p>
 * When setting data by reference, the caller
 * is responsible for allocating the byte arrays used to hold the data.
 * You can also set data by value, as long as you call the initBuffer() method first.
 * You can mix "by value" and "by reference" in the same column vector,
 * though that use is probably not typical.
 */
public class BinaryColumnVector extends ColumnVector
{
    public byte[][] vector;
    public int[] start;          // start offset of each field

    /*
     * The length of each field. If the value repeats for every entry, then it is stored
     * in vector[0] and isRepeating from the superclass is set to true.
     */
    public int[] lens;

    // A call to increaseBufferSpace() or ensureValPreallocated() will ensure that buffer[] points to
    // a byte[] with sufficient space for the specified size.
    public byte[] buffer;   // optional buffer to use when actually copying in data
    private int nextFree;    // next free position in buffer

    // Hang onto a byte array for holding smaller byte values
    private byte[] smallBuffer;
    private int smallBufferNextFree;

    private int bufferAllocationCount;

    // Estimate that there will be 16 bytes per entry
    static final int DEFAULT_BUFFER_SIZE = 16 * VectorizedRowBatch.DEFAULT_SIZE;

    // Proportion of extra space to provide when allocating more buffer space.
    static final float EXTRA_SPACE_FACTOR = (float) 1.2;

    // Largest size allowed in smallBuffer
    static final int MAX_SIZE_FOR_SMALL_BUFFER = 1024 * 1024;

    /**
     * Use this constructor for normal operation.
     * All column vectors should be the default size normally.
     */
    public BinaryColumnVector()
    {
        this(VectorizedRowBatch.DEFAULT_SIZE);
    }

    /**
     * Don't call this constructor except for testing purposes.
     *
     * @param size number of elements in the column vector
     */
    public BinaryColumnVector(int size)
    {
        super(size);
        vector = new byte[size][];
        start = new int[size];
        lens = new int[size];
        memoryUsage += Integer.BYTES * (size * 2 + 2);
    }

    /**
     * Additional reset work for BinaryColumnVector (releasing scratch bytes for by value strings).
     */
    @Override
    public void reset()
    {
        super.reset();
        /**
         * Issue #140:
         * Temporarily comment out this to avoid null pointer exception.
         * FIXME: use encoded column vectors (i.e. lazy encoding) instead of decoded ones.
         */
        // Arrays.fill(vector, null);
        initBuffer(0);
    }

    /**
     * Set a field by reference.
     *
     * @param elementNum index within column vector to set
     * @param sourceBuf  container of source data
     * @param start      start byte position within source
     * @param length     length of source byte sequence
     */
    public void setRef(int elementNum, byte[] sourceBuf, int start, int length)
    {
        if (elementNum >= writeIndex)
        {
            writeIndex = elementNum + 1;
        }
        this.vector[elementNum] = sourceBuf;
        this.start[elementNum] = start;
        this.lens[elementNum] = length;
        this.isNull[elementNum] = sourceBuf == null;
        if (sourceBuf == null)
        {
            this.noNulls = false;
        }
    }

    /**
     * You must call initBuffer first before using setVal().
     * Provide the estimated number of bytes needed to hold
     * a full column vector worth of byte string data.
     *
     * @param estimatedValueSize Estimated size of buffer space needed
     */
    public void initBuffer(int estimatedValueSize)
    {
        nextFree = 0;
        smallBufferNextFree = 0;

        // if buffer is already allocated, keep using it, don't re-allocate
        if (buffer != null)
        {
            // Free up any previously allocated buffers that are referenced by vector
            if (bufferAllocationCount > 0)
            {
                for (int idx = 0; idx < vector.length; ++idx)
                {
                    vector[idx] = null;
                }
                buffer = smallBuffer; // In case last row was a large bytes value
            }
        }
        else
        {
            // allocate a little extra space to limit need to re-allocate
            int bufferSize = this.vector.length * (int) (estimatedValueSize * EXTRA_SPACE_FACTOR);
            if (bufferSize < DEFAULT_BUFFER_SIZE)
            {
                bufferSize = DEFAULT_BUFFER_SIZE;
            }
            buffer = new byte[bufferSize];
            memoryUsage += Byte.BYTES * bufferSize;
            smallBuffer = buffer;
        }
        bufferAllocationCount = 0;
    }

    /**
     * Initialize buffer to default size.
     */
    public void initBuffer()
    {
        initBuffer(0);
    }

    /**
     * @return amount of buffer space currently allocated
     */
    public int bufferSize()
    {
        if (buffer == null)
        {
            return 0;
        }
        return buffer.length;
    }

    @Override
    public void add(byte[] v)
    {
        if (writeIndex >= getLength())
        {
            ensureSize(writeIndex * 2, true);
        }
        setVal(writeIndex++, v);
    }

    @Override
    public void add(String value)
    {
        add(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int[] accumulateHashCode(int[] hashCode)
    {
        requireNonNull(hashCode, "hashCode is null");
        checkArgument(hashCode.length > 0 && hashCode.length <= this.length, "",
                "the length of hashCode is not in the range [1, length]");
        for (int i = 0; i < hashCode.length; ++i)
        {
            if (this.isNull[i])
            {
                continue;
            }
            int h = 1, len = this.lens[i];
            long address = ARRAY_BYTE_BASE_OFFSET + this.start[i], word;
            while (len >= Long.BYTES)
            {
                word = unsafe.getLong(this.vector[i], address);
                h = 31 * h + (int) (word ^ word >>> 32);
                address += Long.BYTES;
                len -= Long.BYTES;
            }
            int offset = (int) (address - ARRAY_BYTE_BASE_OFFSET);
            while (len-- > 0)
            {
                h = h * 31 + (int) this.vector[i][offset++];
            }
            hashCode[i] = 31 * hashCode[i] + h;
        }
        return hashCode;
    }

    @Override
    public boolean elementEquals(int thisIndex, int thatIndex, ColumnVector thatVector)
    {
        BinaryColumnVector that = (BinaryColumnVector) thatVector;
        if (!this.isNull[thisIndex] && !that.isNull[thatIndex])
        {
            if (this.lens[thisIndex] != that.lens[thatIndex])
            {
                return false;
            }

            int thisStart = this.start[thisIndex], thatStart = that.start[thatIndex];
            if (this.vector[thisIndex] == that.vector[thatIndex] && thisStart == thatStart)
            {
                return true;
            }

            int compareLen = this.lens[thisIndex];
            long thisAddress = ARRAY_BYTE_BASE_OFFSET + thisStart;
            long thatAddress = ARRAY_BYTE_BASE_OFFSET + thatStart;
            long thisWord, thatWord;

            while (compareLen >= Long.BYTES)
            {
                thisWord = unsafe.getLong(this.vector[thisIndex], thisAddress);
                thatWord = unsafe.getLong(that.vector[thatIndex], thatAddress);
                if (thisWord != thatWord)
                {
                    return false;
                }
                thisAddress += Long.BYTES;
                thatAddress += Long.BYTES;
                compareLen -= Long.BYTES;
            }

            thisStart = (int) (thisAddress - ARRAY_BYTE_BASE_OFFSET);
            thatStart = (int) (thatAddress - ARRAY_BYTE_BASE_OFFSET);

            while (compareLen-- > 0)
            {
                if (this.vector[thisIndex][thisStart++] != that.vector[thatIndex][thatStart++])
                {
                    return false;
                }
            }

            return true;
        }
        return false;
    }

    @Override
    public int compareElement(int thisIndex, int thatIndex, ColumnVector thatVector)
    {
        BinaryColumnVector that = (BinaryColumnVector) thatVector;
        if (!this.isNull[thisIndex] && !that.isNull[thatIndex])
        {
            int thisStart = this.start[thisIndex], thatStart = that.start[thatIndex];
            int thisLen = this.lens[thisIndex], thatLen = that.lens[thatIndex];
            if (this.vector[thisIndex] == that.vector[thatIndex] && thisStart == thatStart && thisLen == thatLen)
            {
                return 0;
            }

            int compareLen = Math.min(thisLen, thatLen);
            long thisAddress = ARRAY_BYTE_BASE_OFFSET + thisStart;
            long thatAddress = ARRAY_BYTE_BASE_OFFSET + thatStart;
            long thisWord, thatWord;

            while (compareLen >= Long.BYTES)
            {
                thisWord = unsafe.getLong(this.vector[thisIndex], thisAddress);
                thatWord = unsafe.getLong(that.vector[thatIndex], thatAddress);
                if (thisWord != thatWord)
                {
                    return longBytesToLong(thisWord) < longBytesToLong(thatWord) ? -1 : 1;
                }
                thisAddress += Long.BYTES;
                thatAddress += Long.BYTES;
                compareLen -= Long.BYTES;
            }

            thisStart = (int) (thisAddress - ARRAY_BYTE_BASE_OFFSET);
            thatStart = (int) (thatAddress - ARRAY_BYTE_BASE_OFFSET);

            int c;
            while (compareLen-- > 0)
            {
                c = (this.vector[thisIndex][thisStart++] & 0xFF) - (that.vector[thatIndex][thatStart++] & 0xFF);
                if (c != 0)
                {
                    return c;
                }
            }

            return Integer.compare(thisLen, thatLen);
        }
        return this.isNull[thisIndex] ? -1 : 1;
    }

    /**
     * Set a field by actually copying in to a local buffer.
     * If you must actually copy data in to the array, use this method.
     * DO NOT USE this method unless it's not practical to set data by reference with setRef().
     * Setting data by reference tends to run a lot faster than copying data in.
     *
     * @param elementNum index within column vector to set
     * @param sourceBuf  container of source data
     * @param start      start byte position within source
     * @param length     length of source byte sequence
     */
    public void setVal(int elementNum, byte[] sourceBuf, int start, int length)
    {
        if (elementNum >= writeIndex)
        {
            writeIndex = elementNum + 1;
        }
        if ((nextFree + length) > buffer.length)
        {
            increaseBufferSpace(length);
        }
        System.arraycopy(sourceBuf, start, buffer, nextFree, length);
        this.vector[elementNum] = buffer;
        this.start[elementNum] = nextFree;
        this.lens[elementNum] = length;
        this.isNull[elementNum] = false;
        nextFree += length;
    }

    /**
     * Set a field by actually copying in to a local buffer.
     * If you must actually copy data in to the array, use this method.
     * DO NOT USE this method unless it's not practical to set data by reference with setRef().
     * Setting data by reference tends to run a lot faster than copying data in.
     *
     * @param elementNum index within column vector to set
     * @param sourceBuf  container of source data
     */
    public void setVal(int elementNum, byte[] sourceBuf)
    {
        setVal(elementNum, sourceBuf, 0, sourceBuf.length);
    }

    /**
     * Preallocate space in the local buffer so the caller can fill in the value bytes themselves.
     * <p>
     * Always use with getValPreallocatedBytes, getValPreallocatedStart, and setValPreallocated.
     */
    public void ensureValPreallocated(int length)
    {
        if ((nextFree + length) > buffer.length)
        {
            increaseBufferSpace(length);
        }
    }

    public byte[] getValPreallocatedBytes()
    {
        return buffer;
    }

    public int getValPreallocatedStart()
    {
        return nextFree;
    }

    /**
     * Set the length of the preallocated values bytes used.
     *
     * @param elementNum
     * @param length
     */
    public void setValPreallocated(int elementNum, int length)
    {
        if (elementNum >= writeIndex)
        {
            writeIndex = elementNum + 1;
        }
        vector[elementNum] = buffer;
        start[elementNum] = nextFree;
        lens[elementNum] = length;
        isNull[elementNum] = false;
        nextFree += length;
    }

    /**
     * Set a field to the concatenation of two string values. Result data is copied
     * into the internal buffer.
     *
     * @param elementNum     index within column vector to set
     * @param leftSourceBuf  container of left argument
     * @param leftStart      start of left argument
     * @param leftLen        length of left argument
     * @param rightSourceBuf container of right argument
     * @param rightStart     start of right argument
     * @param rightLen       length of right arugment
     */
    public void setConcat(int elementNum, byte[] leftSourceBuf, int leftStart, int leftLen,
                          byte[] rightSourceBuf, int rightStart, int rightLen)
    {
        if (elementNum >= writeIndex)
        {
            writeIndex = elementNum + 1;
        }
        int newLen = leftLen + rightLen;
        if ((nextFree + newLen) > buffer.length)
        {
            increaseBufferSpace(newLen);
        }
        vector[elementNum] = buffer;
        start[elementNum] = nextFree;
        lens[elementNum] = newLen;
        isNull[elementNum] = false;

        System.arraycopy(leftSourceBuf, leftStart, buffer, nextFree, leftLen);
        nextFree += leftLen;
        System.arraycopy(rightSourceBuf, rightStart, buffer, nextFree, rightLen);
        nextFree += rightLen;
    }

    /**
     * Increase buffer space enough to accommodate next element.
     * This uses an exponential increase mechanism to rapidly
     * increase buffer size to enough to hold all data.
     * As batches get re-loaded, buffer space allocated will quickly
     * stabilize.
     *
     * @param nextElemLength size of next element to be added
     */
    public void increaseBufferSpace(int nextElemLength)
    {
        // A call to increaseBufferSpace() or ensureValPreallocated() will ensure that buffer[] points to
        // a byte[] with sufficient space for the specified size.
        // This will either point to smallBuffer, or to a newly allocated byte array for larger values.

        if (nextElemLength > MAX_SIZE_FOR_SMALL_BUFFER)
        {
            // Larger allocations will be special-cased and will not use the normal buffer.
            // buffer/nextFree will be set to a newly allocated array just for the current row.
            // The next row will require another call to increaseBufferSpace() since this new buffer should be used up.
            byte[] newBuffer = new byte[nextElemLength];
            memoryUsage += Byte.BYTES * nextElemLength;
            ++bufferAllocationCount;
            // If the buffer was pointing to smallBuffer, then nextFree keeps track of the current state
            // of the free index for smallBuffer. We now need to save this value to smallBufferNextFree
            // so we don't lose this. A bit of a weird dance here.
            if (smallBuffer == buffer)
            {
                smallBufferNextFree = nextFree;
            }
            buffer = newBuffer;
            nextFree = 0;
        }
        else
        {
            // This value should go into smallBuffer.
            if (smallBuffer != buffer)
            {
                // Previous row was for a large bytes value ( > MAX_SIZE_FOR_SMALL_BUFFER).
                // Use smallBuffer if possible.
                buffer = smallBuffer;
                nextFree = smallBufferNextFree;
            }

            // smallBuffer might still be out of space
            if ((nextFree + nextElemLength) > buffer.length)
            {
                int newLength = smallBuffer.length * 2;
                while (newLength < nextElemLength)
                {
                    if (newLength < 0)
                    {
                        throw new RuntimeException("Overflow of newLength. smallBuffer.length="
                                + smallBuffer.length + ", nextElemLength=" + nextElemLength);
                    }
                    newLength *= 2;
                }
                smallBuffer = new byte[newLength];
                memoryUsage += Byte.BYTES * newLength;
                ++bufferAllocationCount;
                smallBufferNextFree = 0;
                // Update buffer
                buffer = smallBuffer;
                nextFree = 0;
            }
        }
    }

    /**
     * Simplify vector by brute-force flattening noNulls and isRepeating
     * This can be used to reduce combinatorial explosion of code paths in VectorExpressions
     * with many arguments, at the expense of loss of some performance.
     */
    public void flatten(boolean selectedInUse, int[] sel, int size)
    {
        flattenPush();
        if (isRepeating)
        {
            isRepeating = false;

            // setRef is used below and this is safe, because the reference
            // is to data owned by this column vector. If this column vector
            // gets re-used, the whole thing is re-used together so there
            // is no danger of a dangling reference.

            // Only copy data values if entry is not null. The string value
            // at position 0 is undefined if the position 0 value is null.
            if (noNulls || !isNull[0])
            {
                // loops start at position 1 because position 0 is already set
                if (selectedInUse)
                {
                    for (int j = 1; j < size; j++)
                    {
                        int i = sel[j];
                        this.setRef(i, vector[0], start[0], lens[0]);
                    }
                }
                else
                {
                    for (int i = 1; i < size; i++)
                    {
                        this.setRef(i, vector[0], start[0], lens[0]);
                    }
                }
            }
            flattenRepeatingNulls(selectedInUse, sel, size);
        }
        flattenNoNulls(selectedInUse, sel, size);
    }

    // Fill the all the vector entries with provided value
    public void fill(byte[] value)
    {
        noNulls = true;
        isRepeating = true;
        setRef(0, value, 0, value.length);
    }

    // Fill the column vector with nulls
    public void fillWithNulls()
    {
        noNulls = false;
        isRepeating = true;
        vector[0] = null;
        isNull[0] = true;
    }

    @Override
    public void addElement(int inputIndex, ColumnVector inputVector)
    {
        int index = writeIndex++;
        if (inputVector.noNulls || !inputVector.isNull[inputIndex])
        {
            isNull[index] = false;
            BinaryColumnVector in = (BinaryColumnVector) inputVector;
            // We do not change the content of the elements in the vector, thus it is safe to setRef.
            setRef(index, in.vector[inputIndex], in.start[inputIndex], in.lens[inputIndex]);
        }
        else
        {
            isNull[index] = true;
            noNulls = false;
        }
    }

    @Override
    public void addSelected(int[] selected, int offset, int length, ColumnVector src)
    {
        // isRepeating should be false and src should be an instance of BinaryColumnVector.
        // However, we do not check these for performance considerations.
        BinaryColumnVector source = (BinaryColumnVector) src;

        for (int i = offset; i < offset + length; i++)
        {
            int srcIndex = selected[i], thisIndex = writeIndex++;
            if (source.isNull[srcIndex])
            {
                this.isNull[thisIndex] = true;
                this.noNulls = false;
            }
            else
            {
                // We do not change the content of the elements in the vector, thus it is safe to setRef.
                this.setRef(thisIndex, source.vector[srcIndex], source.start[srcIndex],
                        source.lens[srcIndex]);
            }
        }
    }

    @Override
    public void duplicate(ColumnVector inputVector)
    {
        if (inputVector instanceof BinaryColumnVector)
        {
            BinaryColumnVector srcVector = (BinaryColumnVector) inputVector;
            for (int i = 0; i < vector.length; i++)
            {
                if (srcVector.vector[i] != null)
                {
                    this.vector[i] = srcVector.vector[i];
                }
            }
//            System.arraycopy(srcVector.start, 0, this.start, 0, start.length);
//            System.arraycopy(srcVector.lens, 0, this.lens, 0, lens.length);
//            System.arraycopy(srcVector.isNull, 0, this.isNull, 0, isNull.length);
            this.start = srcVector.start;
            this.lens = srcVector.lens;
            this.isNull = srcVector.isNull;
            this.buffer = null;
            this.smallBuffer = null;
            this.nextFree = srcVector.nextFree;
            this.smallBufferNextFree = srcVector.smallBufferNextFree;
            this.bufferAllocationCount = srcVector.bufferAllocationCount;
            this.noNulls = srcVector.noNulls;
            this.isRepeating = srcVector.isRepeating;
            this.writeIndex = srcVector.writeIndex;
        }
    }

    @Override
    public void init()
    {
        initBuffer(0);
    }

    @Override
    protected void applyFilter(Bitmap filter, int before)
    {
        checkArgument(!isRepeating,
                "column vector is repeating, flatten before applying filter");
        checkArgument(before > 0 && before <= length,
                "before index is not in the range [1, length]");
        boolean noNulls = true;
        int j = 0;
        for (int i = filter.nextSetBit(0);
             i >= 0 && i < before; i = filter.nextSetBit(i+1), j++)
        {
            if (i > j)
            {
                this.vector[j] = this.vector[i];
                this.isNull[j] = this.isNull[i];
                this.start[j] = this.start[i];
                this.lens[j] = this.lens[i];
            }
            if (this.isNull[j])
            {
                noNulls = false;
            }
            /*
             * The number of rows in a row batch is impossible to reach Integer.MAX_VALUE.
             * Therefore, we do not check overflow here.
             */
        }
        this.noNulls = noNulls;
    }

    public String toString(int row)
    {
        if (isRepeating)
        {
            row = 0;
        }
        if (noNulls || !isNull[row])
        {
            return new String(vector[row], start[row], lens[row]);
        }
        else
        {
            return null;
        }
    }

    @Override
    public void stringifyValue(StringBuilder buffer, int row)
    {
        if (isRepeating)
        {
            row = 0;
        }
        if (noNulls || !isNull[row])
        {
            buffer.append('"');
            buffer.append(new String(vector[row], start[row], lens[row]));
            buffer.append('"');
        }
        else
        {
            buffer.append("null");
        }
    }

    @Override
    public void ensureSize(int size, boolean preserveData)
    {
        super.ensureSize(size, preserveData);
        if (size > vector.length)
        {
            int[] oldStart = start;
            start = new int[size];
            int[] oldLength = lens;
            lens = new int[size];
            byte[][] oldVector = vector;
            vector = new byte[size][];
            memoryUsage += Integer.BYTES * size * 2;
            length = size;
            if (preserveData)
            {
                if (isRepeating)
                {
                    vector[0] = oldVector[0];
                    start[0] = oldStart[0];
                    lens[0] = oldLength[0];
                }
                else
                {
                    System.arraycopy(oldVector, 0, vector, 0, oldVector.length);
                    System.arraycopy(oldStart, 0, start, 0, oldStart.length);
                    System.arraycopy(oldLength, 0, length, 0, oldLength.length);
                }
            }
        }
    }

    @Override
    public void close()
    {
        super.close();
        this.start = null;
        this.lens = null;
        this.buffer = null;
        this.smallBuffer = null;
        if (this.vector != null)
        {
            for (int i = 0; i < this.vector.length; ++i)
            {
                this.vector[i] = null;
            }
            this.vector = null;
        }
    }
}
