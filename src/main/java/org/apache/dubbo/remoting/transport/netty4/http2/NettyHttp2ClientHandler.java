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
import io.netty.buffer.ByteBufUtil;
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
import io.netty.handler.codec.http2.StreamBufferingEncoder;
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
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.transport.netty4.NettyChannel;

import java.net.InetSocketAddress;

import static io.netty.handler.codec.http2.DefaultHttp2LocalFlowController.DEFAULT_WINDOW_UPDATE_RATIO;
import static io.netty.util.CharsetUtil.UTF_8;

public class NettyHttp2ClientHandler extends AbstractHttp2CodecHandler {

    private final Logger logger = LoggerFactory.getLogger(NettyHttp2ClientHandler.class);

    Http2Connection.PropertyKey streamKey;

    private NettyHttp2ClientHandler(URL url,
                                    Http2ConnectionDecoder decoder,
                                    StreamBufferingEncoder encoder,
                                    Http2Settings settings) {
        super(url, decoder, encoder, settings);

        Http2Connection connection = encoder.connection();
        connection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
                byte[] debugDataBytes = ByteBufUtil.getBytes(debugData);
                goingAway(lastStreamId, errorCode, debugData);
                if (errorCode == Http2Error.ENHANCE_YOUR_CALM.code()) {
                    String data = new String(debugDataBytes, UTF_8);
                    logger.warn("received goaway with ENHANCE_YOUR_CALM. Debug data: " + data);
                }
            }

