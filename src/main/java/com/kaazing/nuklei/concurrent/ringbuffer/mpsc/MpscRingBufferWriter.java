/*
 * Copyright 2014 Kaazing Corporation, All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kaazing.nuklei.concurrent.ringbuffer.mpsc;

import com.kaazing.nuklei.BitUtil;
import com.kaazing.nuklei.concurrent.AtomicBuffer;
import com.kaazing.nuklei.concurrent.ringbuffer.RingBufferWriter;

/**
 * Multiple Publisher, Single Consumer Ring Buffer Writer
 */
public class MpscRingBufferWriter implements RingBufferWriter
{
    private static final int INSUFFICIENT_CAPACITY = -1;

    private final AtomicBuffer buffer;
    private final int mask;
    private final int tailCounterIndex;
    private final int headCounterIndex;
    private final int capacity;

    /**
     * Initialize ring buffer writer with underling ring buffer in the {@link AtomicBuffer}
     *
     * @param buffer to use as the underlying ring buffer.
     */
    public MpscRingBufferWriter(final AtomicBuffer buffer)
    {
        MpscRingBuffer.checkAtomicBufferCapacity(buffer);

        this.buffer = buffer;
        this.capacity = buffer.capacity() - MpscRingBuffer.STATE_TRAILER_SIZE;
        this.mask = capacity - 1;
        this.tailCounterIndex = capacity + MpscRingBuffer.TAIL_RELATIVE_OFFSET;
        this.headCounterIndex = capacity + MpscRingBuffer.HEAD_RELATIVE_OFFSET;
    }

    /** {@inheritDoc} */
    public boolean write(final int typeId, final AtomicBuffer buffer, final int offset, final int length)
    {
        MpscRingBuffer.checkMessageTypeId(typeId);

        final int requiredCapacity = BitUtil.align(length + MpscRingBuffer.HEADER_LENGTH,
                MpscRingBuffer.MESSAGE_ALIGNMENT);
        final int messageIndex = claim(requiredCapacity);  // claim slot, padding if necessary

        if (INSUFFICIENT_CAPACITY == messageIndex)
        {
            return false;
        }

        writeMsgTypeId(messageIndex, typeId);
        writeMsg(messageIndex, buffer, offset, length);
        // TODO: write sequence number for tail value (ordered) - if needed for spy
        writeMsgLengthOrdered(messageIndex, length);

        return true;
    }

    private int claim(final int requiredCapacity)
    {
        final long head = getHeadVolatile();
        final int headIndex = (int)head & mask;

        long tail;
        int tailIndex;
        int padding;
        do
        {
            tail = getTailVolatile();
            final int availableCapacity = capacity - (int)(head - tail);

            if (requiredCapacity > availableCapacity)
            {
                return INSUFFICIENT_CAPACITY;
            }

            padding = 0;
            tailIndex = (int)tail & mask;

            final int bufferEndSize = capacity - tailIndex;

            if (requiredCapacity > bufferEndSize)
            {
                if (requiredCapacity > headIndex)
                {
                    return INSUFFICIENT_CAPACITY;
                }

                padding = bufferEndSize;
            }
        }
        while (!buffer.compareAndSwapLong(tailCounterIndex, tail, tail + requiredCapacity + padding));

        if (0 < padding)
        {
            writePaddingRecord(tailIndex, padding);
            tailIndex = 0;
        }

        return tailIndex;
    }

    private long getHeadVolatile()
    {
        return buffer.getLongVolatile(headCounterIndex);
    }

    private long getTailVolatile()
    {
        return buffer.getLongVolatile(tailCounterIndex);
    }

    private void writePaddingRecord(final int messageIndex, final int padding)
    {
        writeMsgTypeId(messageIndex, MpscRingBuffer.PADDING_MSG_TYPE_ID);
        // TODO: write sequence number ordered - if needed for spy
        writeMsgLengthOrdered(messageIndex, padding);
    }

    private void writeMsgTypeId(final int messageIndex, final int typeId)
    {
        buffer.putInt(messageIndex + MpscRingBuffer.HEADER_MSG_TYPE_OFFSET, typeId);
    }

    private void writeMsg(final int messageIndex, final AtomicBuffer buffer, final int offset, final int length)
    {
        buffer.putBytes(messageIndex + MpscRingBuffer.HEADER_LENGTH, buffer, offset, length);
    }

    private void writeMsgLengthOrdered(final int messageIndex, final int length)
    {
        buffer.putIntOrdered(messageIndex + MpscRingBuffer.HEADER_MSG_LENGTH_OFFSET,
                length + MpscRingBuffer.HEADER_LENGTH);
    }
}