/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.transport.netty4.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.AttributeKey;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Payload {

    public static final String HTTP2_KEY = "http2.payload";
    public static final String HTTP2_STREAM_ID_KEY = "stream.id";

    public static final AttributeKey<Payload> KEY = AttributeKey.valueOf(HTTP2_KEY);

    public static final AttributeKey<Integer> STREAM_ID_KEY = AttributeKey.valueOf(HTTP2_STREAM_ID_KEY);

    // request/response id -> streamId
    public static final ConcurrentHashMap<Long, Payload> ID_PAYLOAD_MAP = new ConcurrentHashMap<Long, Payload>();

    private int streamId;

    private Http2Stream http2Stream;
    private Http2Headers headers;
    private ByteBuf data;
    private boolean endOfStream;

    private Object message;
    private ChannelBuffer encodedBuffer;

    private Map<String, String> attachments;

    public Payload(Object message, ChannelBuffer encodedBuffer) {
        this.message = message;
        this.encodedBuffer = encodedBuffer;
    }

    public Payload(Http2Stream http2Stream, Http2Headers headers) {
        this.http2Stream = http2Stream;
        this.headers = headers;
        this.data = data;
    }

    public Payload http2Stream(Http2Stream stream) {
        this.http2Stream = stream;
        return this;
    }

    public Payload http2Headers(Http2Headers headers) {
        this.headers = headers;
        return this;
    }

    public Payload data(ByteBuf data) {
        this.data = data;
        return this;
    }

    public Http2Stream http2Stream() {
        return this.http2Stream;
    }

    public Http2Headers http2Headers() {
        return this.headers;
    }

    public Map<String, String> attachments() {
        if (attachments == null) attachments = new HashMap<String, String>();
        return attachments;
    }

    public Payload attachments(Map<String, String> attachments) {
        if (attachments != this.attachments) {
            this.attachments = attachments;
        }
        return this;
    }

    public ByteBuf data() {
        return this.data;
    }

    public Payload endOfStream(boolean endOfStream) {
        this.endOfStream = endOfStream;
        return this;
    }

    public Object message() {
        return message;
    }

    public Payload message(Object message) {
        this.message = message;
        return this;
    }

    public ChannelBuffer encodedBuffer() {
        return encodedBuffer;
    }

    public Payload encodedBuffer(ChannelBuffer encodedBuffer) {
        this.encodedBuffer = encodedBuffer;
        return this;
    }

    public boolean endOfStream() {
        return this.endOfStream;
    }

    public Payload addAttachment(String key, String value) {
        this.attachments().put(key, value);
        return this;
    }

    public String header(String key) {
        return this.attachments().get(key);
    }

    public String header(String key, boolean remove) {
        if (remove) {
            return this.attachments().remove(key);
        }
        return this.attachments().get(key);
    }

    public int streamId() {
        return streamId;
    }

    public Payload streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public String toString() {
        return "Payload{" +
                "streamId:" + streamId +
                ", headers:" + (headers == null ? "" : headers) +
                ", data:" + (data == null ? "" : data) +
                ", message:" + (message == null ? "" : message) +
                ", attachments:" + (attachments == null ? "" : attachments) + '}';
    }
}
