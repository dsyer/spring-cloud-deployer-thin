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

import java.sql.SQLException;
import java.util.Map;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Utility class for starting a Spring Boot application in a separate thread. Best used
 * from an isolated class loader.
 * 
 * @author Dave Syer
 *
 */
public class ContextRunner {

	private ConfigurableApplicationContext context;
	private Thread runThread;
	private boolean running = false;
	private Throwable error;

	public void run(String source, Map<String, Object> properties, String... args) {
		// Run in new thread to ensure that the context classloader is setup
		this.runThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					context = new SpringApplicationBuilder(source).properties(properties)
							.run(args);
				}
				catch (Throwable ex) {
					error = ex;
				}

			}
		});
		this.runThread.start();
		try {
			this.runThread.join();
			this.running = context != null && context.isRunning();
		}
		catch (InterruptedException e) {
			this.running = false;
			Thread.currentThread().interrupt();
		}

	}

	public void close() {
		if (this.context != null) {
			this.context.close();
		}
		try {
			new JdbcLeakPrevention().clearJdbcDriverRegistrations();
		}
		catch (SQLException e) {
			// TODO: log something
		}
		this.running = false;
		this.runThread = null;
	}

	public boolean isRunning() {
		return running;
	}

	public Throwable getError() {
		return this.error;
	}

}