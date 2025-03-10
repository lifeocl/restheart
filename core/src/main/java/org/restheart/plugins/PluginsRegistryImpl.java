/*-
 * ========================LICENSE_START=================================
 * restheart-core
 * %%
 * Copyright (C) 2014 - 2020 SoftInstigate
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * =========================LICENSE_END==================================
 */
package org.restheart.plugins;

import static io.undertow.Handlers.path;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.mongodb.MongoClient;

import org.restheart.Configuration;
import org.restheart.ConfigurationException;
import org.restheart.exchange.PipelineInfo;
import org.restheart.handlers.CORSHandler;
import org.restheart.handlers.ConfigurableEncodingHandler;
import org.restheart.handlers.PipelinedHandler;
import org.restheart.handlers.PipelinedWrappingHandler;
import org.restheart.handlers.QueryStringRebuilder;
import org.restheart.handlers.RequestInterceptorsExecutor;
import org.restheart.handlers.RequestLogger;
import org.restheart.handlers.ResponseInterceptorsExecutor;
import org.restheart.handlers.ResponseSender;
import org.restheart.handlers.ServiceExchangeInitializer;
import org.restheart.handlers.TracingInstrumentationHandler;
import org.restheart.handlers.injectors.PipelineInfoInjector;
import org.restheart.handlers.injectors.XPoweredByInjector;
import org.restheart.plugins.RegisterPlugin.MATCH_POLICY;
import org.restheart.plugins.security.AuthMechanism;
import org.restheart.plugins.security.Authenticator;
import org.restheart.plugins.security.Authorizer;
import org.restheart.plugins.security.TokenManager;
import org.restheart.security.handlers.SecurityHandler;
import org.restheart.security.plugins.authorizers.FullAuthorizer;
import static org.restheart.plugins.InterceptPoint.REQUEST_AFTER_AUTH;
import static org.restheart.plugins.InterceptPoint.REQUEST_BEFORE_AUTH;
import static org.restheart.exchange.PipelineInfo.PIPELINE_TYPE.SERVICE;
import static org.restheart.handlers.PipelinedHandler.pipe;

