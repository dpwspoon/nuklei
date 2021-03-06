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

package org.kaazing.nuklei.protocol.http;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import java.nio.ByteOrder;

import org.junit.Test;
import org.kaazing.nuklei.protocol.tcp.TcpManagerHeadersDecoder;
import org.kaazing.nuklei.protocol.tcp.TcpManagerTypeId;

import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.MutableDirectBuffer;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

public class HttpDispatcherTest
{
    private static final long CONNECTION_ID = 101;
    private static final String METHOD = "GET";
    private static final String PATH = "/path";
    private static final String SP = " ";
    private static final String COLON = ":";
    private static final String CRLF = "\r\n";
    private static final String VERSION = "HTTP/1.1";
    private static final String HOST_HEADER_NAME = "Host";
    private static final String HOST_HEADER_VALUE = "Java Unit Tests";
    private static final String BODY = "This is some text to act as body";
    private static final String CONTENT_LENGTH_HEADER_NAME = "Content-Length";

    private final HttpDispatcher dispatcher = new HttpDispatcher();
    private final MutableDirectBuffer buffer = new UnsafeBuffer(new byte[4096]);
    private TcpManagerHeadersDecoder tcpManagerHeadersDecoder = new TcpManagerHeadersDecoder(ByteOrder.nativeOrder());

    private final byte[] bytes = new byte[256];
    private final MutableDirectBuffer atomicBuffer = new UnsafeBuffer(bytes);

    private final int[] received = new int[1];

    @Test
    public void shouldProcessBasicGetRequest()
    {
        final String request =
            METHOD + SP + PATH + SP + VERSION + CRLF +
            HOST_HEADER_NAME + COLON + SP + HOST_HEADER_VALUE + CRLF +
            CRLF;

        dispatcher.addResource(
            METHOD.getBytes(),
            PATH.getBytes(),
            (header, typeId, buffer, offset, length) ->
            {
                final HttpHeadersDecoder decoder = (HttpHeadersDecoder)header;

                final int methodLen = decoder.header(HttpHeaderName.METHOD, atomicBuffer, 0);
                final String method = new String(bytes, 0, methodLen);
                final int pathLen = decoder.header(HttpHeaderName.PATH, atomicBuffer, 0);
                final String path = new String(bytes, 0, pathLen);
                final int versionLen = decoder.header(HttpHeaderName.VERSION, atomicBuffer, 0);
                final String version = new String(bytes, 0, versionLen);
                final int hostLen = decoder.header(HttpHeaderName.HOST, atomicBuffer, 0);
                final String host = new String(bytes, 0, hostLen);

                assertThat(methodLen, is(METHOD.length()));
                assertThat(method, is(METHOD));
                assertThat(pathLen, is(PATH.length()));
                assertThat(path, is(PATH));
                assertThat(versionLen, is(VERSION.length()));
                assertThat(version, is(VERSION));
                assertThat(hostLen, is(HOST_HEADER_VALUE.length()));
                assertThat(host, is(HOST_HEADER_VALUE));
                assertThat(length, is(0));
                received[0]++;
            });

        onRequest(CONNECTION_ID, request);
        assertThat(received[0], is(1));
    }

    @Test
    public void processMixedCaseHeader()
    {
        final String request =
                METHOD + SP + PATH + SP + VERSION + CRLF +
                "HOsT" + COLON + SP + HOST_HEADER_VALUE + CRLF +
                CRLF;

        dispatcher.addResource(
                METHOD.getBytes(),
                PATH.getBytes(),
                (header, typeId, buffer, offset, length) ->
                {
                    final HttpHeadersDecoder decoder = (HttpHeadersDecoder)header;

                    final int hostLen = decoder.header(HttpHeaderName.HOST, atomicBuffer, 0);
                    final String host = new String(bytes, 0, hostLen);

                    assertThat(hostLen, is(HOST_HEADER_VALUE.length()));
                    assertThat(host, is(HOST_HEADER_VALUE));

                    assertThat(length, is(0));
                    received[0]++;
                });

        onRequest(CONNECTION_ID, request);
        assertThat(received[0], is(1));
    }

    @Test
    public void shouldProcessPostWithBody()
    {
        final String request =
            METHOD + SP + PATH + SP + VERSION + CRLF +
            HOST_HEADER_NAME + COLON + SP + HOST_HEADER_VALUE + CRLF +
            CONTENT_LENGTH_HEADER_NAME + COLON + SP + BODY.length() + CRLF +
            CRLF + BODY;

        dispatcher.addResource(
            METHOD.getBytes(),
            PATH.getBytes(),
            (header, typeId, buffer, offset, length) ->
            {
                final HttpHeadersDecoder decoder = (HttpHeadersDecoder)header;

                final int hostLen = decoder.header(HttpHeaderName.HOST, atomicBuffer, 0);
                final String host = new String(bytes, 0, hostLen);
                final int contentLenLen = decoder.header(HttpHeaderName.CONTENT_LENGTH, atomicBuffer, 0);
                final String contentLen = new String(bytes, 0, contentLenLen);

                assertThat(hostLen, is(HOST_HEADER_VALUE.length()));
                assertThat(host, is(HOST_HEADER_VALUE));
                assertThat(Integer.parseInt(contentLen), is(BODY.length()));
                assertThat(length, is(BODY.length()));
                received[0]++;
            });

        onRequest(CONNECTION_ID, request);
        assertThat(received[0], is(1));
    }

    @Test
    public void shouldProcessSplitRequest()
    {
        final String requestA =
            METHOD + SP + PATH + SP + VERSION + CRLF;

        final String requestB =
            HOST_HEADER_NAME + COLON + SP + HOST_HEADER_VALUE + CRLF +
            CRLF;

        dispatcher.addResource(
            METHOD.getBytes(),
            PATH.getBytes(),
            (header, typeId, buffer, offset, length) ->
            {
                final HttpHeadersDecoder decoder = (HttpHeadersDecoder)header;

                final int hostLen = decoder.header(HttpHeaderName.HOST, atomicBuffer, 0);
                final String host = new String(bytes, 0, hostLen);

                assertThat(hostLen, is(HOST_HEADER_VALUE.length()));
                assertThat(host, is(HOST_HEADER_VALUE));
                assertThat(length, is(0));
                received[0]++;
            });

        onRequest(CONNECTION_ID, requestA);
        assertThat(received[0], is(0));

        onRequest(CONNECTION_ID, requestB);
        assertThat(received[0], is(1));
    }

    private void onRequest(final long connectionId, final String request)
    {
        final byte[] data = request.getBytes();
        final int length = BitUtil.SIZE_OF_LONG + data.length;

        buffer.putLong(0, connectionId);
        buffer.putBytes(BitUtil.SIZE_OF_LONG, data);

        tcpManagerHeadersDecoder.wrap(buffer, 0);
        dispatcher.onMessage(
            tcpManagerHeadersDecoder,
            TcpManagerTypeId.RECEIVED_DATA,
            buffer,
            BitUtil.SIZE_OF_LONG,
            length - BitUtil.SIZE_OF_LONG);
    }
}
