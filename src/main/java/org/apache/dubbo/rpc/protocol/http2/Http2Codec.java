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
package org.apache.dubbo.rpc.protocol.http2;

import io.netty.handler.codec.http2.Http2Headers;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.transport.netty4.NettyChannel;
import org.apache.dubbo.remoting.transport.netty4.http2.Payload;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.dubbo.DubboCodec;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

public class Http2Codec extends DubboCodec implements Codec2 {

    public static final String NAME = "http2";
    private static final Logger logger = LoggerFactory.getLogger(Http2Codec.class);

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object message) throws IOException {
        if (channel instanceof NettyChannel) {
            NettyChannel nettyChannel = (NettyChannel) channel;
            if (message instanceof Request) {
                Payload payload = new Payload(message, buffer);
                nettyChannel.setNettyAttribute(Payload.KEY, payload);
            } else if (message instanceof Response) {
                Payload payload = Payload.ID_PAYLOAD_MAP.get(((Response) message).getId());
                payload.message(message).encodedBuffer(buffer);
                nettyChannel.setNettyAttribute(Payload.KEY, payload);
            }
        }
        super.encode(channel, buffer, message);
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        super.encodeRequestData(channel, out, data);

        RpcInvocation invoker = (RpcInvocation) data;
        URL url = invoker.getInvoker().getUrl();
        String serviceName = invoker.getAttachment(Constants.PATH_KEY);
        String methodName = invoker.getMethodName();
        String authority = invoker.getAttachment(Constants.HTTP2_AUTHORITY_KEY);
        if (null == authority) authority = "";

        if (channel instanceof NettyChannel) {
            NettyChannel nettyChannel = (NettyChannel) channel;

            // prepare header for http2
            Payload payload = nettyChannel.getNettyAttribute(Payload.KEY);
            if (payload == null) {
                logger.error("Not found stream payload for key 'http2.payload', channel:" + channel + " data:" + data);
                return;
            }
            payload.addAttachment(Constants.HTTP2_SERVICE_KEY, serviceName)
                    .addAttachment(Constants.HTTP2_SERVICE_METHOD_KEY, methodName)
                    .addAttachment(Constants.HTTP2_SCHEME_KEY, url.getParameter(Constants.SSL_ENABLE_KEY, false) ? "https" : "http")
                    .addAttachment(Constants.HTTP2_CONTENT_TYPE_KEY, Constants.HTTP2_DUBBO_CONTENT_TYPE);
        }
    }

    // Will be invoked when decode body
    @Override
    protected void attachInvocation(Channel channel, Invocation invocation, long id) {
        if (channel instanceof NettyChannel) {
            NettyChannel nettyChannel = (NettyChannel) channel;
            Payload payload = nettyChannel.getNettyAttribute(Payload.KEY);

            if (payload != null && payload.http2Headers() != null && invocation instanceof RpcInvocation) {
                Http2Headers headers = payload.http2Headers();
                Payload.ID_PAYLOAD_MAP.put(id, payload);
                RpcInvocation inv = (RpcInvocation) invocation;
                Iterator<Map.Entry<CharSequence, CharSequence>> iterator = payload.http2Headers().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<CharSequence, CharSequence> entry = iterator.next();
                    if (entry.getKey().toString().equals("X-")) {
                        inv.setAttachment(entry.getKey().toString(),
                                entry.getValue() == null ? "" : entry.getValue().toString());
                    }
                }
            }
        }
    }
}