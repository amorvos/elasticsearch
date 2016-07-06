/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.plugin.cloud.azure;

import org.elasticsearch.cloud.azure.AzureModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.azure.AzureDiscovery;
import org.elasticsearch.discovery.azure.AzureUnicastHostsProvider;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardRepository;
import org.elasticsearch.index.store.IndexStoreModule;
import org.elasticsearch.index.store.smbmmapfs.SmbMmapFsIndexStore;
import org.elasticsearch.index.store.smbsimplefs.SmbSimpleFsIndexStore;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.RepositoriesModule;
import org.elasticsearch.repositories.azure.AzureRepository;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.discovery.zen.ZenDiscovery;
import org.elasticsearch.discovery.zen.ping.unicast.UnicastHostsProvider;
import org.elasticsearch.index.store.IndexStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.SuppressForbidden;

import static org.elasticsearch.cloud.azure.AzureModule.isSnapshotReady;

/**
 *
 */
public class CloudAzurePlugin extends Plugin {

    private final Settings settings;
    protected final ESLogger logger = Loggers.getLogger(CloudAzurePlugin.class);

    public CloudAzurePlugin(Settings settings) {
        this.settings = settings;
        logger.trace("starting azure plugin...");
    }

    @Override
    public String name() {
        return "cloud-azure";
    }

    @Override
    public String description() {
        return "Cloud Azure Plugin";
    }

    @Override
    public Collection<Module> nodeModules() {
        List<Module> modules = new ArrayList<>();
        if (AzureModule.isCloudReady(settings)) {
            modules.add(new AzureModule(settings));
        }
        return modules;
    }

    public void onModule(RepositoriesModule module) {

        ClassLoader azureCL = this.getAzureClassLoader();

        Class<? extends Repository> repository = null;
        try {
            repository = (Class<? extends Repository>) azureCL.loadClass("org.elasticsearch.repositories.azure.AzureRepository");
        } catch (ClassNotFoundException cnfe) {
            throw new IllegalStateException("Cannot load plugin class; is the plugin class setup correctly?", cnfe);
        }

        if (isSnapshotReady(settings, logger)) {
            module.registerRepository("azure", repository, BlobStoreIndexShardRepository.class);
        }
    }

    public void onModule(DiscoveryModule discoveryModule) {
        ClassLoader azureCL = getAzureClassLoader();

        Class<? extends ZenDiscovery> discovery = null;
        Class<? extends UnicastHostsProvider> hostProvider = null;
        try {
            discovery = (Class<? extends ZenDiscovery>) azureCL.loadClass("org.elasticsearch.discovery.azure.AzureDiscovery");
            hostProvider = (Class<? extends UnicastHostsProvider>) azureCL.
                loadClass("org.elasticsearch.discovery.azure.AzureUnicastHostsProvider");
        } catch (ClassNotFoundException cnfe) {
            throw new IllegalStateException("Cannot load plugin class; is the plugin class setup correctly?", cnfe);
        }

        if (AzureModule.isDiscoveryReady(settings, logger)) {
            discoveryModule.addDiscoveryType("azure", discovery);
            discoveryModule.addUnicastHostProvider(hostProvider);
        }
    }

    public void onModule(IndexStoreModule storeModule) {
        ClassLoader azureCL = getAzureClassLoader();
        Class<? extends IndexStore> mapFsIndexStore = null;
        Class<? extends IndexStore> simpleFsIndexStore = null;

        try {
            mapFsIndexStore = (Class<? extends IndexStore>) azureCL.
                loadClass("org.elasticsearch.index.store.smbmmapfs.SmbMmapFsIndexStore");
            simpleFsIndexStore = (Class<? extends IndexStore>) azureCL.
                loadClass("org.elasticsearch.index.store.simplefs.SmbSimpleFsIndexStore");
        } catch (ClassNotFoundException cnfe) {
            throw new IllegalStateException("Cannot load plugin class; is the plugin class setup correctly?", cnfe);
        }

        storeModule.addIndexStore("smb_mmap_fs", mapFsIndexStore);
        storeModule.addIndexStore("smb_simple_fs", simpleFsIndexStore);
    }

    private ClassLoader getAzureClassLoader(){
        String baseLib = detectLibFolder();
        List<URL> cp = this.getAzureClassLoaderPath(baseLib);

        return URLClassLoader.newInstance(cp.toArray(new URL[cp.size()]), getClass().getClassLoader());
    }

    private static String detectLibFolder() {
        ClassLoader cl = CloudAzurePlugin.class.getClassLoader();

        // we could get the URL from the URLClassloader directly
        // but that can create issues when running the tests from the IDE
        // we could detect that by loading resources but that as well relies on
        // the JAR URL
        String classToLookFor = CloudAzurePlugin.class.getName().replace(".", "/").concat(".class");
        URL classURL = cl.getResource(classToLookFor);
        if (classURL == null) {
            throw new IllegalStateException("Cannot detect itself; something is wrong with this ClassLoader " + cl);
        }

        String base = classURL.toString();

        // extract root
        // typically a JAR URL
        int index = base.indexOf("!/");
        if (index > 0) {
            base = base.substring(0, index);
            // remove its prefix (jar:)
            base = base.substring(4);
            // remove the trailing jar
            index = base.lastIndexOf("/");
            base = base.substring(0, index + 1);
        }
        // not a jar - something else, do a best effort here
        else {
            // remove the class searched
            base = base.substring(0, base.length() - classToLookFor.length());
        }

        // append /
        if (!base.endsWith("/")) {
            base = base.concat("/");
        }

        return base;
    }

    protected List<URL> getAzureClassLoaderPath(String baseLib) {
        List<URL> cp = new ArrayList<>();
        // add Azure jars
        discoverJars(createURI(baseLib, "azure-libs"), cp, true);
        return cp;
    }

    private URI createURI(String base, String suffix) {
        String location = base + suffix;
        try {
            return new URI(location);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(String.format(Locale.ROOT, "Cannot detect plugin folder; [%s] seems invalid", location), ex);
        }
    }

    @SuppressForbidden(reason = "discover nested jar")
    private void discoverJars(URI libPath, List<URL> cp, boolean optional) {
        try {
            Path[] jars = FileSystemUtils.files(PathUtils.get(libPath), "*.jar");

            for (Path path : jars) {
                cp.add(path.toUri().toURL());
            }
        } catch (IOException ex) {
            if (!optional) {
                throw new IllegalStateException("Cannot compute plugin classpath", ex);
            }
        }
    }
}
