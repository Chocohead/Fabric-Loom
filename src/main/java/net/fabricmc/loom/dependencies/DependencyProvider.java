/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.dependencies;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;

public abstract class DependencyProvider {
	private LoomDependencyManager dependencyManager;

	DependencyProvider() {
	}

	public void register(LoomDependencyManager dependencyManager) {
		this.dependencyManager = dependencyManager;
	}

	protected LoomDependencyManager getDependencyManager() {
		if (dependencyManager == null) throw new IllegalStateException("Unregistered dependency provider!");
		return dependencyManager;
	}

	/** The collection of {@link DependencyProvider} types this depends on */
	public Set<Class<? extends DependencyProvider>> getDependencies() {
		return Collections.emptySet();
	}

	/** The collection of {@link DependencyProvider} types this should run before */
	public Set<Class<? extends DependencyProvider>> getDependents() {
		return Collections.emptySet();
	}

	protected abstract String getType();

	/** Perform whatever action this needs once the dependencies have run and are all satisfied */
	public abstract void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception;

	protected <T extends DependencyProvider> T getProvider(Class<T> type) {
		T provider = getDependencyManager().getProvider(type);
		if (provider == null) throw new IllegalArgumentException("Could not find " + type + " instance!");
		return provider;
	}

	protected void addDependency(String module, Project project, String target) {
		addDependency(project.getDependencies().module(module), project, target);
	}

	protected void addDependency(File file, Project project, String target) {
		addDependency(project.files(file), project, target);
	}

	private void addDependency(Object object, Project project, String target) {
		project.getDependencies().add(target, object);
	}
}
