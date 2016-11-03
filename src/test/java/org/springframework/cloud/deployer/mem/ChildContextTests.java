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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.springframework.cloud.deployer.mem.InMemoryAppDeployer;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Dave Syer
 *
 */
@RunWith(Parameterized.class)
public class ChildContextTests {

	private static InMemoryAppDeployer deployer = new InMemoryAppDeployer();

	@Parameterized.Parameters
	public static List<Object[]> data() {
		return Arrays.asList(new Object[10][0]);
	}

	@Test
	public void appFromJarFile() throws Exception {
		String deployed = deploy("app-with-db-in-lib-properties.jar", "--server.port=0");
		// Deployment is blocking so it either failed or succeeded.
		assertThat(deployer.status(deployed).getState())
				.isEqualTo(DeploymentState.deployed);
		deployer.undeploy(deployed);
	}

	@Test
	public void twoApps() throws Exception {
		String first = deploy("app-with-db-in-lib-properties.jar", "--server.port=0");
		String second = deploy("app-with-cloud-in-lib-properties.jar", "--server.port=0");
		// Deployment is blocking so it either failed or succeeded.
		assertThat(deployer.status(first).getState()).isEqualTo(DeploymentState.deployed);
		assertThat(deployer.status(second).getState())
				.isEqualTo(DeploymentState.deployed);
		deployer.undeploy(first);
		deployer.undeploy(second);
	}

	@Test
	public void appFromJarFileFails() throws Exception {
		String deployed = deploy("app-with-cloud-in-lib-properties.jar", "--fail");
		assertThat(deployer.status(deployed).getState())
				.isEqualTo(DeploymentState.failed);
		deployer.undeploy(deployed);
	}

	private String deploy(String jarName, String... args) {
		Resource resource = new FileSystemResource("src/test/resources/" + jarName);
		AppDefinition definition = new AppDefinition("child", Collections.emptyMap());
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.emptyMap(), Arrays.asList(args));
		String deployed = deployer.deploy(request);
		return deployed;
	}

}
