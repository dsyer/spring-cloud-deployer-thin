package org.springframework.cloud.deployer.thin;

import java.util.Collections;

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

public class ThinJarTaskLauncher extends AbstractThinJarSupport implements TaskLauncher {

	public ThinJarTaskLauncher() {
		this("thin");
	}

	public ThinJarTaskLauncher(String name, String... profiles) {
		super(name, profiles);
	}

	@Override
	public String launch(AppDeploymentRequest request) {
		String id = super.deploy(request);
		ThinJarAppWrapper wrapper = super.getWrapper(id);
		wrapper.status(new TaskStatus(id, LaunchState.launching,
				request.getDeploymentProperties()));
		return id;
	}

	@Override
	public void cancel(String id) {
		super.cancel(id);
	}

	@Override
	public TaskStatus status(String id) {
		ThinJarAppWrapper wrapper = super.getWrapper(id);
		if (wrapper != null) {
			return new TaskStatus(id, wrapper.getState(), Collections.emptyMap());
		}
		return null;
	}

	@Override
	public void cleanup(String id) {
	}

	@Override
	public void destroy(String appName) {
		super.getWrapper(appName).cancel();
	}

}
