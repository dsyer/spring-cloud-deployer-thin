/*
 * Copyright 2012-2015 the original author or authors.
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

package org.springframework.cloud.deployer.mem;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.loader.archive.Archive;
import org.springframework.boot.loader.archive.ExplodedArchive;
import org.springframework.boot.loader.archive.JarFileArchive;
import org.springframework.boot.loader.thin.ArchiveUtils;
import org.springframework.boot.loader.tools.MainClassFinder;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.Resource;
import org.springframework.util.ClassUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.ReflectionUtils;

/**
 * An {@link AppDeployer} that launches apps in the same JVM, using a separate class
 * loader.
 * 
 * @author Dave Syer
 *
 */
public class ThinJarAppDeployer implements AppDeployer {

	private Map<String, Wrapper> apps = new LinkedHashMap<>();

	private String name = "thin";

	private String[] profiles = new String[0];

	public ThinJarAppDeployer() {
		this("thin");
	}

	public ThinJarAppDeployer(String name, String... profiles) {
		this.name = name;
		this.profiles = profiles;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		Wrapper wrapper = new Wrapper(request.getResource(), getName(request),
				getProfiles(request));
		String id = wrapper.getId();
		if (!apps.containsKey(id)) {
			apps.put(id, wrapper);
		}
		else {
			wrapper = apps.get(id);
		}
		wrapper.run(request.getCommandlineArguments());
		return id;
	}

	private String[] getProfiles(AppDeploymentRequest request) {
		return this.profiles;
	}

	private String getName(AppDeploymentRequest request) {
		return this.name;
	}

	@Override
	public AppStatus status(String id) {
		return apps.containsKey(id) ? apps.get(id).status() : null;
	}

	@Override
	public void undeploy(String id) {
		if (apps.containsKey(id)) {
			apps.get(id).close();
		}
	}

}

class Wrapper {

	private static Log logger = LogFactory.getLog(Wrapper.class);

	private String id;

	private Object app;

	private AppStatus status;

	private Resource resource;

	private DeploymentState state = DeploymentState.undeployed;

	private String name = "thin";

	private String[] profiles = new String[0];

	public Wrapper(Resource resource, String name, String[] profiles) {
		this.resource = resource;
		this.name = name;
		this.profiles = profiles;
		try {
			this.id = DigestUtils.md5DigestAsHex(resource.getFile().getAbsolutePath()
					.getBytes(Charset.forName("UTF-8")));
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Not a valid file resource");
		}
		this.status = AppStatus.of(id).with(new InMemoryAppInstanceStatus(this)).build();
	}

	public void run(List<String> args) {
		if (this.app == null) {
			this.state = DeploymentState.deploying;
			ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
			try {
				Archive child = new JarFileArchive(resource.getFile());
				Class<?> cls = createContextRunnerClass(child);
				this.app = cls.newInstance();
				runContext(getMainClass(child), args.toArray(new String[0]));
				boolean running = isRunning();
				this.state = running ? DeploymentState.deployed : DeploymentState.failed;
			}
			catch (Exception e) {
				this.state = DeploymentState.failed;
				logger.error("Cannot deploy " + resource, e);
			}
			finally {
				ClassUtils.overrideThreadContextClassLoader(contextLoader);
			}
		}
	}

	private boolean isRunning() {
		Method method = ReflectionUtils.findMethod(this.app.getClass(), "isRunning");
		return (Boolean) ReflectionUtils.invokeMethod(method, this.app);
	}

	private void runContext(String mainClass, String... args) {
		Method method = ReflectionUtils.findMethod(this.app.getClass(), "run",
				String.class, String[].class);
		ReflectionUtils.invokeMethod(method, this.app, mainClass, args);
	}

	private Class<?> createContextRunnerClass(Archive child)
			throws Exception, ClassNotFoundException {
		Archive parent = createArchive();
		List<Archive> extracted = new ArchiveUtils().extract(child, name, profiles);
		ClassLoader loader = createClassLoader(extracted, parent, child);
		ClassUtils.overrideThreadContextClassLoader(loader);
		reset();
		Class<?> cls = loader.loadClass(ContextRunner.class.getName());
		return cls;
	}

	private void reset() {
		if (ClassUtils.isPresent(
				"org.apache.catalina.webresources.TomcatURLStreamHandlerFactory", null)) {
			setField(ClassUtils.resolveClassName(
					"org.apache.catalina.webresources.TomcatURLStreamHandlerFactory",
					null), "instance", null);
			setField(URL.class, "factory", null);
		}
	}

