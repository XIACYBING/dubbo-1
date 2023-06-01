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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.rpc.model.ConsumerMethodModel;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.apache.dubbo.common.utils.ReflectUtils.defaultReturn;

/**
 * 一个异步的，未完成的Rpc调用的结果，会记录当前Rpc调用的相关信息，结果相关的内容都交给{@link #responseFuture}处理
 *
 * {@link AsyncRpcResult}对{@link Result}的实现都是基于{@link #responseFuture}中的值，类型一般是{@link AppResponse}，也是{@link Result}的实现类
 *
 * 因此{@link AsyncRpcResult}只是{@link AppResponse}的一个装饰器
 * <p>
 * This class represents an unfinished RPC call, it will hold some context information for this call, for example RpcContext and Invocation,
 * so that when the call finishes and the result returns, it can guarantee all the contexts being recovered as the same as when the call was made
 * before any callback is invoked.
 * <p>
 * TODO if it's reasonable or even right to keep a reference to Invocation?
 * <p>
 * As {@link Result} implements CompletionStage, {@link AsyncRpcResult} allows you to easily build a async filter chain whose status will be
 * driven entirely by the state of the underlying RPC call.
 * <p>
 * AsyncRpcResult does not contain any concrete value (except the underlying value bring by CompletableFuture), consider it as a status transfer node.
 * {@link #getValue()} and {@link #getException()} are all inherited from {@link Result} interface, implementing them are mainly
 * for compatibility consideration. Because many legacy {@link Filter} implementation are most possibly to call getValue directly.
 */
public class AsyncRpcResult implements Result {
    private static final Logger logger = LoggerFactory.getLogger(AsyncRpcResult.class);

    /**
     * Rpc调用过程中的{@link RpcContext}是绑定在线程上的，而线程在整个系统中是共用的，因此对于异步调用来说，需要将上下文信息{@link RpcContext}存储起来，以防被修改掉，
     * 而绑定在线程上的{@link RpcContext}，则会在{@link org.apache.dubbo.rpc.filter.ContextFilter}中通过{@link RpcContext#removeContext}移除掉
     * <p>
     * RpcContext may already have been changed when callback happens, it happens when the same thread is used to execute another RPC call.
     * So we should keep the reference of current RpcContext instance and restore it before callback being executed.
     */
    private RpcContext storedContext;

    /**
     * 和{@link #storedContext}同理
     */
    private RpcContext storedServerContext;

    /**
     * 本次Rpc调用关联的线程池
     */
    private Executor executor;

    private Invocation invocation;

    /**
     * 具体等待结果的{@link CompletableFuture}，类型一般是{@link DefaultFuture}
     * <p>
     * 对当前{@link AsyncRpcResult}附加的相关回调操作，最终其实都是添加到{@link #responseFuture}
     */
    private CompletableFuture<AppResponse> responseFuture;

    public AsyncRpcResult(CompletableFuture<AppResponse> future, Invocation invocation) {
        this.responseFuture = future;
        this.invocation = invocation;

        // 存储RpcContext
        this.storedContext = RpcContext.getContext();
        this.storedServerContext = RpcContext.getServerContext();
    }

    /**
     * Notice the return type of {@link #getValue} is the actual type of the RPC method, not {@link AppResponse}
     *
     * @return
     */
    @Override
    public Object getValue() {
        return getAppResponse().getValue();
    }

    /**
     * CompletableFuture can only be completed once, so try to update the result of one completed CompletableFuture will
     * has no effect. To avoid this problem, we check the complete status of this future before update it's value.
     *
     * But notice that trying to give an uncompleted CompletableFuture a new specified value may face a race condition,
     * because the background thread watching the real result will also change the status of this CompletableFuture.
     * The result is you may lose the value you expected to set.
     *
     * @param value
     */
    @Override
    public void setValue(Object value) {
        try {
            if (responseFuture.isDone()) {
                responseFuture.get().setValue(value);
            } else {
                AppResponse appResponse = new AppResponse(invocation);
                appResponse.setValue(value);
                responseFuture.complete(appResponse);
            }
        } catch (Exception e) {
            // This should not happen in normal request process;
            logger.error("Got exception when trying to fetch the underlying result from AsyncRpcResult.");
            throw new RpcException(e);
        }
    }

