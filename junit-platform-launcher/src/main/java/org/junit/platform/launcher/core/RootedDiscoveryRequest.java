/*
 * Copyright 2015-2018 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.platform.launcher.core;

import java.util.Optional;

import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.launcher.LauncherDiscoveryRequest;

class RootedDiscoveryRequest {

	private final LauncherDiscoveryRequest discoveryRequest;
	private final TestDescriptor suiteDescriptor;

	RootedDiscoveryRequest(LauncherDiscoveryRequest discoveryRequest) {
		this(discoveryRequest, null);
	}

	RootedDiscoveryRequest(LauncherDiscoveryRequest discoveryRequest, TestDescriptor suiteDescriptor) {
		this.discoveryRequest = discoveryRequest;
		this.suiteDescriptor = suiteDescriptor;
	}

	public LauncherDiscoveryRequest getDiscoveryRequest() {
		return discoveryRequest;
	}

	public Optional<TestDescriptor> getSuiteDescriptor() {
		return Optional.ofNullable(suiteDescriptor);
	}
}
