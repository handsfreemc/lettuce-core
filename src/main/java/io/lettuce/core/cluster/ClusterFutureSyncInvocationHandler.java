/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.cluster;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.NodeSelectionSupport;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.internal.AbstractInvocationHandler;

/**
 * Invocation-handler to synchronize API calls which use Futures as backend. This class leverages the need to implement a full
 * sync class which just delegates every request.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @since 3.0
 */
@SuppressWarnings("unchecked")
class ClusterFutureSyncInvocationHandler<K, V> extends AbstractInvocationHandler {

    private final StatefulConnection<K, V> connection;
    private final Class<?> asyncCommandsInterface;
    private final Class<?> nodeSelectionInterface;
    private final Class<?> nodeSelectionCommandsInterface;
    private final Object asyncApi;

    private final Map<Method, Method> apiMethodCache = new ConcurrentHashMap<>(RedisClusterCommands.class.getMethods().length,
            1);
    private final Map<Method, Method> connectionMethodCache = new ConcurrentHashMap<>(5, 1);

    private static final Constructor<MethodHandles.Lookup> LOOKUP_CONSTRUCTOR;

    static {
        try {
            LOOKUP_CONSTRUCTOR = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }

        try {
            if (!LOOKUP_CONSTRUCTOR.isAccessible()) {
                LOOKUP_CONSTRUCTOR.setAccessible(true);
            }
        } catch (Throwable jdk9) {
        }
    }

    ClusterFutureSyncInvocationHandler(StatefulConnection<K, V> connection, Class<?> asyncCommandsInterface,
            Class<?> nodeSelectionInterface, Class<?> nodeSelectionCommandsInterface, Object asyncApi) {
        this.connection = connection;
        this.asyncCommandsInterface = asyncCommandsInterface;
        this.nodeSelectionInterface = nodeSelectionInterface;
        this.nodeSelectionCommandsInterface = nodeSelectionCommandsInterface;
        this.asyncApi = asyncApi;
    }

    /**
     *
     * @see AbstractInvocationHandler#handleInvocation(Object, Method, Object[])
     */
    @Override
    protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {

        try {

            if (method.isDefault()) {
                return getDefaultMethodHandle(method).bindTo(proxy).invokeWithArguments(args);
            }

            if (method.getName().equals("getConnection") && args.length > 0) {
                Method targetMethod = connectionMethodCache.computeIfAbsent(method, key -> {
                    try {
                        return connection.getClass().getMethod(key.getName(), key.getParameterTypes());
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException(e);
                    }
                });

                Object result = targetMethod.invoke(connection, args);
                if (result instanceof StatefulRedisClusterConnection) {
                    StatefulRedisClusterConnection<K, V> connection = (StatefulRedisClusterConnection<K, V>) result;
                    return connection.sync();
                }

                if (result instanceof StatefulRedisConnection) {
                    StatefulRedisConnection<K, V> connection = (StatefulRedisConnection<K, V>) result;
                    return connection.sync();
                }
            }

            if (method.getName().equals("readonly") && args.length == 1) {
                return nodes((Predicate<RedisClusterNode>) args[0], ClusterConnectionProvider.Intent.READ, false);
            }

            if (method.getName().equals("nodes") && args.length == 1) {
                return nodes((Predicate<RedisClusterNode>) args[0], ClusterConnectionProvider.Intent.WRITE, false);
            }

            if (method.getName().equals("nodes") && args.length == 2) {
                return nodes((Predicate<RedisClusterNode>) args[0], ClusterConnectionProvider.Intent.WRITE, (Boolean) args[1]);
            }

            Method targetMethod = apiMethodCache.computeIfAbsent(method, key -> {

                try {
                    return asyncApi.getClass().getMethod(key.getName(), key.getParameterTypes());
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(e);
                }
            });

            Object result = targetMethod.invoke(asyncApi, args);

            if (result instanceof RedisFuture) {
                RedisFuture<?> command = (RedisFuture<?>) result;
                if (!method.getName().equals("exec") && !method.getName().equals("multi")) {
                    if (connection instanceof StatefulRedisConnection && ((StatefulRedisConnection) connection).isMulti()) {
                        return null;
                    }
                }
                return LettuceFutures.awaitOrCancel(command, connection.getTimeout(), connection.getTimeoutUnit());
            }

            return result;

        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    protected Object nodes(Predicate<RedisClusterNode> predicate, ClusterConnectionProvider.Intent intent, boolean dynamic) {

        NodeSelectionSupport<RedisCommands<K, V>, ?> selection = null;

        if (connection instanceof StatefulRedisClusterConnectionImpl) {

            StatefulRedisClusterConnectionImpl impl = (StatefulRedisClusterConnectionImpl) connection;

            if (dynamic) {
                selection = new DynamicNodeSelection<RedisCommands<K, V>, Object, K, V>(
                        impl.getClusterDistributionChannelWriter(), predicate, intent, StatefulRedisConnection::sync);
            } else {

                selection = new StaticNodeSelection<RedisCommands<K, V>, Object, K, V>(
                        impl.getClusterDistributionChannelWriter(), predicate, intent, StatefulRedisConnection::sync);
            }
        }

        if (connection instanceof StatefulRedisClusterPubSubConnectionImpl) {

            StatefulRedisClusterPubSubConnectionImpl impl = (StatefulRedisClusterPubSubConnectionImpl) connection;
            selection = new StaticNodeSelection<RedisCommands<K, V>, Object, K, V>(impl.getClusterDistributionChannelWriter(),
                    predicate, intent, StatefulRedisConnection::sync);
        }

        NodeSelectionInvocationHandler h = new NodeSelectionInvocationHandler((AbstractNodeSelection<?, ?, ?, ?>) selection,
                asyncCommandsInterface, connection.getTimeout(), connection.getTimeoutUnit());
        return Proxy.newProxyInstance(NodeSelectionSupport.class.getClassLoader(), new Class<?>[] {
                nodeSelectionCommandsInterface, nodeSelectionInterface }, h);
    }

    private static MethodHandle getDefaultMethodHandle(Method method) {

        Class<?> declaringClass = method.getDeclaringClass();

        try {
            if (LOOKUP_CONSTRUCTOR.isAccessible()) {
                MethodHandles.Lookup result = LOOKUP_CONSTRUCTOR.newInstance(declaringClass, MethodHandles.Lookup.PRIVATE);
                return result.unreflectSpecial(method, declaringClass);
            }

            return MethodHandles.lookup().findSpecial(method.getDeclaringClass(), method.getName(),
                    MethodType.methodType(method.getReturnType(), method.getParameterTypes()), method.getDeclaringClass());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Did not pass in an interface method: " + method, e);
        }
    }
}
