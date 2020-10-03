/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.util.function.Consumer;
import java.util.function.Function;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.ArtifactDependencyProvider;
import net.fabricmc.loom.dependencies.ArtifactInfo;
import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.dependencies.RemappedConfigurationEntry;

public abstract class MappedModsCollector extends ArtifactDependencyProvider {
	private final String originConfig, remappedConfig;
	private final Function<ConfigurationContainer, Configuration> naturalConfig;
	private MappedModsResolver resolver;

	public MappedModsCollector(RemappedConfigurationEntry configuration) {
		originConfig = configuration.getSourceConfiguration();
		naturalConfig = configuration::getTargetConfiguration;
		remappedConfig = configuration.getRemappedConfiguration();
	}

	public MappedModsCollector(String originConfig, String naturalConfig, String remappedConfig) {
		this.originConfig = originConfig;
		this.naturalConfig = configs -> configs.getByName(naturalConfig);
		this.remappedConfig = remappedConfig;
	}

	@Override
	public void register(LoomDependencyManager dependencyManager) {
		super.register(dependencyManager);

		if (!dependencyManager.hasProvider(MappedModsResolver.class)) {
			dependencyManager.addProvider(resolver = new MappedModsResolver());
		} else {
			resolver = dependencyManager.getProvider(MappedModsResolver.class);
		}
	}

	@Override
	public String getTargetConfig() {
		return originConfig;
	}

	@Override
	public boolean isRequired() {
		return false;
	}

	@Override
	protected void provide(ArtifactInfo artifact, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		if (!artifact.isFabricMod()) {
			project.getLogger().lifecycle(":providing {}", artifact.notation());
			naturalConfig.apply(project.getConfigurations()).getDependencies().add(artifact.asNonTransitiveDependency());
		} else {
			resolver.queueMod(remappedConfig, artifact);
		}
	}
}