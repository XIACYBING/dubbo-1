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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.Transporters;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.remoting.exchange.support.header.HeaderExchanger;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.OPTIMIZER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;

/**
 * dubbo protocol的具体实现
 * <p>
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static volatile DubboProtocol INSTANCE;
    private static Object MONITOR = new Object();

    /**
     * <host:port,Exchanger>
     * <p>
     * key：host:port
     * value：{@link #PENDING_OBJECT}或{@link List<ReferenceCountExchangeClient>}
     */
    private final Map<String, Object> referenceClientMap = new ConcurrentHashMap<>();
    private static final Object PENDING_OBJECT = new Object();
    private final Set<String> optimizers = new ConcurrentHashSet<>();

    /**
     * 默认的{@link org.apache.dubbo.remoting.ChannelHandler}实现
     */
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            // 数据类型检查
            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel,
                    "Unsupported request: " + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: "
                        + channel.getLocalAddress());
            }

            Invocation inv = (Invocation)message;

            // 根据请求获取符合要求的服务提供者的Invoker
            Invoker<?> invoker = getInvoker(channel, inv);

            // 针对客户端回调内容的处理
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get(METHODS_KEY);
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(COMMA_SEPARATOR)) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(COMMA_SEPARATOR);
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                        + " not found in callback service interface ,invoke will be ignored."
                        + " please update the api interface. url is:" + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }

            // 设置远程调用/客户端/Consumer的地址
            RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());

            // 调用服务提供者，生成结果
            Result result = invoker.invoke(inv);

            // todo 这是为什么？这不是啥处理都没压吗？
            return result.thenApply(Function.identity());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        /**
         * FIXME channel.getUrl() always binds to a fixed service, and this service is random.
         * we can choose to use a common service to carry onConnect event if there's no easy way to get the specific
         * service this connection is binding to.
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(method, url.getParameter(INTERFACE_KEY), "", new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getParameter(GROUP_KEY));
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getParameter(VERSION_KEY));
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
    }

    public static DubboProtocol getDubboProtocol() {
        if (null == INSTANCE) {
            synchronized (MONITOR) {
                if (null == INSTANCE) {
                    INSTANCE = (DubboProtocol) ExtensionLoader.getExtensionLoader(Protocol.class).getOriginalInstance(DubboProtocol.NAME);
                }
            }
        }
        return INSTANCE;
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() && NetUtils
            .filterLocalHost(channel.getUrl().getIp())
            .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    /**
     * 根据{@code inv}获取代表提供者的Invoker，然后提供调用能力，这是在服务提供者接收到请求时的处理
     */
    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = (String)inv.getObjectAttachments().get(PATH_KEY);

        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }

        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getObjectAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getObjectAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        // 根据参数生成serviceKey：{serviceGroup/}serviceName{:serviceVersion}:port
        String serviceKey = serviceKey(port, path, (String)inv.getObjectAttachments().get(VERSION_KEY),
            (String)inv.getObjectAttachments().get(GROUP_KEY));

        // 根据serviceKey获取对应的Exporter
        DubboExporter<?> exporter = (DubboExporter<?>)exporterMap.getExport(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel,
                "Not found exported service: " + serviceKey + " in " + exporterMap.getExporterMap().keySet()
                    + ", may be version or group mismatch " + ", channel: consumer: " + channel.getRemoteAddress()
                    + " --> provider: " + channel.getLocalAddress() + ", message:" + getInvocationWithoutData(inv));
        }

        // 返回响应的Invoker
        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {

        // 获取url
        URL url = invoker.getUrl();

        // export service.

        // 生成服务key：{serviceGroup/}serviceName{:serviceVersion}:port
        String key = serviceKey(url);

        // 生成DubboExporter
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);

        // 添加映射到exporterMap的映射集合中
        exporterMap.addExportMap(key, exporter);

        // 对stub和callback的判断，纸打印日志，暂时没有任何操作 todo 这是干啥的？
        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY)
                        + "], has set stubproxy support event ,but no stub methods founded."));
                }

            }
        }

        // 启动服务器
        openServer(url);

        // 增加序列化优化器配置
        optimizeSerialization(url);

        // 返回DubboExporter
        return exporter;
    }

    private void openServer(URL url) {

        // 获取地址作为key
        // find server.
        String key = url.getAddress();

        // 客户端也可以导出一个服务供服务器调用
        //client can export a service which's only for server to invoke
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);
        if (isServer) {

            // 根据ip+port获取服务器实现
            ProtocolServer server = serverMap.get(key);

            // 如果为空，双重检查创建服务器
            if (server == null) {
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {

                        // 创建服务器
                        serverMap.put(key, createServer(url));
                    }
                }
            }

            // 如果已有ProtocolServer，则基于url上的参数，reset某些服务器属性
            else {
                // server supports reset, use together with override
                server.reset(url);
            }
        }
    }

    /**
     * 链路：
     * {@link #createServer} -> {@link Exchangers#bind} -> {@link HeaderExchanger#bind} ->
     * {@link Transporters#bind} -> {@link org.apache.dubbo.remoting.transport.netty.NettyTransporter#bind}
     * -> {@link org.apache.dubbo.remoting.transport.netty4.NettyServer#NettyServer}
     */
    private ProtocolServer createServer(URL url) {

        // 构建一个新的url
        url = URLBuilder.from(url)

                        // 设置channel.readonly.sent属性，当服务器关闭时，需要发送readonly事件给相关的客户端，如果当前属性为true，代表服务器需要阻塞等待相应返回
                        // send readonly event when server closes, it's enabled by default
                        .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())

                        // 设置心跳检查的毫秒数：默认值为60秒，表示心跳时间间隔时60秒
                        // enable heartbeat by default
                        .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))

                        // 设置codec实现的key
                        .addParameter(CODEC_KEY, DubboCodec.NAME).build();

        // 获取Transporter实现的key
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);

        // 没有相关的Transporter实现则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader
            .getExtensionLoader(Transporter.class)
            .hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {

            // 从exchanger层获取ExchangeServer
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        // client类型检查   todo 是做什么的？
        str = url.getParameter(CLIENT_KEY);
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        // 包装ExchangeServer为ProtocolServer
        return new DubboProtocolServer(server);
    }

    /**
     * 序列化优化：在使用某些序列化算法时（例如：Kryo、FST等...），为了让其能发挥出最佳的性能，最好将哪些需要被序列化的类提前注册到Dubbo系统中，
     * 这也是SerializationOptimizer在做的事
     */
    private void optimizeSerialization(URL url) throws RpcException {

        // 获取优化器配置的类名
        String className = url.getParameter(OPTIMIZER_KEY, "");

        // 类名为空，或已经加载过，则不处理
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {

            // 加载类对象
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);

            // 类型判断
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of "
                    + SerializationOptimizer.class.getName());
            }

            // 实例化优化器
            SerializationOptimizer optimizer = (SerializationOptimizer)clazz.newInstance();

            // 优化器未配置可序列化类，则跳过
            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            // 注册优化器的可序列化类
            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            // 添加优化器到集合中
            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException | IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        }
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {

        // 进行序列化优化，使用可能存在的序列化优化器
        optimizeSerialization(url);

        // 创建一个DubboInvoker对象：getClients会生成对应的ExchangeClient[]
        // create rpc invoker.
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);

        // 将创建完成的Invoker添加到集合中
        invokers.add(invoker);

        // 返回创建完成的invoker
        return invoker;
    }

    /**
     * 根据url，获取代表服务提供者的{@link ExchangeClient}
     */
    private ExchangeClient[] getClients(URL url) {

        // 获取连接数，未配置默认为0
        // whether to share connection
        int connections = url.getParameter(CONNECTIONS_KEY, 0);

        // 如果没配置，则使用共享连接，即共享Client
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {

            // 获取共享Client连接数（可以理解为需要多少个Client），默认值为1
            // xml配置比properties配置更优先
            // The xml configuration should have a higher priority than properties.
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String)null);
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ?
                ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY, DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);

            // 获取共享Client，如果没有，则根据connections创建相应数量的共享客户端
            return getSharedClient(url, connections).toArray(new ExchangeClient[0]);
        }

        // 初始化相应数量的客户端，由当前url独享
        else {
            ExchangeClient[] clients = new ExchangeClient[connections];
            for (int i = 0; i < clients.length; i++) {
                // 初始化客户端（非共享）
                clients[i] = initClient(url);
            }
            return clients;
        }

    }

    /**
     * 获取共享客户端，代表即共享连接
     * <p>
     * 不是获取{@code connectNum}个Client，{@code connectNum}的作用是在没有共享客户端的时候，创建相应数量的共享客户端，如果已有共享客户端，会直接返回
     * <p>
     * Get shared connection
     *
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    @SuppressWarnings("unchecked")
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {

        // 获取地址：IP:PORT
        String key = url.getAddress();

        // 获取集合中当前key的数据
        Object clients = referenceClientMap.get(key);

        // 只有在值为集合时才进行处理（非集合的时候可能是ReferenceCountExchangeClient）
        if (clients instanceof List) {

            // 类型转换
            List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>)clients;

            // 校验Client集合中的Client是否全部可用，全部可用时才将其返回给外部使用
            if (checkClientCanUse(typedClients)) {

                // 对当前Client集合中的Client进行引用计数自增
                batchClientRefIncr(typedClients);

                // 返回Client集合
                return typedClients;
            }
        }

        List<ReferenceCountExchangeClient> typedClients = null;

        // 加锁
        synchronized (referenceClientMap) {
            for (; ; ) {
                clients = referenceClientMap.get(key);

                if (clients instanceof List) {
                    typedClients = (List<ReferenceCountExchangeClient>)clients;

                    // 校验typedClients中的client是否全部可用，如果是，对client进行计数自增，并返回
                    if (checkClientCanUse(typedClients)) {
                        batchClientRefIncr(typedClients);
                        return typedClients;
                    }

                    // 否则需要额外创建Client，先放入占位符PENDING_OBJECT，并中断循环
                    else {
                        referenceClientMap.put(key, PENDING_OBJECT);
                        break;
                    }
                }

                // 如果当前的key映射的是PENDING_OBJECT，说明当前key正在创建，当前线程wait，等待其他线程创建完成
                else if (clients == PENDING_OBJECT) {
                    try {
                        referenceClientMap.wait();
                    } catch (InterruptedException ignored) {
                    }
                }

                // 不是以上两种情况，放入占位符PENDING_OBJECT，并中断循环
                else {
                    referenceClientMap.put(key, PENDING_OBJECT);
                    break;
                }
            }
        }

        // 退出同步块，开始创建共享Client

        try {

            // 最少创建一个
            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);

            // 如果上面获取到的typedClients集合为空，说明共享客户端需要初始化
            // If the clients is empty, then the first initialization is
            if (CollectionUtils.isEmpty(typedClients)) {
                typedClients = buildReferenceCountExchangeClientList(url, connectNum);
            }

            // 如果不为空，说明集合中可能有某些共享客户端不可用，需要替换掉哪些客户端
            else {
                for (int i = 0; i < typedClients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = typedClients.get(i);

                    // 客户端为空，或连接已关闭，需要新建一个客户端替换
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        typedClients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }

                    // 客户端被使用计数自增
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }
        } finally {
            synchronized (referenceClientMap) {

                // 移除或更新共享客户端集合
                if (typedClients == null) {
                    referenceClientMap.remove(key);
                } else {
                    referenceClientMap.put(key, typedClients);
                }

                // 通知所有休眠的线程
                referenceClientMap.notifyAll();
            }
        }

        // 返回共享客户端
        return typedClients;

    }

    /**
     * Check if the client list is all available
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {

            // Client为空，引用此处小于等于0，或连接关闭，都代表当前Client不可用     todo 引用次数小于等于0为什么代表Client不可用，是有什么特殊的链路吗？
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.getCount() <= 0
                || referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 增加{@link ReferenceCountExchangeClient}中的引用计数
     * <p>
     * Increase the reference Count if we create new invoker shares same connection, the connection will be closed without any reference.
     *
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        // 循环client
        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {

                // 当前Client引用次数自增
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        // 创建相应数量的ReferenceCountExchangeClient
        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * 创建{@link ExchangeClient}，并包装成{@link ReferenceCountExchangeClient}
     * <p>
     * Build a single client
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {

        // 初始化ExchangeClient
        ExchangeClient exchangeClient = initClient(url);

        // 包装成ReferenceCountExchangeClient，新增引用计数功能
        return new ReferenceCountExchangeClient(exchangeClient);
    }

    /**
     * Create new connection
     * <p>
     * 创建一个新连接（新的{@link ExchangeClient}）
     *
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        // 获取客户端类型（Transporter的实现）
        // client type setting.
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));

        // 编码的SPI实现，默认是DubboCodec（Dubbo也只提供了DubboCodec和ExchangeCodec的实现）
        url = url.addParameter(CODEC_KEY, DubboCodec.NAME);

        // 心跳请求的配置
        // enable heartbeat by default
        url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

        // 检验Transporter的实现
        // BIO is not allowed since it has severe performance issue.
        if (str != null && str.length() > 0 && !ExtensionLoader
            .getExtensionLoader(Transporter.class)
            .hasExtension(str)) {
            throw new RpcException(
                "Unsupported client type: " + str + "," + " supported client type is " + StringUtils.join(
                    ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {

            // 懒加载客户端的实现：只有在第一次请求的时候才会去创建Client，创建连接，然后处理请求
            // connection should be lazy
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);

            }

            // 创建客户端
            else {
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy() {

        // 循环服务器集合，销毁服务器
        for (String key : new ArrayList<>(serverMap.keySet())) {

            // 从集合中移除当前服务器
            ProtocolServer protocolServer = serverMap.remove(key);

            if (protocolServer == null) {
                continue;
            }

            // 获取其中的RemotingServer
            RemotingServer server = protocolServer.getRemotingServer();

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }

                // 关闭server，server会向连接的客户端发送readonly事件，根据配置判断是否等待客户端响应，然后关闭相关的定时任务（定时重连），关闭处理连接的线程池
                // exchange层的HeaderExchangeServer：发送readonly事件，等待客户端响应；设置closed状态，关闭定时关闭客户端连接的定时任务；并调用transport层的server实例的close方法
                // transport层的NettyServer：AbstractServer关闭线程池；关闭对应的NettyChannel，关闭所有客户端连接的channel，关闭Netty相关的线程池
                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        // 销毁引用客户端
        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            Object clients = referenceClientMap.remove(key);
            if (clients instanceof List) {
                List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;

                if (CollectionUtils.isEmpty(typedClients)) {
                    continue;
                }

                // 循环销毁客户端：
                for (ReferenceCountExchangeClient client : typedClients) {
                    closeReferenceCountExchangeClient(client);
                }
            }
        }

        // 调用父类的destroy方法，销毁refer和export出去的所有相关资源
        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            // 关闭当前客户端：需要注意，ReferenceCountExchangeClient只有在引用计数真正减到0时，才回去执行close逻辑
            // exchange层的HeaderExchangeClient：关闭定时任务，关闭ExchangeChannel通道（而ExchangeChannel一般是transport层的Client
            // 的包装，会联动关闭Client）
            // transport层的NettyClient：设置关闭状态，从断开连接，并执行具体的断开连接和关闭的操作
            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /*
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * only log body in debugger mode for size & security consideration.
     *
     * @param invocation
     * @return
     */
    private Invocation getInvocationWithoutData(Invocation invocation) {
        if (logger.isDebugEnabled()) {
            return invocation;
        }
        if (invocation instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) invocation;
            rpcInvocation.setArguments(null);
            return rpcInvocation;
        }
        return invocation;
    }
}
