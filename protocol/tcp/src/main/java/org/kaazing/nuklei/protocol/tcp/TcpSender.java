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

package org.kaazing.nuklei.protocol.tcp;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.kaazing.nuklei.MessagingNukleus;
import org.kaazing.nuklei.NioSelectorNukleus;
import org.kaazing.nuklei.Nuklei;
import org.kaazing.nuklei.concurrent.MpscArrayBuffer;
import org.kaazing.nuklei.protocol.tcp.command.TcpCloseConnectionCmd;

import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.MutableDirectBuffer;
import uk.co.real_logic.agrona.concurrent.AtomicBuffer;

/**
 */
public class TcpSender
{
    private static final int MPSC_READ_LIMIT = 10;

    private final MessagingNukleus messagingNukleus;
    private final Map<Long, TcpConnection> connectionsByIdMap;
    private final ByteBuffer sendByteBuffer;
    private final MpscArrayBuffer<Object> tcpManagerCommandQueue;
    private final MpscArrayBuffer<Object> tcpReceiverCommandQueue;

    public TcpSender(
        final MpscArrayBuffer<Object> commandQueue,
        final AtomicBuffer sendBuffer,
        final NioSelectorNukleus selectorNukleus,
        final MpscArrayBuffer<Object> tcpManagerCommandQueue,
        final MpscArrayBuffer<Object> tcpReceiverCommandQueue)
    {
        final MessagingNukleus.Builder builder = new MessagingNukleus.Builder()
            .nioSelector(selectorNukleus)
            .mpscRingBuffer(sendBuffer, this::sendHandler, MPSC_READ_LIMIT)
            .mpscArrayBuffer(commandQueue, this::commandHandler, MPSC_READ_LIMIT);

        this.tcpManagerCommandQueue = tcpManagerCommandQueue;
        this.tcpReceiverCommandQueue = tcpReceiverCommandQueue;

        messagingNukleus = builder.build();
        connectionsByIdMap = new HashMap<>();
        byte[] sendByteArray = sendBuffer.byteArray();
        sendByteBuffer = (sendByteArray != null) ? ByteBuffer.wrap(sendByteArray) : sendBuffer.byteBuffer().duplicate();
        sendByteBuffer.clear();
    }

    public void launch(final Nuklei nuklei)
    {
        nuklei.spinUp(messagingNukleus);
    }

    private void commandHandler(final Object obj)
    {
        if (obj instanceof TcpConnection)
        {
            final TcpConnection connection = (TcpConnection)obj;

            connectionsByIdMap.put(connection.id(), connection);

            // pass on to receiver so it can hook things up also
            if (!tcpReceiverCommandQueue.write(connection))
            {
                throw new IllegalStateException("could not write to command queue");
            }
        }
        else if (obj instanceof TcpCloseConnectionCmd)
        {
            final TcpCloseConnectionCmd cmd = (TcpCloseConnectionCmd) obj;
            final TcpConnection connection = connectionsByIdMap.remove(cmd.connectionId());

            if (null != connection)
            {
                connection.senderClosed();
                informTcpManagerOfClose(connection);
            }
        }
    }

    private void sendHandler(final int typeId, final MutableDirectBuffer buffer, final int offset, final int length)
    {
        if (TcpManagerTypeId.SEND_DATA == typeId)
        {
            final TcpConnection connection = connectionsByIdMap.get(buffer.getLong(offset));

            final int messageOffset = offset + BitUtil.SIZE_OF_LONG;
            sendByteBuffer.limit(messageOffset + length - BitUtil.SIZE_OF_LONG);
            sendByteBuffer.position(messageOffset);

            if (null != connection)
            {
                connection.send(sendByteBuffer);
            }
        }
        else if (TcpManagerTypeId.CLOSE_CONNECTION == typeId)
        {
            final TcpConnection connection = connectionsByIdMap.remove(buffer.getLong(offset));

            if (null != connection)
            {
                connection.senderClosed();
                informTcpManagerOfClose(connection);
            }
        }
    }

    private void informTcpManagerOfClose(final TcpConnection connection)
    {
        if (!tcpManagerCommandQueue.write(connection))
        {
            throw new IllegalStateException("could not write to command queue");
        }
    }
}