	private void setField(Class<?> type, String name, Object value) {
		Field field = ReflectionUtils.findField(type, name);
		ReflectionUtils.makeAccessible(field);
		ReflectionUtils.setField(field, null, value);
	}

	public void close() {
		if (this.app != null) {
			try {
				Method method = ReflectionUtils.findMethod(this.app.getClass(), "close");
				ReflectionUtils.invokeMethod(method, this.app);
			}
			catch (Exception e) {
				this.state = DeploymentState.unknown;
				logger.error("Cannot undeploy " + resource, e);
			}
			finally {
				if (this.app != null) {
					try {
						((URLClassLoader) app.getClass().getClassLoader()).close();
						this.app = null;
					}
					catch (Exception e) {
						this.state = DeploymentState.unknown;
						logger.error("Cannot clean up " + resource, e);
					}
					finally {
						this.app = null;
						System.gc();
					}
				}
			}
		}
		this.state = DeploymentState.undeployed;
	}

	public String getId() {
		return id;
	}

	public AppStatus status() {
		return this.status;
	}

	public Object getApp() {
		return this.app;
	}

	public DeploymentState getDeploymentState() {
		// TODO: support full lifecycle
		return this.state;
	}

	@Override
	public String toString() {
		return "Wrapper [id=" + id + ", resource=" + resource + ", state=" + state + "]";
	}

	protected String getMainClass(Archive archive) {
		try {
			Manifest manifest = archive.getManifest();
			String mainClass = null;
			if (manifest != null) {
				mainClass = manifest.getMainAttributes().getValue("Start-Class");
			}
			if (mainClass == null) {
				throw new IllegalStateException(
						"No 'Start-Class' manifest entry specified in " + this);
			}
			return mainClass;
		}
		catch (Exception e) {
			try {
				File root = new File(archive.getUrl().toURI());
				if (archive instanceof ExplodedArchive) {
					return MainClassFinder.findSingleMainClass(root);
				}
				else {
					return MainClassFinder.findSingleMainClass(new JarFile(root), "/");
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException("Cannot find main class", e);
			}
		}
	}

	private ClassLoader createClassLoader(List<Archive> archives, Archive... roots) {
		URL[] urls = getUrls(archives, roots);
		URLClassLoader classLoader = new URLClassLoader(urls,
				getClass().getClassLoader().getParent());
		Thread.currentThread().setContextClassLoader(classLoader);
		return classLoader;
	}

	private URL[] getUrls(List<Archive> archives, Archive... roots) {
		try {
			List<URL> urls = new ArrayList<URL>(archives.size());
			for (Archive archive : archives) {
				urls.add(archive.getUrl());
			}
			for (int i = 0; i < roots.length; i++) {
				urls.add(i, roots[i].getUrl());
			}
			return urls.toArray(new URL[0]);
		}
		catch (MalformedURLException e) {
			throw new IllegalStateException("Cannot create URL", e);
		}
	}

	protected final Archive createArchive() {
		try {
			ProtectionDomain protectionDomain = getClass().getProtectionDomain();
			CodeSource codeSource = protectionDomain.getCodeSource();
			URI location;
			location = (codeSource == null ? null : codeSource.getLocation().toURI());
			String path = (location == null ? null : location.getSchemeSpecificPart());
			if (path == null) {
				throw new IllegalStateException(
						"Unable to determine code source archive");
			}
			File root = new File(path);
			if (!root.exists()) {
				throw new IllegalStateException(
						"Unable to determine code source archive from " + root);
			}
			return (root.isDirectory() ? new ExplodedArchive(root)
					: new JarFileArchive(root));
		}
		catch (Exception e) {
			throw new IllegalStateException("Cannt create local archive", e);
		}
	}

}

class InMemoryAppInstanceStatus implements AppInstanceStatus {

	private final String id;
	private final Wrapper wrapper;

	public InMemoryAppInstanceStatus(Wrapper wrapper) {
		this.id = UUID.randomUUID().toString();
		this.wrapper = wrapper;
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public DeploymentState getState() {
		return wrapper.getDeploymentState();
	}

	@Override
	public Map<String, String> getAttributes() {
		return Collections.emptyMap();
	}

}