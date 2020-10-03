/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.dependencies;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.stitch.util.StitchUtil;

class DependencyGraph {
	private static class DependencyNode {
		public final Class<? extends DependencyProvider> type;
		private DependencyProvider provider;
		private final Set<DependencyNode> dependencies = StitchUtil.newIdentityHashSet();
		private final Set<DependencyNode> dependents = StitchUtil.newIdentityHashSet();

		static DependencyNode emptyOf(Class<? extends DependencyProvider> type) {
			return new DependencyNode(type, null);
		}

		static DependencyNode of(DependencyProvider provider) {
			return new DependencyNode(provider.getClass(), provider);
		}

		private <T extends DependencyProvider> DependencyNode(Class<? extends T> type, T provider) {
			this.type = type;
			this.provider = provider;
		}

		public boolean isEmpty() {
			return provider == null;
		}

		void fill(DependencyProvider provider) {
			if (!isEmpty()) throw new IllegalStateException("Duplicate providers given for " + type);

			this.provider = provider;
		}

		public DependencyProvider getProvider() {
			if (isEmpty()) throw new IllegalStateException("Cannot get provider of empty node");

			return provider;
		}

		boolean addDependency(DependencyNode node) {
			if (node.type == type) throw new IllegalArgumentException("Provider cannot depend on itself");

			return dependencies.add(node);
		}

		Set<DependencyNode> getDependencies() {
			return Collections.unmodifiableSet(dependencies);
		}

		boolean addDependent(DependencyNode node) {
			if (node.type == type) throw new IllegalArgumentException("Provider cannot depend on itself");

			return dependents.add(node);
		}

		Set<DependencyNode> getDependents() {
			return Collections.unmodifiableSet(dependents);
		}

		Set<DependencyNode> flagComplete() {
			return dependents.stream().filter(dependent -> dependent.dependencyComplete(this)).collect(Collectors.toSet());
		}

		private boolean dependencyComplete(DependencyNode node) {
			dependencies.remove(node);
			return dependencies.isEmpty();
		}
	}

	private final Queue<DependencyNode> currentActive = new ArrayDeque<>();
	private final Set<DependencyNode> awaiting = StitchUtil.newIdentityHashSet();

	public DependencyGraph(List<DependencyProvider> dependencies) {
		Set<Class<? extends DependencyProvider>> seenDependencies = StitchUtil.newIdentityHashSet();
		Map<Class<? extends DependencyProvider>, DependencyNode> dependenciesGraph = new IdentityHashMap<>();
		List<DependencyNode> roots = new ArrayList<>();

		for (DependencyProvider dependency : dependencies) {
			Class<? extends DependencyProvider> type = dependency.getClass();
			if (!seenDependencies.add(type)) {
				throw new IllegalArgumentException("Duplicate dependency types of " + type);
			}

			Set<Class<? extends DependencyProvider>> before = dependency.getDependencies();
			Set<Class<? extends DependencyProvider>> after = dependency.getDependents();

			if (before.isEmpty() && after.isEmpty()) {
				DependencyNode existing = dependenciesGraph.get(type);

				if (existing != null) {
					existing.fill(dependency);
				} else {
					dependenciesGraph.put(type, existing = DependencyNode.of(dependency));
					roots.add(existing);
				}
			} else {
				DependencyNode existing = dependenciesGraph.get(type);

				if (existing != null) {
					//If we have dependencies of our own we aren't a root
					if (!before.isEmpty()) roots.remove(existing);

					existing.fill(dependency);
				} else {
					dependenciesGraph.put(type, existing = DependencyNode.of(dependency));

					//If we don't have dependencies of our own we are a root
					if (before.isEmpty()) roots.add(existing);
				}

				assert existing != null;
				for (Class<? extends DependencyProvider> beforeType : before) {
					DependencyNode existingBefore = dependenciesGraph.get(beforeType);

					if (existingBefore == null) {
						dependenciesGraph.put(beforeType, existingBefore = DependencyNode.emptyOf(beforeType));
						roots.add(existingBefore); //Without any other information otherwise, the dependency is a root
					}

					existingBefore.addDependent(existing);
					existing.addDependency(existingBefore);
				}

				for (Class<? extends DependencyProvider> afterType : after) {
					DependencyNode existingAfter = dependenciesGraph.get(afterType);

					if (existingAfter == null) {
						dependenciesGraph.put(afterType, existingAfter = DependencyNode.emptyOf(afterType));
					} else {
						roots.remove(existingAfter);
					}

					existing.addDependent(existingAfter);
					existingAfter.addDependency(existing);
				}
			}
		}

		for (DependencyNode node : dependenciesGraph.values()) {
			if (node.isEmpty()) {
				throw new IllegalStateException("Missing dependency type: " + node.type);
			}

			Set<DependencyNode> checkedDependents = StitchUtil.newIdentityHashSet();
			Queue<DependencyNode> depenentList = new ArrayDeque<>(node.getDependents());

			DependencyNode dependent;
			while ((dependent = depenentList.poll()) != null) {
				Set<DependencyNode> depenents = dependent.getDependents();

				if (depenents.contains(node)) {
					throw new IllegalStateException("Circular dependencies on " + node.getClass());
				}

				for (DependencyNode child : depenents) {
					if (!checkedDependents.contains(child)) {
						checkedDependents.add(child);
						depenentList.add(child);
					}
				}
			}
		}

		if (roots.isEmpty()) {
			throw new IllegalStateException("All dependencies have dependencies!");
		}
		currentActive.addAll(roots);
	}

