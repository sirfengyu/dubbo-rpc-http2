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

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.exchange.Request;

import java.util.Map;

import static io.netty.handler.codec.http2.Http2CodecUtil.getEmbeddedHttp2Exception;

public abstract class AbstractHttp2CodecHandler extends Http2ConnectionHandler {

    static final Object NOOP_MESSAGE = new Object();
    private static final long BDP_MEASUREMENT_PING = 1234;
    protected URL url;
    private int initialConnectionWindow;
    private ChannelHandlerContext ctx;
    private boolean autoTuneFlowControlOn = false;

    public AbstractHttp2CodecHandler(URL url,
                                     Http2ConnectionDecoder decoder,
                                     Http2ConnectionEncoder encoder,
                                     Http2Settings initialSettings) {
        super(decoder, encoder, initialSettings);
        this.url = url;
        this.initialConnectionWindow = initialSettings.initialWindowSize() == null ? -1 :
                initialSettings.initialWindowSize();
    }

    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        super.handlerAdded(ctx);
        sendInitialConnectionWindow();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        sendInitialConnectionWindow();
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Http2Exception embedded = getEmbeddedHttp2Exception(cause);
        if (embedded == null) {
            // There was no embedded Http2Exception, assume it's a connection error. Subclasses are
            // responsible for storing the appropriate status and shutting down the connection.
            onError(ctx, false, cause);
        } else {
            super.exceptionCaught(ctx, cause);
        }
    }

    /**
     * Sends initial connection window to the remote endpoint if necessary.
     */
    private void sendInitialConnectionWindow() throws Http2Exception {
        if (ctx.channel().isActive() && initialConnectionWindow > 0) {
            Http2Stream connectionStream = connection().connectionStream();
            int currentSize = connection().local().flowController().windowSize(connectionStream);
            int delta = initialConnectionWindow - currentSize;
            decoder().flowController().incrementWindowSize(connectionStream, delta);
            initialConnectionWindow = -1;
            ctx.flush();
        }
    }

    protected Http2Stream requireHttp2Stream(int streamId) {
        Http2Stream stream = connection().stream(streamId);
        if (stream == null) {
            // This should never happen.
            throw new AssertionError("Stream does not exist: " + streamId);
        }
        return stream;
    }

    protected Http2Headers prepareHeaders(Request request, Payload payload) {
        Http2Headers headers = new DefaultHttp2Headers();

        String contentType = payload.header(Constants.HTTP2_CONTENT_TYPE_KEY, true);
        headers.add(Constants.HTTP2_CONTENT_TYPE_KEY, contentType == null ? Constants.HTTP2_DUBBO_CONTENT_TYPE : contentType);

        if (!request.isEvent()) {
            headers.method(Constants.POST_KEY)
                    .path("/" + payload.header(Constants.HTTP2_SERVICE_KEY, true) +
                            "/" + payload.header(Constants.HTTP2_SERVICE_METHOD_KEY, true))
                    .scheme(payload.header(Constants.HTTP2_SCHEME_KEY, true));

            if (payload.attachments() != null) {
                for (Map.Entry<String, String> attachment : payload.attachments().entrySet()) {
                    headers.add(attachment.getKey(), attachment.getValue());
                }
            }
        } else {
            headers.method(Constants.GET_KEY)
                    .path(Constants.EVENT_PATH);
        }
        return headers;
    }

    protected final ChannelHandlerContext ctx() {
        return ctx;
    }

    protected boolean isDubboContentType(String contentType) {
        if (contentType == null) {
            return false;
        }

        contentType = contentType.toLowerCase();
        if (!contentType.startsWith(Constants.HTTP2_DUBBO_CONTENT_TYPE)) {
            return false;
        }

        if (contentType.length() == Constants.HTTP2_DUBBO_CONTENT_TYPE.length()) {
            return true;
        }

        char nextChar = contentType.charAt(Constants.HTTP2_DUBBO_CONTENT_TYPE.length());
        return nextChar == '+' || nextChar == ';';
    }

    public void handleProtocolNegotiationCompleted(Object event) {

    }
}
