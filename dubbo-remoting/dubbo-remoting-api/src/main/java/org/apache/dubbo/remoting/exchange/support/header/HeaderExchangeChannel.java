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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * {@link HeaderExchangeChannel}是{@link Channel}的装饰器，实际的逻辑基于常量{@link #channel}实现
 * <p>
 * ExchangeReceiver
 */
final class HeaderExchangeChannel implements ExchangeChannel {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeChannel.class);

    private static final String CHANNEL_KEY = HeaderExchangeChannel.class.getName() + ".CHANNEL";

    /**
     * 当前类的所有通道操作都委托给当前{@link #channel}
     * <p>
     * 在{@link HeaderExchangeClient}场景，当前{@link #channel}就是{@code transport}层返回的{@link Client}，比如{@link org.apache.dubbo.remoting.transport.netty4.NettyClient}
     */
    private final Channel channel;

    private volatile boolean closed = false;

    HeaderExchangeChannel(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel == null");
        }
        this.channel = channel;
    }

    static HeaderExchangeChannel getOrAddChannel(Channel ch) {
        if (ch == null) {
            return null;
        }
        HeaderExchangeChannel ret = (HeaderExchangeChannel) ch.getAttribute(CHANNEL_KEY);
        if (ret == null) {
            ret = new HeaderExchangeChannel(ch);
            if (ch.isConnected()) {
                ch.setAttribute(CHANNEL_KEY, ret);
            }
        }
        return ret;
    }

    static void removeChannelIfDisconnected(Channel ch) {
        if (ch != null && !ch.isConnected()) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    static void removeChannel(Channel ch) {
        if (ch != null) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    @Override
    public void send(Object message) throws RemotingException {
        send(message, false);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null,
                "Failed to send message " + message + ", cause: The channel " + this + " is closed!");
        }

        // 正常的请求/响应，或字符串（telnet）
        if (message instanceof Request || message instanceof Response || message instanceof String) {
            channel.send(message, sent);
        }

        // todo 这是什么请求？单向的？（twoWay是false）
        else {
            Request request = new Request();
            request.setVersion(Version.getProtocolVersion());
            request.setTwoWay(false);
            request.setData(message);
            channel.send(request, sent);
        }
    }

    @Override
    public CompletableFuture<Object> request(Object request) throws RemotingException {
        return request(request, null);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout) throws RemotingException {
        return request(request, timeout, null);
    }

    @Override
    public CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException {

        // 增加timeout参数，调用重载的request方法
        return request(request, channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT), executor);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException {

        // 状态检查
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null,
                    "Failed to send request " + request + ", cause: The channel " + this + " is closed!");
        }

        // 创建Request
        // create request.
        Request req = new Request();
        req.setVersion(Version.getProtocolVersion());

        // twoWay模式：双向通信，是否需要接收服务端的响应，在不设置oneWay属性时，twoWay是默认的通讯模式
        req.setTwoWay(true);

        // 外部传入的request作为data字段的值
        req.setData(request);

        // 关联channel、req、timeout和executor，生成DefaultFuture，返回给外部获取结果
        // 此处应该不关注结果，HeaderExchangeHandler会将响应数据通过DefaultFuture.received方法设置到对应的DefaultFuture中
        DefaultFuture future = DefaultFuture.newFuture(channel, req, timeout, executor);
        try {

            // 通过channel发送请求：netty4的NettyClient.send，实际是父类：AbstractPeer.send
            // channel代表当前消费者和服务端的连接通道，可以直接发送数据
            channel.send(req);
        } catch (RemotingException e) {

            // 异常则cancel掉future
            future.cancel();
            throw e;
        }

        // 对外返回future
        return future;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        // If the channel has been closed, return directly.
        if (closed) {
            return;
        }
        try {

            // 关闭所有请求
            // graceful close
            DefaultFuture.closeChannel(channel);

            // 关闭通道，实际上调用的是具体Client的close方法，比如NettyClient.close
            channel.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    // graceful close
    @Override
    public void close(int timeout) {
        if (closed) {
            return;
        }

        // 设置关闭标识
        closed = true;

        // 超时毫秒大于0
        if (timeout > 0) {
            long start = System.currentTimeMillis();

            // 如果当前通道还有请求未接收到响应，则循环等待，直至timeout耗尽
            while (DefaultFuture.hasFuture(channel) && System.currentTimeMillis() - start < timeout) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }

        // 执行关闭，对于未接收到响应的请求，会直接创建一个状态为连接关闭的Response给业务线程
        close();
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return (ExchangeHandler) channel.getChannelHandler();
    }

    @Override
    public Object getAttribute(String key) {
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        return channel.hasAttribute(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((channel == null) ? 0 : channel.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        HeaderExchangeChannel other = (HeaderExchangeChannel) obj;
        if (channel == null) {
            if (other.channel != null) {
                return false;
            }
        } else if (!channel.equals(other.channel)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return channel.toString();
    }

}
