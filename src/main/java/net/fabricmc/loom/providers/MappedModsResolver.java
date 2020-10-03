/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.ArtifactInfo;
import net.fabricmc.loom.dependencies.DependencyProvider;
import net.fabricmc.loom.dependencies.LogicalDependencyProvider;
import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.stitch.util.Pair;

public class MappedModsResolver extends LogicalDependencyProvider {
	private final Queue<Pair<String, ArtifactInfo>> mods = new ConcurrentLinkedQueue<>();
	private final MappedModsProvider provider = new MappedModsProvider();

	@Override
	public Set<Class<? extends DependencyProvider>> getDependencies() {
		return ImmutableSet.<Class<? extends DependencyProvider>>builder().add(MappingsProvider.class).addAll(MappedModsCollectors.all()).build();
	}

	@Override
	public void register(LoomDependencyManager dependencyManager) {
		super.register(dependencyManager);

		dependencyManager.addProvider(provider);
	}

	void queueMod(String config, ArtifactInfo artifact) {
		mods.add(Pair.of(config, artifact));
	}

	@Override
	public void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MappingsProvider mappingsProvider = getProvider(MappingsProvider.class);

		String mappingsSuffix = mappingsProvider.mappingsName + '.' + mappingsProvider.minecraftVersion.replace(' ', '_').replace('.', '_').replace('-', '_') + '.' + mappingsProvider.mappingsVersion;
		File modCache = extension.getRemappedModCache();

		Logger logger = project.getLogger();
		logger.info("Collected {} mods to remap to {}", mods.size(), modCache);

		for (Pair<String, ArtifactInfo> mod : mods) {
			ArtifactInfo artifact = mod.getRight();
			String group = artifact.group;
			String name = artifact.name;
			String version = artifact.version;
			String classifier = artifact.classifier;

			logger.lifecycle(":providing {}:{}:{}{} ({})", group, name, version, classifier, mappingsSuffix);

			File input = artifact.getFile();
			File output = new File(modCache, String.format("%s-%s@%s%s.jar", name, version, mappingsSuffix, classifier.replace(':', '-')));

			remapIfNecessary(logger, input, output, artifact.getSources().isPresent());
			if (extension.extractJars) handleNestedJars(project, extension, input.getPath(), input, mod.getLeft());

			artifact.getSources().ifPresent(sources -> {
				File remappedSources = new File(modCache, String.format("%s-%s@%s%s-sources.jar", name, version, mappingsSuffix, classifier.replace(':', '-')));

				if (!remappedSources.exists() || sources.lastModified() <= 0 || sources.lastModified() > remappedSources.lastModified()) {
					logger.info("Queuing remapping of {} to {}", sources.getName(), remappedSources.getName());

					provider.queueRemap(sources, remappedSources);
				} else {
					logger.info("{} is up to date with {}", remappedSources.getName(), sources.getName());
				}
			});

			addDependency(String.format("%s:%s:%s@%s%s", group, name, version, mappingsSuffix, classifier), project, mod.getLeft());
		}
	}

	private void remapIfNecessary(Logger logger, File input, File output, boolean hasSource) {
		if (!output.exists() || input.lastModified() <= 0 || input.lastModified() > output.lastModified()) {
			//If the output doesn't exist, or appears to be outdated compared to the input we'll remap it
			logger.info("Queuing remapping of {} to {}", input.getName(), output.getName());

			provider.queueRemap(input, output, hasSource);
		} else {
			//Existing output seems fine, add it purely for classpath purposes remapping other mods
			logger.info("{} is up to date with {}", output.getName(), input.getName());

			provider.noteClasspath(input);
		}
	}

	private void handleNestedJars(Project project, LoomGradleExtension extension, String origin, File input, String config) throws IOException {
		try (JarFile zip = new JarFile(input)) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) throw new IllegalStateException("Mod collector missed a non-Fabric mod: " + origin);

			JsonElement json = new JsonParser().parse(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8));
			if (json == null) return; //Apparently the mod has an empty json?

			if (!json.isJsonArray() && !json.isJsonObject()) {//The mod JSON should be one of these two options
				throw new AssertionError("Expected mod json in " + origin + " to be an array or object but was " + json);
			}

			for (JsonElement mod : json.isJsonArray() ? json.getAsJsonArray() : Collections.singleton(json.getAsJsonObject())) {
				if (mod == null || !mod.isJsonObject()) {//A mod itself should be expressed as an object
					throw new AssertionError("Expected mod json in " + origin + " to be an object but was " + mod);
				}

				if (!mod.getAsJsonObject().has("jars")) continue; //No nested mods
				JsonArray nests = mod.getAsJsonObject().getAsJsonArray("jars");

				for (JsonElement nest : nests) {
					String fileName = nest.getAsJsonObject().getAsJsonPrimitive("file").getAsString();
					project.getLogger().lifecycle("Found {} nested in {}", fileName, FilenameUtils.getName(origin));
					processNestedJar(project, extension, origin, zip, fileName, config);
				}
			}
		}
	}

	private void processNestedJar(Project project, LoomGradleExtension extension, String origin, JarFile parent, String jarName, String config) throws IOException {
		JarEntry entry = parent.getJarEntry(jarName); //There's an expectation this is not missing
		if (entry == null) throw new RuntimeException("Unable to find declared nested jar " + jarName + " in " + parent.getName() + ", extracted from " + origin);

		File nestedFile = new File(extension.getNestedModCache(), jarName = FilenameUtils.getName(jarName));
		FileUtils.copyInputStreamToFile(parent.getInputStream(entry), nestedFile);

		File remappedFile = new File(extension.getRemappedModCache(), jarName);
		//There is an expectation the outer jar doesn't contain the nested jar's sources
		//There is nothing stopping them from doing so, however this is not configured by default (so no one will)
		remapIfNecessary(project.getLogger(), nestedFile, remappedFile, false);

		//Recurse into the newly extracted mod to see if it has anything nested inside
		handleNestedJars(project, extension, origin, nestedFile, config);

		addDependency(remappedFile, project, config);
	}
}