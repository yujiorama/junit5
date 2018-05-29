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
import static org.junit.platform.engine.TestExecutionResult.successful;
import static org.junit.platform.launcher.core.RootedDiscoveryResult.collectRoots;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;
import org.junit.platform.commons.util.BlacklistedExceptions;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Default implementation of the {@link Launcher} API.
 *
 * <p>External clients can obtain an instance by invoking {@link LauncherFactory#create()}.
 *
 * @since 1.0
 * @see Launcher
 * @see LauncherFactory
 */
class DefaultLauncher implements Launcher {

	private static final Logger logger = LoggerFactory.getLogger(DefaultLauncher.class);

	private final TestExecutionListenerRegistry listenerRegistry = new TestExecutionListenerRegistry();
	private final Iterable<TestEngine> testEngines;

	/**
	 * Construct a new {@code DefaultLauncher} with the supplied test engines.
	 *
	 * @param testEngines the test engines to delegate to; never {@code null} or empty
	 */
	DefaultLauncher(Iterable<TestEngine> testEngines) {
		Preconditions.condition(testEngines != null && testEngines.iterator().hasNext(),
			() -> "Cannot create Launcher without at least one TestEngine; "
					+ "consider adding an engine implementation JAR to the classpath");
		this.testEngines = validateUniqueIds(testEngines);
	}

	private static Iterable<TestEngine> validateUniqueIds(Iterable<TestEngine> testEngines) {
		Set<String> ids = new HashSet<>();
		for (TestEngine testEngine : testEngines) {
			if (!ids.add(testEngine.getId())) {
				throw new JUnitException(String.format(
					"Cannot create Launcher for multiple engines with the same ID '%s'.", testEngine.getId()));
			}
		}
		return testEngines;
	}

	@Override
	public void registerTestExecutionListeners(TestExecutionListener... listeners) {
		Preconditions.notEmpty(listeners, "listeners array must not be null or empty");
		Preconditions.containsNoNullElements(listeners, "individual listeners must not be null");
		this.listenerRegistry.registerListeners(listeners);
	}

	@Override
	public TestPlan discover(LauncherDiscoveryRequest discoveryRequest) {
		Preconditions.notNull(discoveryRequest, "LauncherDiscoveryRequest must not be null");
		return TestPlan.from(collectRoots(discover(discoveryRequest, "discovery")));
	}

	@Override
	public void execute(LauncherDiscoveryRequest discoveryRequest, TestExecutionListener... listeners) {
		Preconditions.notNull(discoveryRequest, "LauncherDiscoveryRequest must not be null");
		Preconditions.notNull(listeners, "TestExecutionListener array must not be null");
		Preconditions.containsNoNullElements(listeners, "individual listeners must not be null");
		execute(discover(discoveryRequest, "execution"), discoveryRequest.getConfigurationParameters(), listeners);
	}

	TestExecutionListenerRegistry getTestExecutionListenerRegistry() {
		return listenerRegistry;
	}

	private List<RootedDiscoveryResult> discover(LauncherDiscoveryRequest discoveryRequest, String phase) {
		// @formatter:off
		return collectRequests(discoveryRequest).stream()
				.map(request -> resolveRequest(phase, request))
				.collect(toList());
		// @formatter:on
		// TODO prune more aggressively when a suite is present
		// TODO -> also remove empty engine descriptors from non-suite request
	}

	private List<RootedDiscoveryRequest> collectRequests(LauncherDiscoveryRequest discoveryRequest) {
		List<RootedDiscoveryRequest> requests = new ArrayList<>();
		requests.add(new RootedDiscoveryRequest(discoveryRequest));
		requests.addAll(new SuiteDiscoverer().resolve(discoveryRequest));
		return requests;
	}

	private RootedDiscoveryResult resolveRequest(String phase, RootedDiscoveryRequest request) {
		RootedDiscoveryResult result = new RootedDiscoveryResult(request);
		for (TestEngine testEngine : this.testEngines) {
			// @formatter:off
			boolean engineIsExcluded = request.getDiscoveryRequest().getEngineFilters().stream()
					.map(engineFilter -> engineFilter.apply(testEngine))
					.anyMatch(FilterResult::excluded);
			// @formatter:on

			if (engineIsExcluded) {
				logger.debug(() -> String.format(
					"Test discovery for engine '%s' was skipped due to an EngineFilter in phase '%s'.",
					testEngine.getId(), phase));
				continue;
			}

			logger.debug(() -> String.format("Discovering tests during Launcher %s phase in engine '%s'.", phase,
				testEngine.getId()));

			Optional<TestDescriptor> engineRoot = discoverEngineRoot(testEngine, request);
			engineRoot.ifPresent(rootDescriptor -> result.add(testEngine, rootDescriptor));
		}
		result.applyPostDiscoveryFilters();
		result.prune();
		return result;
	}

	private Optional<TestDescriptor> discoverEngineRoot(TestEngine testEngine, RootedDiscoveryRequest request) {

		UniqueId uniqueEngineId = request.getSuiteDescriptor().map(TestDescriptor::getUniqueId).map(
			id -> id.append(UniqueId.ENGINE_SEGMENT_TYPE, testEngine.getId())).orElseGet(
				() -> UniqueId.forEngine(testEngine.getId()));
		try {
			TestDescriptor engineRoot = testEngine.discover(request.getDiscoveryRequest(), uniqueEngineId);
			Preconditions.notNull(engineRoot,
				() -> String.format(
					"The discover() method for TestEngine with ID '%s' must return a non-null root TestDescriptor.",
					testEngine.getId()));
			return Optional.of(engineRoot);
		}
		catch (Throwable throwable) {
			handleThrowable(testEngine, "discover", throwable);
			return Optional.empty();
		}
	}

	private void execute(List<RootedDiscoveryResult> results, ConfigurationParameters configurationParameters,
			TestExecutionListener... listeners) {

		TestExecutionListenerRegistry listenerRegistry = buildListenerRegistryForExecution(listeners);
		TestPlan testPlan = TestPlan.from(collectRoots(results));
		TestExecutionListener testExecutionListener = listenerRegistry.getCompositeTestExecutionListener();
		testExecutionListener.testPlanExecutionStarted(testPlan);
		ExecutionListenerAdapter engineExecutionListener = new ExecutionListenerAdapter(testPlan,
			testExecutionListener);
		for (RootedDiscoveryResult result : results) {
			result.getSuiteIdentifier().ifPresent(testExecutionListener::executionStarted);
			for (TestEngine testEngine : result.getTestEngines()) {
				TestDescriptor testDescriptor = result.getTestDescriptorFor(testEngine);
				execute(testEngine,
					new ExecutionRequest(testDescriptor, engineExecutionListener, configurationParameters));
			}
			result.getSuiteIdentifier().ifPresent(
				suiteIdentifier -> testExecutionListener.executionFinished(suiteIdentifier, successful()));
		}
		testExecutionListener.testPlanExecutionFinished(testPlan);
	}

	private TestExecutionListenerRegistry buildListenerRegistryForExecution(TestExecutionListener... listeners) {
		if (listeners.length == 0) {
			return this.listenerRegistry;
		}
		TestExecutionListenerRegistry registry = new TestExecutionListenerRegistry(this.listenerRegistry);
		registry.registerListeners(listeners);
		return registry;
	}

	private void execute(TestEngine testEngine, ExecutionRequest executionRequest) {
		try {
			testEngine.execute(executionRequest);
		}
		catch (Throwable throwable) {
			handleThrowable(testEngine, "execute", throwable);
		}
	}

	private void handleThrowable(TestEngine testEngine, String phase, Throwable throwable) {
		logger.warn(throwable,
			() -> String.format("TestEngine with ID '%s' failed to %s tests", testEngine.getId(), phase));
		BlacklistedExceptions.rethrowIfBlacklisted(throwable);
	}

}