    @Override
    public Throwable getException() {
        return getAppResponse().getException();
    }

    @Override
    public void setException(Throwable t) {
        try {
            if (responseFuture.isDone()) {
                responseFuture.get().setException(t);
            } else {
                AppResponse appResponse = new AppResponse(invocation);
                appResponse.setException(t);
                responseFuture.complete(appResponse);
            }
        } catch (Exception e) {
            // This should not happen in normal request process;
            logger.error("Got exception when trying to fetch the underlying result from AsyncRpcResult.");
            throw new RpcException(e);
        }
    }

    @Override
    public boolean hasException() {
        return getAppResponse().hasException();
    }

    public CompletableFuture<AppResponse> getResponseFuture() {
        return responseFuture;
    }

    public void setResponseFuture(CompletableFuture<AppResponse> responseFuture) {
        this.responseFuture = responseFuture;
    }

    public Result getAppResponse() {
        try {

            // 如果异步任务以完成，获取结果返回，结果类型一般是DecodeableRpcResult（AppResponse和Result的子类）
            if (responseFuture.isDone()) {
                return responseFuture.get();
            }
        } catch (Exception e) {
            // This should not happen in normal request process;
            logger.error("Got exception when trying to fetch the underlying result from AsyncRpcResult.");
            throw new RpcException(e);
        }

        // responseFuture还未完成，也没超时，创建一个默认返回
        return createDefaultValue(invocation);
    }

    /**
     * This method will always return after a maximum 'timeout' waiting:
     * 1. if value returns before timeout, return normally.
     * 2. if no value returns after timeout, throw TimeoutException.
     *
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @Override
    public Result get() throws InterruptedException, ExecutionException {

        // 如果线程池是ThreadlessExecutor，则需要将其所有任务先执行完
        if (executor != null && executor instanceof ThreadlessExecutor) {
            ThreadlessExecutor threadlessExecutor = (ThreadlessExecutor)executor;
            threadlessExecutor.waitAndDrain();
        }

        // 从响应future中获取响应：其实是等待HeaderExchangeHandler接收Response，并将Response设置到responseFuture中
        return responseFuture.get();
    }

    @Override
    public Result get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {

        // 如果线程池是ThreadlessExecutor，则需要将其所有任务先执行完
        if (executor != null && executor instanceof ThreadlessExecutor) {
            ThreadlessExecutor threadlessExecutor = (ThreadlessExecutor) executor;
            threadlessExecutor.waitAndDrain();
        }

        // 从响应future中获取响应：其实是等待HeaderExchangeHandler接收Response，并将Response设置到responseFuture中
        return responseFuture.get(timeout, unit);
    }

    @Override
    public Object recreate() throws Throwable {

        // 获取invocation
        RpcInvocation rpcInvocation = (RpcInvocation) invocation;

        // 调用模式是future，则直接返回future结果
        // 这里返回的future，是在AbstractInvoker.invoke方法末尾设置到RpcContext中
        if (InvokeMode.FUTURE == rpcInvocation.getInvokeMode()) {
            return RpcContext.getContext().getFuture();
        }

        // 获取实际的响应结果：getAppResponse返回DefaultFuture中get的结果（DecodeableRpcResult），recreate（走的是DecodeableRpcResult
        // 的父类AppResponse的实现）则会将异常抛出（如果有），或将结果返回
        return getAppResponse().recreate();
    }

    /**
     * 基于当前方法，可以不断添加回调但是又不会丢失{@link RpcContext}
     */
    @Override
    public Result whenCompleteWithContext(BiConsumer<Result, Throwable> fn) {

        // 添加回调方法
        this.responseFuture = this.responseFuture.whenComplete((v, t) -> {

            // 设置RpcContext
            beforeContext.accept(v, t);

            // 应用回调
            fn.accept(v, t);

            // 恢复RpcContext
            afterContext.accept(v, t);
        });
        return this;
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<Result, ? extends U> fn) {
        return this.responseFuture.thenApply(fn);
    }

