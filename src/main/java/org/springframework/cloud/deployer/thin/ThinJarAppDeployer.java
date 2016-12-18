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

package org.springframework.cloud.deployer.thin;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.util.SocketUtils;

/**
 * An {@link AppDeployer} that launches thin jars as apps in the same JVM, using a
 * separate class loader. Computes the class path from the jar being deployed in the same
 * way as it would if you ran the jar in its own process. Makes an assumption that the
 * "main" class in the archive is a Spring application context (e.g. typically a
 * <code>@SpringBootApplication</code>) so that there is a way to close the context when
 * the app is undeployed (a generic main method does not have that feature).
 * 
 * @author Dave Syer
 *
 */
public class ThinJarAppDeployer extends AbstractThinJarSupport implements AppDeployer {

	private static final String SERVER_PORT_KEY = "server.port";

	private static final int DEFAULT_SERVER_PORT = 8080;

	public ThinJarAppDeployer() {
		this("thin");
	}

	public ThinJarAppDeployer(String name, String... profiles) {
		super(name, profiles);
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		String id = super.deploy(request);
		ThinJarAppWrapper wrapper = super.getWrapper(id);
		wrapper.status(
				AppStatus.of(id).with(new InMemoryAppInstanceStatus(wrapper)).build());
		return id;
	}

	@Override
	protected Map<String, String> getProperties(AppDeploymentRequest request) {
		Map<String, String> properties = super.getProperties(request);
		boolean useDynamicPort = !properties.containsKey(SERVER_PORT_KEY);
		int port = useDynamicPort ? SocketUtils.findAvailableTcpPort(DEFAULT_SERVER_PORT)
				: Integer.parseInt(
						request.getDefinition().getProperties().get(SERVER_PORT_KEY));
		if (useDynamicPort) {
			properties.put(SERVER_PORT_KEY, String.valueOf(port));
		}
		return properties;
	}

	@Override
	public AppStatus status(String id) {
		return (AppStatus) super.getWrapper(id).status();
	}

	@Override
	public void undeploy(String id) {
		super.cancel(id);
	}

}

class InMemoryAppInstanceStatus implements AppInstanceStatus {

	private final String id;
	private final ThinJarAppWrapper wrapper;

	public InMemoryAppInstanceStatus(ThinJarAppWrapper wrapper) {
		this.id = UUID.randomUUID().toString();
		this.wrapper = wrapper;
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public DeploymentState getState() {
		LaunchState state = wrapper.getState();
		switch (state) {
		case running:
			return DeploymentState.deployed;
		case failed:
			return DeploymentState.failed;
		case cancelled:
			return DeploymentState.undeployed;
		default:
			return DeploymentState.unknown;
		}
	}

	@Override
	public Map<String, String> getAttributes() {
		return Collections.emptyMap();
	}

}