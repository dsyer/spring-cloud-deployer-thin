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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.springframework.boot.loader.thin.ArchiveUtils;
import org.springframework.boot.loader.tools.LogbackInitializer;
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
public class FunctionAppDeployerTests {

	static {
		LogbackInitializer.initialize();
	}

	private static ThinJarAppDeployer deployer = new ThinJarAppDeployer();

	@BeforeClass
	public static void skip() {
		try {
			ArchiveUtils.getArchiveRoot(ArchiveUtils.getArchive(
					"maven://org.springframework.cloud:spring-cloud-function-web:1.0.0.BUILD-SNAPSHOT"));
		}
		catch (Exception e) {
			Assume.assumeNoException(
					"Could not locate jar for tests. Please build spring-cloud-function locally first.",
					e);
		}
	}

	@Parameterized.Parameters
	public static List<Object[]> data() {
		// Repeat a couple of times to ensure it's consistent
		return Arrays.asList(new Object[2][0]);
	}

	@Test
	public void web() throws Exception {
		String first = deploy(
				"maven://org.springframework.cloud:spring-cloud-function-web:1.0.0.BUILD-SNAPSHOT",
				"--web.path=/words", "--function.name=uppercase");
		// Deployment is blocking so it either failed or succeeded.
		assertThat(deployer.status(first).getState()).isEqualTo(DeploymentState.deployed);
		deployer.undeploy(first);
	}

	@Test
	public void stream() throws Exception {
		String first = deploy(
				"maven://org.springframework.cloud:spring-cloud-function-stream:1.0.0.BUILD-SNAPSHOT",
				"--spring.cloud.stream.bindings.input.destination=words",
				"--spring.cloud.stream.bindings.output.destination=uppercaseWords",
				"--function.name=uppercase");
		// Deployment is blocking so it either failed or succeeded.
		assertThat(deployer.status(first).getState()).isEqualTo(DeploymentState.deployed);
		deployer.undeploy(first);
	}

	private String deploy(String jarName, String... args) throws Exception {
		Resource resource = new FileSystemResource(
				ArchiveUtils.getArchiveRoot(ArchiveUtils.getArchive(jarName)));
		AppDefinition definition = new AppDefinition(resource.getFilename(),
				Collections.emptyMap());
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.emptyMap(), Arrays.asList(args));
		String deployed = deployer.deploy(request);
		return deployed;
	}

}