	/**
	 * Pops the next available {@link DependencyProvider} out of the queue to be processed
	 *
	 * @throws NoSuchElementException If there are no providers available (ie {@link #hasAvailable()} returns <code>false</code>)
	 */
	public synchronized DependencyProvider nextAvailable() {
		if (!hasAvailable()) {
			throw new NoSuchElementException("No more providers");
		}

		assert currentActive.stream().allMatch(node -> node.getDependencies().isEmpty());
		DependencyNode node = currentActive.poll();
		awaiting.add(node);
		return node.getProvider();
	}

	/**
	 * Drain all available {@link DependencyProvider}s, designed for threading the processing each
	 *
	 * @throws NoSuchElementException If there are no providers available (ie {@link #hasAvailable()} returns <code>false</code>)
	 */
	public synchronized List<DependencyProvider> allAvailable() {
		if (!hasAvailable()) {
			throw new NoSuchElementException("No more providers");
		}

		assert currentActive.stream().allMatch(node -> node.getDependencies().isEmpty());
		List<DependencyProvider> out = currentActive.stream().map(DependencyNode::getProvider).collect(Collectors.toList());
		awaiting.addAll(currentActive);
		currentActive.clear();
		return out;
	}

	/** Flags the given {@link DependencyProvider} as complete for the purposes of allowing dependent providers to run */
	public synchronized void markComplete(DependencyProvider provider) {
		for (Iterator<DependencyNode> it = awaiting.iterator(); it.hasNext();) {
			DependencyNode node = it.next();

			if (node.getProvider() == provider) {
				it.remove();
				currentActive.addAll(node.flagComplete());

				if (currentActive.isEmpty() && !node.getDependents().stream().map(DependencyNode::getDependencies).allMatch(awaiting::containsAll)) {
					throw new IllegalStateException("All remaining dependencies have dependencies!");
				}

				if (!currentActive.isEmpty() || awaiting.isEmpty()) notifyAll();
				break;
			}
		}
	}

	/** Whether there are any more {@link DependencyProvider} currently capable of being processed */
	public synchronized boolean hasAvailable() {
		return !currentActive.isEmpty();
	}

	/** Whether there are any {@link DependencyProvider}s waiting to be {@link #markComplete(DependencyProvider) marked complete} */
	public synchronized boolean hasActive() {
		return !awaiting.isEmpty();
	}

	/** {@link Iterable} form of the graph designed to loop over all the {@link DependencyProvider}s in it */
	public Iterable<DependencyProvider> asIterable() {
		return () -> new Iterator<DependencyProvider>() {
			@Override
			public boolean hasNext() {
				return hasAvailable();
			}

			@Override
			public DependencyProvider next() {
				return nextAvailable();
			}
		};
	}

	/**
	 * Waits until either {@link #hasAvailable()} is true or there are no remaining {@link DependencyProvider}s to process
	 *
	 * @throws InterruptedException If the current thread is interrupted before work is available
	 */
	public synchronized boolean waitForWork() throws InterruptedException {
		if (currentActive.isEmpty() && !awaiting.isEmpty()) wait();

		return !currentActive.isEmpty();
	}
}