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

import static java.util.stream.Collectors.toList;
import static org.junit.platform.engine.Filter.composeFilters;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.platform.engine.Filter;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Represents the result of resolving a single discovery request for all
 * discovered {@link TestEngine TestEngines} and their
 * {@link TestDescriptor TestDescriptors}.
 *
 * @since 1.0
 */
class RootedDiscoveryResult {

	private final Map<TestEngine, TestDescriptor> testEngineDescriptors = new LinkedHashMap<>(4);
	private final RootedDiscoveryRequest request;
	private final Optional<TestIdentifier> suiteIdentifier;

	static Collection<TestDescriptor> collectRoots(List<RootedDiscoveryResult> results) {
		return results.stream().flatMap(result -> result.getRoots().stream()).collect(toList());
	}

	RootedDiscoveryResult(RootedDiscoveryRequest request) {
		this.request = request;
		this.suiteIdentifier = request.getSuiteDescriptor().map(TestIdentifier::from);
	}

	RootedDiscoveryRequest getRequest() {
		return request;
	}

	Optional<TestIdentifier> getSuiteIdentifier() {
		return suiteIdentifier;
	}

	/**
	 * Add an {@code engine}'s root {@link TestDescriptor}.
	 */
	void add(TestEngine engine, TestDescriptor testDescriptor) {
		request.getSuiteDescriptor().ifPresent(suiteDescriptor -> suiteDescriptor.addChild(testDescriptor));
		this.testEngineDescriptors.put(engine, testDescriptor);
	}

	Iterable<TestEngine> getTestEngines() {
		return this.testEngineDescriptors.keySet();
	}

	Collection<TestDescriptor> getRoots() {
		// @formatter:off
		return request.getSuiteDescriptor()
				.map(o -> (Collection<TestDescriptor>) Collections.singleton(o))
				.orElseGet(this.testEngineDescriptors::values);
		// @formatter:on
	}

	TestDescriptor getTestDescriptorFor(TestEngine testEngine) {
		return this.testEngineDescriptors.get(testEngine);
	}

	void applyPostDiscoveryFilters() {
		Filter<TestDescriptor> postDiscoveryFilter = composeFilters(
			request.getDiscoveryRequest().getPostDiscoveryFilters());
		TestDescriptor.Visitor removeExcludedTestDescriptors = descriptor -> {
			if (!descriptor.isRoot() && isExcluded(descriptor, postDiscoveryFilter)) {
				descriptor.removeFromHierarchy();
			}
		};
		acceptInAllTestEngines(removeExcludedTestDescriptors);
	}

	/**
	 * Prune all branches in the tree of {@link TestDescriptor TestDescriptors}
	 * that do not have executable tests.
	 *
	 * <p>If a {@link TestEngine} ends up with no {@code TestDescriptors} after
	 * pruning, it will <strong>not</strong> be removed.
	 */
	void prune() {
		acceptInAllTestEngines(TestDescriptor::prune);
	}

	private boolean isExcluded(TestDescriptor descriptor, Filter<TestDescriptor> postDiscoveryFilter) {
		return descriptor.getChildren().isEmpty() && postDiscoveryFilter.apply(descriptor).excluded();
	}

	private void acceptInAllTestEngines(TestDescriptor.Visitor visitor) {
		this.testEngineDescriptors.values().forEach(descriptor -> descriptor.accept(visitor));
	}
}
