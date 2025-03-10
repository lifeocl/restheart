/*-
 * ========================LICENSE_START=================================
 * restheart-commons
 * %%
 * Copyright (C) 2019 - 2020 SoftInstigate
 * %%
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
 * =========================LICENSE_END==================================
 */
package org.restheart.utils;

import io.undertow.server.HttpServerExchange;
import java.lang.reflect.Type;
import java.util.Map;
import org.restheart.cache.Cache;
import org.restheart.cache.CacheFactory;
import org.restheart.cache.LoadingCache;
import org.restheart.exchange.ByteArrayProxyRequest;
import org.restheart.exchange.PipelineInfo;
import static org.restheart.exchange.PipelineInfo.PIPELINE_TYPE.SERVICE;
import org.restheart.plugins.ExchangeTypeResolver;
import org.restheart.plugins.InitPoint;
import org.restheart.plugins.Initializer;
import org.restheart.plugins.InterceptPoint;
import org.restheart.plugins.Interceptor;
import org.restheart.plugins.Plugin;
import org.restheart.plugins.PluginsRegistry;
import org.restheart.plugins.RegisterPlugin;
import org.restheart.plugins.Service;
import org.restheart.plugins.RegisterPlugin.MATCH_POLICY;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class PluginUtils {
    @SuppressWarnings("rawtypes")
    public static InterceptPoint interceptPoint(Interceptor interceptor) {
        var a = interceptor.getClass()
                .getDeclaredAnnotation(RegisterPlugin.class);

        if (a == null) {
            return null;
        } else {
            return a.interceptPoint();
        }
    }

    public static InitPoint initPoint(Initializer initializer) {
        var a = initializer.getClass()
                .getDeclaredAnnotation(RegisterPlugin.class);

        if (a == null) {
            return null;
        } else {
            return a.initPoint();
        }
    }

    @SuppressWarnings("rawtypes")
    public static boolean requiresContent(Interceptor interceptor) {
        var a = interceptor.getClass()
                .getDeclaredAnnotation(RegisterPlugin.class);

        if (a == null) {
            return false;
        } else {
            return a.requiresContent();
        }
    }

    /**
     *
     * @param plugin
     * @return the plugin name
     */
    public static String name(Plugin plugin) {
        var a = plugin.getClass()
                .getDeclaredAnnotation(RegisterPlugin.class);

        return a == null
                ? null
                : a.name();
    }

    /**
     *
     * @param service
     * @return the service default URI. If not explicitly set via defaulUri
     * attribute, it is /[service-name]
     */
    @SuppressWarnings("rawtypes")
    public static String defaultURI(Service service) {
        var a = service.getClass()
                .getDeclaredAnnotation(RegisterPlugin.class);

        return a == null
                ? null
                : a.defaultURI() == null || "".equals(a.defaultURI())
                ? "/".concat(a.name())
                : a.defaultURI();
    }

    /**
     *
     * @param service
     * @return uri match policy.
     */
    @SuppressWarnings("rawtypes")
    public static MATCH_POLICY uriMatchPolicy(Service service) {
        return service.getClass()
                .getDeclaredAnnotation(RegisterPlugin.class).uriMatchPolicy();
    }

    /**
     *
     * @param <P>
     * @param serviceClass
     * @return the service default URI. If not explicitly set via defaulUri
     * attribute, it is /[service-name]
     */
    @SuppressWarnings("rawtypes")
    public static <P extends Service> String defaultURI(Class<P> serviceClass) {
        var a = serviceClass
                .getDeclaredAnnotation(RegisterPlugin.class);

        return a == null
                ? null
                : a.defaultURI() == null || "".equals(a.defaultURI())
                ? "/".concat(a.name())
                : a.defaultURI();
    }

    /**
     *
     * @param <P>
     * @param conf the plugin configuration got from @InjectConfiguration
     * @param serviceClass the class of the service
     * @return the actual service uri set in cofiguration or the defaultURI
     */
    @SuppressWarnings("rawtypes")
    public static <P extends Service> String actualUri(Map<String, Object> conf,
            Class<P> serviceClass) {

        if (conf != null
                && conf.get("uri") != null
                && conf.get("uri") instanceof String) {
            return (String) conf.get("uri");
        } else {
            return PluginUtils.defaultURI(serviceClass);
        }
    }

    /**
     *
     * @param service
     * @return the intercept points of interceptors that must not be executed on
     * requests handled by service
     */
    @SuppressWarnings("rawtypes")
    public static InterceptPoint[] dontIntercept(Service service) {
        var a = service.getClass()
                .getDeclaredAnnotation(RegisterPlugin.class);

        if (a == null) {
            return new InterceptPoint[0];
        } else {
            return a.dontIntercept();
        }
    }

    /**
     *
     * @param registry
     * @param exchange
     * @return the service handling the exchange or null if the request is not
     * handled by a service
     */
    @SuppressWarnings("rawtypes")
    public static Service handlingService(PluginsRegistry registry,
            HttpServerExchange exchange) {
        var pi = pipelineInfo(exchange);

        if (pi != null && pi.getType() == SERVICE) {
            var srvName = pi.getName();

            if (srvName != null) {
                var _s = registry.getServices()
                        .stream()
                        .filter(s -> srvName.equals(s.getName()))
                        .map(s -> s.getInstance())
                        .findAny();

                if (_s.isPresent()) {
                    return _s.get();
                }
            }
        }

        return null;
    }

    /**
     *
     * @param registry
     * @param exchange
     * @return the intercept points of interceptors that must not be executed on
     * the exchange
     */
    public static InterceptPoint[] dontIntercept(PluginsRegistry registry,
            HttpServerExchange exchange) {
        var hs = handlingService(registry, exchange);

        return hs == null
                ? new InterceptPoint[0]
                : dontIntercept(hs);
    }

    public static PipelineInfo pipelineInfo(HttpServerExchange exchange) {
        return ByteArrayProxyRequest.of(exchange).getPipelineInfo();
    }

    @SuppressWarnings("rawtypes")
    private static LoadingCache<ExchangeTypeResolver, Type> RC = CacheFactory
            .createLocalLoadingCache(
                    Integer.MAX_VALUE,
                    Cache.EXPIRE_POLICY.NEVER, 0,
                    plugin -> plugin.requestType());

    @SuppressWarnings("rawtypes")
    private static LoadingCache<ExchangeTypeResolver, Type> SC = CacheFactory
            .createLocalLoadingCache(
                    Integer.MAX_VALUE,
                    Cache.EXPIRE_POLICY.NEVER, 0,
                    plugin -> plugin.responseType());

    /**
     * Plugin.requestType() is heavy. This helper methods speeds up invocation
     * using a cache
     *
     * @param plugin
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static Type cachedRequestType(ExchangeTypeResolver plugin) {
        return RC.getLoading(plugin).get();
    }

    /**
     * Plugin.responseType() is heavy. This helper methods speeds up invocation
     * using a cache
     *
     * @param plugin
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static Type cachedResponseType(ExchangeTypeResolver plugin) {
        return SC.getLoading(plugin).get();
    }
}
