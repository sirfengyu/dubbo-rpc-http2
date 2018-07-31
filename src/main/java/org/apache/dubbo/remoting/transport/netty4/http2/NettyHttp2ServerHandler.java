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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.DefaultHttp2LocalFlowController;
import io.netty.handler.codec.http2.DefaultHttp2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersDecoder;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2OutboundFrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2StreamVisitor;
import io.netty.handler.codec.http2.WeightedFairQueueByteDistributor;
import io.netty.handler.logging.LogLevel;
import io.netty.util.ReferenceCountUtil;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import java.net.InetSocketAddress;

import static io.netty.handler.codec.http2.DefaultHttp2LocalFlowController.DEFAULT_WINDOW_UPDATE_RATIO;


public class NettyHttp2ServerHandler extends AbstractHttp2CodecHandler {

    private final Logger logger = LoggerFactory.getLogger(NettyHttp2ServerHandler.class);

    Http2Connection.PropertyKey streamKey;

    private NettyHttp2ServerHandler(URL url,
                                    final Http2Connection connection,
                                    Http2ConnectionDecoder decoder,
                                    Http2ConnectionEncoder encoder,
                                    Http2Settings initialSettings) {
        super(url, decoder, encoder, initialSettings);

        connection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamClosed(Http2Stream stream) {
                if (logger.isDebugEnabled()) {
                    logger.debug("stream closed, streamId:" + stream.id() + " payload:" + (serverStream(stream) != null ? serverStream(stream) : ""));
                }
            }
        });

        this.streamKey = encoder.connection().newKey();
        this.decoder().frameListener(new NettyHttp2ServerHandler.FrameListener());
    }

    public static NettyHttp2ServerHandler newHandler(URL url) {
        Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, NettyHttp2ServerHandler.class);
        Http2Settings initialSettings = Http2Settings.defaultSettings();
        Http2HeadersDecoder headersDecoder = new DefaultHttp2HeadersDecoder(true, initialSettings.maxHeaderListSize());
        Http2FrameReader frameReader = new Http2InboundFrameLogger(new DefaultHttp2FrameReader(headersDecoder), frameLogger);
        Http2FrameWriter frameWriter = new Http2OutboundFrameLogger(new DefaultHttp2FrameWriter(), frameLogger);
        return newHandler(url, frameReader, frameWriter);
    }

    public static NettyHttp2ServerHandler newHandler(URL url, Http2FrameReader frameReader, Http2FrameWriter frameWriter) {
        final Http2Connection connection = new DefaultHttp2Connection(true);
        WeightedFairQueueByteDistributor dist = new WeightedFairQueueByteDistributor(connection);
        dist.allocationQuantum(16 * 1024);
        DefaultHttp2RemoteFlowController controller =
                new DefaultHttp2RemoteFlowController(connection, dist);
        connection.remote().flowController(controller);

        // Create the local flow controller configured to auto-refill the connection window.
        connection.local().flowController(
                new DefaultHttp2LocalFlowController(connection, DEFAULT_WINDOW_UPDATE_RATIO, true));

        Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(connection, frameWriter);
        Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder, frameReader);

        Http2Settings settings = new Http2Settings();
        return new NettyHttp2ServerHandler(url, connection, decoder, encoder, settings);
    }

    private void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, boolean endStream) throws Http2Exception {
        try {
            CharSequence path = headers.path();
            if (path == null) {
                respondWithHttpError(ctx, streamId, "Request path is missing, expect patten '/service/method'");
                return;
            }

            if (path.charAt(0) != '/') {
                respondWithHttpError(ctx, streamId, String.format("Expected path to start with /: %s", path));
                return;
            }

            if (!path.toString().equals(Constants.EVENT_PATH)) {
                // Verify that the Content-Type is correct in the request.
                CharSequence contentType = headers.get(Constants.HTTP2_CONTENT_TYPE_KEY);
                if (contentType == null) {
                    respondWithHttpError(ctx, streamId, "Content-Type is missing from the request");
                    return;
                }

                String contentTypeString = contentType.toString();
                if (!isDubboContentType(contentTypeString)) {
                    respondWithHttpError(ctx, streamId, String.format("Content-Type '%s' is not supported", contentTypeString));
                    return;
                }

                if (!Constants.POST_KEY.equals(headers.method().toString()) && !Constants.GET_KEY.equals(headers.method().toString())) {
                    respondWithHttpError(ctx, streamId, String.format("Method '%s' is not supported", headers.method()));
                    return;
                }
            }

            Http2Stream http2Stream = requireHttp2Stream(streamId);
            Payload payload = new Payload(http2Stream, headers);
            payload.streamId(streamId).endOfStream(endStream);
            http2Stream.setProperty(streamKey, payload);

            if (logger.isDebugEnabled()) {
                logger.debug("received headers:" + headers + ", streamId:" + streamId + " channel:" + ctx.channel()
                        + " hash:" + System.identityHashCode(ctx.channel()));
            }
        } catch (Exception e) {
            logger.warn("Unexpected onHeaderRead, streamId:" + streamId, e);
            throw Http2Exception.streamError(
                    streamId, Http2Error.INTERNAL_ERROR, e, StringUtils.nullToEmpty(e.getMessage()));
        }
    }

    private void onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
            throws Http2Exception {
        try {
            Channel channel = ctx.channel();
            Payload payload = serverStream(requireHttp2Stream(streamId));
            if (payload == null || payload.streamId() != streamId) {
                // never happen ？
                throw new RemotingException((InetSocketAddress) channel.localAddress(), (InetSocketAddress) channel.remoteAddress(),
                        "server side received remote data from streamId:" + streamId + ", but not found payload.");
            }

            if (payload.data() == null) {
                payload.data(ctx.alloc().buffer());
            }
            payload.endOfStream(endOfStream).data().writeBytes(data);

            if (logger.isDebugEnabled()) {
                logger.debug("received data streamId:" + streamId + " length:" + data.readableBytes() + " endOfStream:" + endOfStream);
            }

            if (endOfStream) {
                Payload prev = channel.attr(Payload.KEY).get();
                try {
                    /** A valid stream data will trigger an rpc invoke */
                    channel.attr(Payload.KEY).set(payload);
                    ctx.fireChannelRead(payload.data());
                } finally {
                    channel.attr(Payload.KEY).set(prev);
                    payload.data(null);
                }
            }
        } catch (Throwable e) {
            logger.warn("Exception in onDataRead, streamId:" + streamId, e);
            throw Http2Exception.streamError(
                    streamId, Http2Error.INTERNAL_ERROR, e, StringUtils.nullToEmpty(e.getMessage()));
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        Payload payload = null;
        Channel channel = ctx.channel();
        if ((payload = ctx.channel().attr(Payload.KEY).get()) == null) {
            promise.setFailure(new RemotingException((InetSocketAddress) channel.localAddress(),
                    (InetSocketAddress) channel.remoteAddress(), "Ignore to write because of not found payload from key 'http2.payload', message: "
                    + msg + " , message type:" + msg.getClass().getName() + " channel:" + channel));
            return;
        }

        Object message = payload.message();
        if (message instanceof Response) {
            sendRpcResponse(ctx, (Response) message, payload, promise);
        } else if (message instanceof Request) {
            // may be event ?
            sendRpcRequest(ctx, (Request) message, payload, promise);
        }
    }

    private void sendRpcRequest(ChannelHandlerContext ctx, Request request, final Payload payload, final ChannelPromise promise) throws Exception {

        // Get the http2Stream ID for the new http2Stream.
        final int streamId;
        try {
            streamId = incrementAndGetNextStreamId();
        } catch (RemotingException e) {
            promise.setFailure(e);
            // Initiate a graceful shutdown if we haven't already.
            if (!connection().goAwaySent()) {
                logger.warn("Stream IDs have been exhausted for this connection. "
                        + "Initiating graceful shutdown of the connection.");
                close(ctx, promise);
            }
            return;
        }

        payload.streamId(streamId);
        Http2Headers headers = prepareHeaders(request, payload);

        // only for debug
        headers.add(Constants.HTTP2_TRANCE_ID, "sId:" + streamId + ",id:" + request.getId());

        payload.http2Headers(headers);
        ChannelPromise callbackPromise = ctx().newPromise();
        encoder().writeHeaders(ctx(), streamId, headers, 0, false, callbackPromise)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            // The http2Stream will be null in case a http2Stream buffered in the encoder was canceled via RST_STREAM.
                            Http2Stream http2Stream = connection().stream(streamId);
                            if (http2Stream != null) {
                                http2Stream.setProperty(streamKey, payload);
                                // Attach the client http2Stream to the HTTP/2 http2Stream object as user data.
                                payload.http2Stream(http2Stream);
                            }
                        } else {
                            promise.setFailure(future.cause());
                        }
                    }
                });

        /** dubbo default is not use direct buffer. */
        byte[] sendBytes = new byte[payload.encodedBuffer().readableBytes()];
        payload.encodedBuffer().readBytes(sendBytes);

        if (logger.isDebugEnabled()) {
            logger.debug("send rpc request streamId:" + streamId + ", send byte size : " + sendBytes.length);
        }

        encoder().writeData(ctx, streamId, Unpooled.wrappedBuffer(sendBytes), 0, true, promise);

    }

    private void sendRpcResponse(ChannelHandlerContext ctx, Response response, final Payload payload, final ChannelPromise promise) throws Exception {

        // Get the http2Stream ID for the new http2Stream.
        final int streamId = payload.streamId();
        Http2Stream stream = connection().stream(streamId);

        if (stream == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("stream is not available, streamId:" + streamId + ", responeId:" + response.getId());
            }
            resetStream(ctx, streamId, Http2Error.CANCEL.code(), promise);
            return;
        }

        if (payload.endOfStream()) {
            stream.removeProperty(streamKey);
        }

        try {
            Http2Headers headers = payload.http2Headers().clear();
            headers.status(Constants.HTTP2_OK_STATUS);
            encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.voidPromise());

            /**
             * dubbo channel buffer is not easy to use when in netty,
             * dubbo default is not use direct buffer.
             */
            byte[] sendBytes = new byte[payload.encodedBuffer().readableBytes()];
            payload.encodedBuffer().readBytes(sendBytes);

            encoder().writeData(ctx, streamId, Unpooled.wrappedBuffer(sendBytes), 0, false, ctx.voidPromise());

            Http2Headers trailers = new DefaultHttp2Headers();
            trailers.add(Constants.HTTP2_DUBBO_RPC_MESSAGE, StringUtils.nullToEmpty(response.getErrorMessage()));

            encoder().writeHeaders(ctx, streamId, trailers, 0, true, promise);

            if (logger.isDebugEnabled()) {
                logger.debug("rpc response streamId:" + streamId + ", headers: " + headers
                        + " content-length:" + sendBytes.length
                        + " trailers: " + trailers
                        + " id:" + response.getId()
                        + " payload:" + payload);
            }
        } finally {
            // help for GC
            Payload.ID_PAYLOAD_MAP.remove(streamId);
        }
    }

    private void onRstStreamRead(int streamId, long errorCode) throws Http2Exception {
        Payload payload = serverStream(connection().stream(streamId));
        if (payload != null) {
            logger.debug("Received rst_stream, streamId:" + streamId + ", errorCode:" + errorCode);
            if (payload.data() != null && payload.data().refCnt() > 0) {
                ReferenceCountUtil.release(payload.data());
            }
            connection().stream(streamId).removeProperty(streamKey);
        }
    }

    @Override
    protected void onStreamError(ChannelHandlerContext ctx, boolean outbound, Throwable cause,
                                 Http2Exception.StreamException http2Ex) {
        logger.warn("Stream Error", cause);
        Http2Stream stream = connection().stream(Http2Exception.streamId(http2Ex));
        Payload payload = serverStream(stream);
        if (payload != null) {
            if (payload.data() != null && payload.data().refCnt() > 0) {
                ReferenceCountUtil.release(payload.data());
            }
            stream.removeProperty(streamKey);
        }
        // Delegate to the base class to send a RST_STREAM.
        super.onStreamError(ctx, outbound, cause, http2Ex);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            logger.warn("connection terminated, may be closed by remote endpoint. ");
            // Any streams that are still active must be closed
            connection().forEachActiveStream(new Http2StreamVisitor() {
                @Override
                public boolean visit(Http2Stream stream) throws Http2Exception {
                    Payload serverStream = serverStream(stream);
                    if (serverStream != null) {
                        if (serverStream.data() != null && serverStream.data().refCnt() > 0) {
                            ReferenceCountUtil.release(serverStream.data());
                        }
                        stream.removeProperty(streamKey);
                        logger.info("channel " + ctx.channel() + " payload removed, streamId:" + serverStream.streamId());
                    }
                    return true;
                }
            });
        } finally {
            super.channelInactive(ctx);
        }
    }

    private int incrementAndGetNextStreamId() throws RemotingException {
        int nextStreamId = connection().local().incrementAndGetNextStreamId();
        if (nextStreamId < 0) {
            logger.error("Stream IDs have been exhausted for this connection. "
                    + "Initiating graceful shutdown of the connection, nextStreamId:" + nextStreamId);
            throw new RemotingException(null, "Stream IDs have been exhausted, nextStreamId:" + nextStreamId);
        }
        return nextStreamId;
    }

    private Payload serverStream(Http2Stream stream) {
        return stream == null ? null : (Payload) stream.getProperty(streamKey);
    }

    private void respondWithHttpError(
            ChannelHandlerContext ctx, int streamId, String message) {

        // http2 header
        Http2Headers header = new DefaultHttp2Headers();
        header.status(Constants.HTTP2_OK_STATUS);
        header.add(Constants.HTTP2_CONTENT_TYPE_KEY, Constants.HTTP2_DUBBO_CONTENT_TYPE);

        encoder().writeHeaders(ctx, streamId, header, 0, false, ctx.newPromise());

        // http2 trailer
        Http2Headers trailer = new DefaultHttp2Headers();
        trailer.add(Constants.HTTP2_DUBBO_RPC_MESSAGE, message);

        encoder().writeHeaders(ctx, streamId, trailer, 0, true, ctx.newPromise());
    }

    private class FrameListener extends Http2FrameAdapter {

        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
            NettyHttp2ServerHandler.this.onDataRead(ctx, streamId, data, padding, endOfStream);
            return data.readableBytes() + padding;
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
                                  short weight, boolean exclusive, int padding, boolean endStream) throws Http2Exception {
            NettyHttp2ServerHandler.this.onHeadersRead(ctx, streamId, headers, endStream);
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endStream) throws Http2Exception {
            NettyHttp2ServerHandler.this.onHeadersRead(ctx, streamId, headers, endStream);
        }

        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
                throws Http2Exception {
            NettyHttp2ServerHandler.this.onRstStreamRead(streamId, errorCode);
        }
    }
}