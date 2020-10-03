/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.dependencies;

import java.util.function.Consumer;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import net.fabricmc.loom.LoomGradleExtension;

/**
 * A {@link DependencyProvider} which handles a resolved {@link Configuration}
 *
 * @author Chocohead
 *
 * @see PhysicalDependencyProvider
 */
public abstract class ArtifactDependencyProvider extends DependencyProvider {
	/** The name of the {@link Configuration} (as given by {@link Configuration#getName()}) that this handles */
	public abstract String getTargetConfig();

	/** Whether the target {@link Configuration} must have at least one dependency in */
	public abstract boolean isRequired();

	@Override
	protected final String getType() {
		return "artifact";
	}

	@Override
	public final void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		Configuration configuration = project.getConfigurations().getByName(getTargetConfig());

		if (isRequired() && configuration.getDependencies().isEmpty()) {
			throw new InvalidUserDataException("Missing dependency for " + configuration.getName() + " configuration");
		}

		for (ArtifactInfo artifact : ArtifactInfo.resolve(configuration, project.getDependencies())) {
			try {
				provide(artifact, project, extension, postPopulationScheduler);
			} catch (Throwable t) {
				throw new GradleException(String.format("%s failed to provide %s for %s", getClass(), artifact.notation(), getTargetConfig()), t);
			}
		}
	}

	/** Perform whatever action this needs for the given {@link ArtifactInfo} from the target {@link Configuration} */
	protected abstract void provide(ArtifactInfo artifact, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception;
}