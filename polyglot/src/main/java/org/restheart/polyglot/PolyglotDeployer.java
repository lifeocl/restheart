/*-
 * ========================LICENSE_START=================================
 * restheart-security
 * %%
 * Copyright (C) 2018 - 2020 SoftInstigate
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
package org.restheart.polyglot;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import com.mongodb.MongoClient;

import static org.fusesource.jansi.Ansi.ansi;
import static org.fusesource.jansi.Ansi.Color.GREEN;

import org.graalvm.polyglot.Source;
import org.restheart.ConfigurationException;
import org.restheart.ConfigurationKeys;
import org.restheart.plugins.ConfigurationScope;
import org.restheart.plugins.Initializer;
import org.restheart.plugins.InjectConfiguration;
import org.restheart.plugins.InjectMongoClient;
import org.restheart.plugins.InjectPluginsRegistry;
import org.restheart.plugins.PluginRecord;
import org.restheart.plugins.PluginsRegistry;
import org.restheart.plugins.RegisterPlugin;
import org.restheart.plugins.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
@RegisterPlugin(name = "polyglotDeployer", description = "handles GraalVM polyglot plugins", enabledByDefault = true, defaultURI = "/graal")
public class PolyglotDeployer implements Initializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolyglotDeployer.class);

    private Path pluginsDirectory = null;

    private PluginsRegistry registry = null;

    private static final Map<Path, AbstractJavaScriptService> DEPLOYEES = new HashMap<>();

    private WatchService watchService;

    private Path requireCdw;

    private MongoClient mclient;

    @InjectPluginsRegistry
    public void reg(PluginsRegistry registry) {
        this.registry = registry;

        // make sure to invoke this after both @Injected methods are invoked
        if (pluginsDirectory != null) {
            deployAll(pluginsDirectory);
            watch(pluginsDirectory);
        }
    }

    @InjectConfiguration(scope = ConfigurationScope.ALL)
    public void init(Map<String, Object> args) throws ConfigurationException {
        if (!isRunningOnGraalVM()) {
            LOGGER.warn("Not running on GraalVM, polyglot plugins deployer disabled!");
            return;
        }

        pluginsDirectory = getPluginsDirectory(args);

        this.requireCdw = pluginsDirectory.resolve("node_modules").toAbsolutePath();

        if (!Files.exists(requireCdw)) {
            LOGGER.debug("Creating CommonJS modules directory {}", requireCdw);
            try {
                Files.createDirectory(requireCdw);
            } catch (IOException ioe) {
                LOGGER.warn("Cound not create CommonJS modules directory {}", requireCdw);
            }
        }

        LOGGER.info("Folder where the CommonJS modules are located: {}", requireCdw.toAbsolutePath());

        // make sure to invoke this after both @Injected methods are invoked
        if (registry != null) {
            deployAll(pluginsDirectory);
            watch(pluginsDirectory);
        }
    }

    @InjectMongoClient
    public void mc(MongoClient mclient) {
        this.mclient = mclient;
    }

    private boolean isRunningOnGraalVM() {
        try {
            Class.forName("org.graalvm.polyglot.Value");
        } catch (ClassNotFoundException cnfe) {
            return false;
        }

        return true;
    }

    @Override
    public void init() {
        // nothing to do
    }

    private void deployAll(Path pluginsDirectory) {
        for (var pluginPath : findJsPlugins(pluginsDirectory)) {
            try {
                deploy(pluginPath);
            } catch (Throwable t) {
                LOGGER.error("Error deploying {}", pluginPath.toAbsolutePath(), t);
            }
        }
    }

    private void watch(Path pluginsDirectory) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            pluginsDirectory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

            var watchThread = new Thread(() -> {
                try {
                    Thread.sleep(1000);
                } catch (Throwable t) {
                    // nothing to do
                }

                WatchKey key;
                try {
                    while ((key = watchService.take()) != null) {
                        for (var event : key.pollEvents()) {
                            var eventContext = event.context();
                            var pluginPath = pluginsDirectory.resolve(eventContext.toString());

                            LOGGER.trace("fs event {} {}", event.kind(), eventContext.toString());

                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                                var language = Source.findLanguage(pluginPath.toFile());
                                if ("js".equals(language)) {
                                    try {
                                        deploy(pluginPath);
                                    } catch (Throwable t) {
                                        LOGGER.error("Error deploying {}", pluginPath.toAbsolutePath(), t);
                                    }
                                }
                            } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                                var language = Source.findLanguage(pluginPath.toFile());
                                if ("js".equals(language)) {
                                    try {
                                        undeploy(pluginPath);
                                        deploy(pluginPath);
                                    } catch (Throwable t) {
                                        LOGGER.warn("Error updating {}", pluginPath.toAbsolutePath(), t);
                                    }
                                }
                            } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                                undeploy(pluginPath);
                            }
                        }

                        key.reset();
                    }
                } catch (IOException | InterruptedException ex) {
                    LOGGER.error("Error watching {}" + pluginsDirectory.toAbsolutePath(), ex);
                }
            });

            watchThread.start();

        } catch (IOException ex) {
            LOGGER.error("Error watching {}: {}" + pluginsDirectory.toAbsolutePath(), ex);
        }
    }

    private Path getPluginsDirectory(Map<String, Object> args) {
        var _pluginsDir = args.getOrDefault(ConfigurationKeys.PLUGINS_DIRECTORY_PATH_KEY, null);

        if (_pluginsDir == null || !(_pluginsDir instanceof String)) {
            return null;
        }

        var pluginsDir = (String) _pluginsDir;

        if (pluginsDir.startsWith("/")) {
            return Paths.get(pluginsDir);
        } else {
            // this is to allow specifying the plugins directory path
            // relative to the jar (also working when running from classes)
            var location = PluginsRegistry.class.getProtectionDomain().getCodeSource().getLocation();

            File locationFile = new File(location.getPath());

            pluginsDir = locationFile.getParent() + File.separator + pluginsDir;

            return FileSystems.getDefault().getPath(pluginsDir);
        }
    }

    private ArrayList<Path> findJsPlugins(Path pluginsDirectory) {
        if (pluginsDirectory == null) {
            return new ArrayList<>();
        } else {
            checkPluginDirectory(pluginsDirectory);
        }

        var paths = new ArrayList<Path>();

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(pluginsDirectory, "*.js")) {
            for (Path path : directoryStream) {
                if (!Files.isReadable(path)) {
                    LOGGER.error("Plugin script {} is not readable", path);
                    throw new IllegalStateException("Plugin script " + path + " is not readable");
                }

                paths.add(path);
                LOGGER.info("Found plugin script {}", path);
            }
        } catch (IOException ex) {
            LOGGER.error("Cannot read scritps in plugins directory", pluginsDirectory, ex);
        }

        return paths;
    }

    private void checkPluginDirectory(Path pluginsDirectory) {
        if (!Files.exists(pluginsDirectory)) {
            LOGGER.error("Plugin directory {} does not exist", pluginsDirectory);
            throw new IllegalStateException("Plugins directory " + pluginsDirectory + " does not exist");
        }

        if (!Files.isReadable(pluginsDirectory)) {
            LOGGER.error("Plugin directory {} is not readable", pluginsDirectory);
            throw new IllegalStateException("Plugins directory " + pluginsDirectory + " is not readable");
        }
    }

    @SuppressWarnings("rawtypes")
    private void deploy(Path pluginPath) throws IOException {
        var language = Source.findLanguage(pluginPath.toFile());

        if ("js".equals(language)) {
            if (isRunningOnNode()) {
                var executor = Executors.newSingleThreadExecutor();
                executor.submit(() -> {
                    try {
                        var srvf = NodeService.get(pluginPath, this.requireCdw, this.mclient);

                        while (!srvf.isDone()) {
                            Thread.sleep(300);
                        }

                        var srv = srvf.get();

                        var record = new PluginRecord<Service>(srv.getName(), "description", true,
                                srv.getClass().getName(), srv, new HashMap<>());

                        registry.plugService(record, srv.getUri(), srv.getMatchPolicy(), srv.isSecured());

                        DEPLOYEES.put(pluginPath.toAbsolutePath(), srv);

                        LOGGER.info(ansi().fg(GREEN).a("URI {} bound to service {}, description: {}, secured: {}, uri match {}").reset().toString(),
                            srv.getUri(), srv.getName(), srv.getDescription(), srv.isSecured(), srv.getMatchPolicy());
                    } catch (IOException | InterruptedException | ExecutionException ex) {
                        LOGGER.error("Error deployng node plugin {}", pluginPath, ex);
                        return;
                    }
                });

            } else {
                var srv = new JavaScriptService(pluginPath, this.requireCdw, this.mclient);

                var record = new PluginRecord<Service>(srv.getName(), "description", true, srv.getClass().getName(),
                        srv, new HashMap<>());

                registry.plugService(record, srv.getUri(), srv.getMatchPolicy(), srv.isSecured());

                DEPLOYEES.put(pluginPath.toAbsolutePath(), srv);

                LOGGER.info(ansi().fg(GREEN).a("URI {} bound to service {}, description: {}, secured: {}, uri match {}").reset().toString(),
                    srv.getUri(), srv.getName(), srv.getDescription(), srv.isSecured(), srv.getMatchPolicy());
            }
        }
    }

    private void undeploy(Path pluginPath) {
        var srvToUndeploy = DEPLOYEES.remove(pluginPath.toAbsolutePath());

        if (srvToUndeploy != null) {
            registry.unplug(srvToUndeploy.getUri(), srvToUndeploy.getMatchPolicy());

            LOGGER.info(ansi().fg(GREEN).a("removed service {} bound to URI {}").reset().toString(),
                    srvToUndeploy.getName(), srvToUndeploy.getUri());
        }
    }

    private boolean isRunningOnNode() {
        return NodeQueue.instance().isRunningOnNode();
    }
}