            @Override
            public void onStreamClosed(Http2Stream stream) {
                if (connection().numActiveStreams() != 0) {
                    return;
                }
            }
        });

        this.streamKey = encoder.connection().newKey();
        this.decoder().frameListener(new NettyHttp2ClientHandler.FrameListener());
    }

    public static NettyHttp2ClientHandler newHandler(URL url) {
        Http2Settings initialSettings = Http2Settings.defaultSettings();
        initialSettings.pushEnabled(false);
        Http2HeadersDecoder headersDecoder = new DefaultHttp2HeadersDecoder(true, initialSettings.maxHeaderListSize());
        Http2FrameReader frameReader = new DefaultHttp2FrameReader(headersDecoder);
        Http2FrameWriter frameWriter = new DefaultHttp2FrameWriter();
        Http2Connection connection = new DefaultHttp2Connection(false);
        WeightedFairQueueByteDistributor distributor = new WeightedFairQueueByteDistributor(connection);
        distributor.allocationQuantum(16 * 1024);
        DefaultHttp2RemoteFlowController controller = new DefaultHttp2RemoteFlowController(connection, distributor);
        connection.remote().flowController(controller);
        return newHandler(url, connection, frameReader, frameWriter, initialSettings);
    }

    public static NettyHttp2ClientHandler newHandler(URL url, final Http2Connection connection,
                                                     Http2FrameReader frameReader,
                                                     Http2FrameWriter frameWriter,
                                                     Http2Settings settings) {

        Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, NettyHttp2ClientHandler.class);
        frameReader = new Http2InboundFrameLogger(frameReader, frameLogger);
        frameWriter = new Http2OutboundFrameLogger(frameWriter, frameLogger);

        StreamBufferingEncoder encoder = new StreamBufferingEncoder(new DefaultHttp2ConnectionEncoder(connection, frameWriter));

        // Create the local flow controller configured to auto-refill the connection window.
        connection.local().flowController(new DefaultHttp2LocalFlowController(connection, DEFAULT_WINDOW_UPDATE_RATIO, true));
        Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder, frameReader);

        return new NettyHttp2ClientHandler(url, decoder, encoder, settings);
    }

    private void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, boolean endStream) throws Http2Exception {
        Payload payload = null;
        try {
            Http2Stream http2Stream = requireHttp2Stream(streamId);
            payload = http2Stream.getProperty(streamKey);

            CharSequence path = headers.path();
            if (payload == null) {
                // received event request from provider
                if (path != null && path.toString().equals(Constants.EVENT_PATH)) {
                    payload = new Payload(http2Stream, headers);
                    http2Stream.setProperty(streamKey, payload);
                    if (logger.isDebugEnabled()) {
                        logger.debug("received event headers: " + headers + ", streamId:" + streamId);
                    }
                } else {
                    // new rpc request from remote, maybe heartbeat from server request
                    logger.error("client side received unexpected headers: " + headers + ", streamId:" + streamId);
                    return;
                }
            }

            Channel channel = ctx.channel();
            // trailers
            if (endStream) {
                if (payload.data() == null) {
                    CharSequence rpcMessage = headers.get(Constants.HTTP2_DUBBO_RPC_MESSAGE);
                    throw new RemotingException((InetSocketAddress) channel.localAddress(), (InetSocketAddress) channel.remoteAddress(),
                            "No response data received, cause: " + StringUtils.nullToEmpty(rpcMessage));
                }
            } else {
                payload.streamId(streamId).http2Headers(headers).endOfStream(endStream);
            }

        } catch (Exception e) {
            logger.error("Unexpected onHeaderRead, streamId:" + streamId + ", headers:" + headers, e);
            handleException(ctx, payload, e);
        }
    }

    private void onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
            throws Http2Exception {
        Channel channel = ctx.channel();
        Payload payload = null;
        try {

            if (logger.isDebugEnabled()) {
                logger.debug("received data streamId:" + streamId + " length:" + data.readableBytes() + "endOfStream:" + endOfStream);
            }

            payload = clientStream(requireHttp2Stream(streamId));
            if (payload == null) {
                throw new RemotingException((InetSocketAddress) channel.localAddress(), (InetSocketAddress) channel.remoteAddress(),
                        "client side received remote data from streamId:" + streamId + ", but not found payload.");
            }

            if (payload.http2Headers() == null) {
                // received before header ?
                throw new RemotingException((InetSocketAddress) ctx.channel().localAddress(),
                        (InetSocketAddress) ctx.channel().remoteAddress(), "Headers not received before payload.");
            }

            if (payload.data() == null) {
                payload.data(ctx.alloc().buffer());
            }
            payload.endOfStream(endOfStream).data().writeBytes(data);

            if (endOfStream) {
                Payload prev = channel.attr(Payload.KEY).get();
                try {
                    /** A valid stream data will trigger an rpc call */
                    channel.attr(Payload.KEY).set(payload);
                    ctx.fireChannelRead(payload.data());
                } finally {
                    channel.attr(Payload.KEY).set(prev);
                    payload.data(null);
                }
            }
        } catch (Exception e) {
            logger.warn("Exception in onDataRead, streamId:" + streamId, e);
            handleException(ctx, payload, e);
        }
    }

    private void handleException(ChannelHandlerContext ctx, Payload payload, Exception e) {
        Object message;
        if (payload != null && (message = payload.message()) != null) {
            Request request = (Request) message;
            if (request.isTwoWay() && !request.isHeartbeat()) {
                Response response = new Response(request.getId(), request.getVersion());
                response.setStatus(Response.SERVER_ERROR);
                response.setErrorMessage(StringUtils.toString(e));
                DefaultFuture.received(NettyChannel.getChannel(ctx.channel()), response);
            }
        }
    }

    /**
     * Handler for a GOAWAY being received. Fails any streams created after the last known http2Stream.
     */
    private void goingAway(int lastStreamId, long errorCode, ByteBuf debugData) {
        final int lastKnownStream = connection().local().lastStreamKnownByPeer();
        try {
            connection().forEachActiveStream(new Http2StreamVisitor() {
                @Override
                public boolean visit(Http2Stream stream) throws Http2Exception {
                    if (stream.id() > lastKnownStream) {
                        Payload clientStream = clientStream(stream);
                        if (clientStream != null) {
                            logger.warn("received goingAway, lastStreamId:" + lastStreamId
                                    + ",errorCode:" + errorCode + ",payload:" + clientStream);
                            stream.removeProperty(streamKey);
                        }
                        stream.close();
                    }
                    return true;
                }
            });
        } catch (Http2Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try{
            Payload payload;
            Channel channel = ctx.channel();
            if ((payload = channel.attr(Payload.KEY).get()) == null) {
                promise.setFailure(new RemotingException((InetSocketAddress) channel.localAddress(),
                        (InetSocketAddress) channel.remoteAddress(), "Ignore to write because of not found payload from key 'http2.payload', message: "
                        + msg + " , message type:" + msg.getClass().getName() + " channel:" + channel));
                return;
            }

            Object message = payload.message();

            if (message instanceof Request) {
                sendRpcRequest(ctx, (Request) message, payload, promise);
            } else if (message instanceof Response) {
                sendRpcResponse(ctx, (Response) message, payload, promise);
            } else if (message == NOOP_MESSAGE) {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER, promise);
            }
        }finally {
            ReferenceCountUtil.release(msg);
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
                logger.warn("Stream IDs have been exhausted for this connection. Initiating graceful shutdown of the connection. payload:" + payload);
                close(ctx, promise);
            }
            return;
        }

        payload.streamId(streamId).endOfStream(!request.isTwoWay());
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

        try {
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

            Http2Headers headers = payload.http2Headers().clear();
            headers.status(Constants.HTTP2_OK_STATUS);
            encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.voidPromise());

            // dubbo channel buffer is not easy to use when in netty.
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
        } catch (Exception e) {
            promise.setFailure(e);
        } finally {
            // help for GC
            Payload.ID_PAYLOAD_MAP.remove(streamId);
        }
    }

    private int incrementAndGetNextStreamId() throws RemotingException {
        int nextStreamId = connection().local().incrementAndGetNextStreamId();
        if (nextStreamId < 0) {
            logger.error("Stream IDs have been exhausted for this connection. "
                    + "Initiating graceful shutdown of the connection.");
            throw new RemotingException(null, "Stream IDs have been exhausted, nextStreamId:" + nextStreamId);
        }
        return nextStreamId;
    }

    private void onRstStreamRead(int streamId, long errorCode) throws Http2Exception {
        Payload payload = clientStream(connection().stream(streamId));
        if (payload != null) {
            logger.debug("Received rst_stream, streamId:" + streamId + ", errorCode:" + errorCode);
            if (payload.data() != null && payload.data().refCnt() > 0) {
                ReferenceCountUtil.release(payload.data());
            }
            connection().stream(streamId).removeProperty(streamKey);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            logger.warn("connection: " + ctx.channel() + " terminated , maybe closed by remote endpoint. ");
            // Report status to the application layer for any open streams
            connection().forEachActiveStream(new Http2StreamVisitor() {
                @Override
                public boolean visit(Http2Stream stream) throws Http2Exception {
                    Payload clientStream = clientStream(stream);
                    if (clientStream != null) {
                        if (clientStream.data() != null && clientStream.data().refCnt() > 0) {
                            ReferenceCountUtil.release(clientStream.data());
                        }
                        stream.removeProperty(streamKey);
                        logger.info("channel " + ctx.channel() + " payload removed, streamId:" + clientStream.streamId());
                    }
                    return true;
                }
            });
        } finally {
            // Close any open streams
            super.channelInactive(ctx);
        }
    }

    private Payload clientStream(Http2Stream stream) {
        return stream == null ? null : (Payload) stream.getProperty(streamKey);
    }

    private class FrameListener extends Http2FrameAdapter {

        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
            NettyHttp2ClientHandler.this.onDataRead(ctx, streamId, data, padding, endOfStream);
            return data.readableBytes() + padding;
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
                                  short weight, boolean exclusive, int padding, boolean endStream) throws Http2Exception {
            NettyHttp2ClientHandler.this.onHeadersRead(ctx, streamId, headers, endStream);
        }

        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
                throws Http2Exception {
            NettyHttp2ClientHandler.this.onRstStreamRead(streamId, errorCode);
        }
    }
}