    @Override
    @Deprecated
    public Map<String, String> getAttachments() {
        return getAppResponse().getAttachments();
    }

    @Override
    public Map<String, Object> getObjectAttachments() {
        return getAppResponse().getObjectAttachments();
    }

    @Override
    public void setAttachments(Map<String, String> map) {
        getAppResponse().setAttachments(map);
    }

    @Override
    public void setObjectAttachments(Map<String, Object> map) {
        getAppResponse().setObjectAttachments(map);
    }

    @Deprecated
    @Override
    public void addAttachments(Map<String, String> map) {
        getAppResponse().addAttachments(map);
    }

    @Override
    public void addObjectAttachments(Map<String, Object> map) {
        getAppResponse().addObjectAttachments(map);
    }

    @Override
    public String getAttachment(String key) {
        return getAppResponse().getAttachment(key);
    }

    @Override
    public Object getObjectAttachment(String key) {
        return getAppResponse().getObjectAttachment(key);
    }

    @Override
    public String getAttachment(String key, String defaultValue) {
        return getAppResponse().getAttachment(key, defaultValue);
    }

    @Override
    public Object getObjectAttachment(String key, Object defaultValue) {
        return getAppResponse().getObjectAttachment(key, defaultValue);
    }

    @Override
    public void setAttachment(String key, String value) {
        setObjectAttachment(key, value);
    }

    @Override
    public void setAttachment(String key, Object value) {
        setObjectAttachment(key, value);
    }

    @Override
    public void setObjectAttachment(String key, Object value) {
        getAppResponse().setAttachment(key, value);
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * 当进行回调时，可能需要记录当前线程上已有的{@link RpcContext}，就记录在{@link #tmpContext}和{@link #tmpServerContext}s
     * <p>
     * tmp context to use when the thread switch to Dubbo thread.
     */
    private RpcContext tmpContext;

    /**
     * 同{@link #tmpContext}
     */
    private RpcContext tmpServerContext;

    /**
     * 用来记录当前线程的{@link RpcContext}，并应用当前Rpc调用时的{@link RpcContext}：{@link #storedContext}和{@link #storedServerContext}
     */
    private BiConsumer<Result, Throwable> beforeContext = (appResponse, t) -> {
        tmpContext = RpcContext.getContext();
        tmpServerContext = RpcContext.getServerContext();
        RpcContext.restoreContext(storedContext);
        RpcContext.restoreServerContext(storedServerContext);
    };

    /**
     * 恢复原线程的{@link RpcContext}
     *
     * @see #beforeContext
     */
    private BiConsumer<Result, Throwable> afterContext = (appResponse, t) -> {
        RpcContext.restoreContext(tmpContext);
        RpcContext.restoreServerContext(tmpServerContext);
    };

    /**
     * Some utility methods used to quickly generate default AsyncRpcResult instance.
     */
    public static AsyncRpcResult newDefaultAsyncResult(AppResponse appResponse, Invocation invocation) {
        return new AsyncRpcResult(CompletableFuture.completedFuture(appResponse), invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Invocation invocation) {
        return newDefaultAsyncResult(null, null, invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Object value, Invocation invocation) {
        return newDefaultAsyncResult(value, null, invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Throwable t, Invocation invocation) {
        return newDefaultAsyncResult(null, t, invocation);
    }

    /**
     * 新建一个默认的异步请求结果
     */
    public static AsyncRpcResult newDefaultAsyncResult(Object value, Throwable t, Invocation invocation) {
        CompletableFuture<AppResponse> future = new CompletableFuture<>();

        // 生成结果，设置异常和响应数据
        AppResponse result = new AppResponse(invocation);
        if (t != null) {
            result.setException(t);
        } else {
            result.setValue(value);
        }

        // 设置future为complete，并将前面生成的result设置进去
        future.complete(result);
        return new AsyncRpcResult(future, invocation);
    }

    private static Result createDefaultValue(Invocation invocation) {
        ConsumerMethodModel method = (ConsumerMethodModel) invocation.get(Constants.METHOD_MODEL);
        return method != null ? new AppResponse(defaultReturn(method.getReturnClass())) : new AppResponse();
    }
}