import io.undertow.predicate.Predicate;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.PathMatcher;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class PluginsRegistryImpl implements PluginsRegistry {

    private static PluginsRegistryImpl HOLDER;
    private static final PathHandler ROOT_PATH_HANDLER = path();
    private static final PathMatcher<PipelineInfo> PIPELINE_INFOS = new PathMatcher<>();

    public static synchronized PluginsRegistryImpl getInstance() {
        if (HOLDER == null) {
            HOLDER = new PluginsRegistryImpl();
        }

        return HOLDER;
    }

    private Set<PluginRecord<AuthMechanism>> authMechanisms;

    private Set<PluginRecord<Authenticator>> authenticators;

    private Set<PluginRecord<Authorizer>> authorizers;

    private Optional<PluginRecord<TokenManager>> tokenManager;

    @SuppressWarnings("rawtypes")
    private Set<PluginRecord<Service>> services = new LinkedHashSet<>();
    // keep track of service initialization, to allow initializers to add services 
    // before actual scannit. this is used for intance by PolyglotDeployer
    private boolean servicesInitialized = false;

    private Set<PluginRecord<Initializer>> initializers;

    @SuppressWarnings("rawtypes")
    private Set<PluginRecord<Interceptor>> interceptors;

    private final Set<Predicate> globalSecurityPredicates = new LinkedHashSet<>();

    private PluginsRegistryImpl() {
    }

    /**
     * force plugin objects instantiation
     */
    public void instantiateAll() {
        var factory = PluginsFactory.getInstance();

        factory.initializers();
        factory.authMechanisms();
        factory.authorizers();
        factory.tokenManager();
        factory.authenticators();
        factory.interceptors();
        factory.services();

        factory.injectCoreDependencies();
    }

    /**
     * @return the authMechanisms
     */
    @Override
    public Set<PluginRecord<AuthMechanism>> getAuthMechanisms() {
        if (this.authMechanisms == null) {
            this.authMechanisms = new LinkedHashSet<>();
            this.authMechanisms.addAll(PluginsFactory.getInstance().authMechanisms());
        }

        return Collections.unmodifiableSet(this.authMechanisms);
    }

    /**
     * @return the authenticators
     */
    @Override
    public Set<PluginRecord<Authenticator>> getAuthenticators() {
        if (this.authenticators == null) {
            this.authenticators = new LinkedHashSet<>();
            this.authenticators.addAll(PluginsFactory.getInstance().authenticators());
        }

        return Collections.unmodifiableSet(this.authenticators);
    }

    /**
     *
     * @param name the name of the authenticator
     * @return the authenticator
     * @throws org.restheart.ConfigurationException
     */
    @Override
    public PluginRecord<Authenticator> getAuthenticator(String name) throws ConfigurationException {

        var auth = getAuthenticators().stream().filter(p -> name.equals(p.getName())).findFirst();

        if (auth != null && auth.isPresent()) {
            return auth.get();
        } else {
            throw new ConfigurationException("Authenticator " + name + " not found");
        }
    }

    /**
     * @return the authenticators
     */
    @Override
    public PluginRecord<TokenManager> getTokenManager() {
        if (this.tokenManager == null) {
            var tm = PluginsFactory.getInstance().tokenManager();

            this.tokenManager = tm == null ? Optional.empty() : Optional.of(tm);
        }

        return this.tokenManager.isPresent() ? this.tokenManager.get() : null;
    }

    /**
     * @return the authenticators
     */
    @Override
    public Set<PluginRecord<Authorizer>> getAuthorizers() {
        if (this.authorizers == null) {
            this.authorizers = PluginsFactory.getInstance().authorizers();
        }

        return Collections.unmodifiableSet(this.authorizers);
    }

    /**
     * @return the initializers
     */
    @Override
    public Set<PluginRecord<Initializer>> getInitializers() {
        if (this.initializers == null) {
            this.initializers = new LinkedHashSet<>();
            this.initializers.addAll(PluginsFactory.getInstance().initializers());
        }

        return Collections.unmodifiableSet(this.initializers);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Set<PluginRecord<Interceptor>> getInterceptors() {
        if (this.interceptors == null) {
            this.interceptors = new LinkedHashSet<>();
            this.interceptors.addAll(PluginsFactory.getInstance().interceptors());
        }

        return this.interceptors;
    }

    /**
     * @return the services
     */
    @Override
    @SuppressWarnings("rawtypes")
    public Set<PluginRecord<Service>> getServices() {
        if (!servicesInitialized) {
            this.services.addAll(PluginsFactory.getInstance().services());
            this.servicesInitialized = true;
        }

        return Collections.unmodifiableSet(this.services);
    }

    /**
     * global security predicates must all resolve to true to allow the request
     *
     * @return the globalSecurityPredicates allow to get and set the global security
     *         predicates to apply to all requests
     */
    @Override
    public Set<Predicate> getGlobalSecurityPredicates() {
        return globalSecurityPredicates;
    }

    @Override
    public void injectDependency(Object mclient) {
        if (mclient instanceof MongoClient) {
            PluginsFactory.getInstance().injectMongoDbDependencies((MongoClient) mclient);
        } else {
            throw new IllegalArgumentException("Unkwnown dependency type");
        }
    }

    @Override
    public PathHandler getRootPathHandler() {
        return ROOT_PATH_HANDLER;
    }

    @Override
    public void plugPipeline(String path, PipelinedHandler handler, PipelineInfo info) {
        if (info.getUriMatchPolicy() == MATCH_POLICY.PREFIX) {
            ROOT_PATH_HANDLER.addPrefixPath(path, handler);
            PIPELINE_INFOS.addPrefixPath(path, info);
        } else {
            ROOT_PATH_HANDLER.addExactPath(path, handler);
            PIPELINE_INFOS.addExactPath(path, info);
        }
    }

    @Override
    public PipelineInfo getPipelineInfo(String path) {
        var m = PIPELINE_INFOS.match(path);

        return m.getValue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void plugService(PluginRecord<Service> srv, final String uri, MATCH_POLICY mp, boolean secured) {
            SecurityHandler securityHandler;

            final Set<PluginRecord<AuthMechanism>> mechanisms = getAuthMechanisms();
            final Set<PluginRecord<Authorizer>> authorizers = getAuthorizers();
            final PluginRecord<TokenManager> tokenManager = getTokenManager();

            if (secured) {
                securityHandler = new SecurityHandler(
                        mechanisms,
                        authorizers,
                        tokenManager);
            } else {
                var _fauthorizers = new LinkedHashSet<PluginRecord<Authorizer>>();

                PluginRecord<Authorizer> _fauthorizer = new PluginRecord<>(
                        "fullAuthorizer",
                        "authorize any operation to any user",
                        true,
                        FullAuthorizer.class
                                .getName(),
                        new FullAuthorizer(false),
                        null
                );

                _fauthorizers.add(_fauthorizer);

                securityHandler = new SecurityHandler(
                        mechanisms,
                        _fauthorizers,
                        tokenManager);
            }

            var _srv = pipe(new PipelineInfoInjector(),
                    new TracingInstrumentationHandler(),
                    new RequestLogger(),
                    new ServiceExchangeInitializer(),
                    new CORSHandler(),
                    new XPoweredByInjector(),
                    new RequestInterceptorsExecutor(REQUEST_BEFORE_AUTH),
                    new QueryStringRebuilder(),
                    securityHandler,
                    new RequestInterceptorsExecutor(REQUEST_AFTER_AUTH),
                    new QueryStringRebuilder(),
                    PipelinedWrappingHandler
                            .wrap(new ConfigurableEncodingHandler(
                                    PipelinedWrappingHandler.wrap(
                                        srv.getInstance()))),
                    new ResponseInterceptorsExecutor(),
                    new ResponseSender()
            );

            plugPipeline(uri, _srv, new PipelineInfo(SERVICE, uri, mp, srv.getName()));

            this.services.add(srv);
    }

    /**
     * unplugs an handler from the root handler
     *
     * @param uri
     * @param mp
     */
    public void unplug(String uri, MATCH_POLICY mp) {
        var pi = getPipelineInfo(uri);

        this.services.removeIf(s -> s.getName().equals(pi.getName()));

        if (mp == MATCH_POLICY.PREFIX) {
            ROOT_PATH_HANDLER.removePrefixPath(uri);
            PIPELINE_INFOS.removePrefixPath(uri);
        } else {
            ROOT_PATH_HANDLER.removeExactPath(uri);
            PIPELINE_INFOS.removeExactPath(uri);
        }
    }
}
