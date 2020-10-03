/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Chocohead
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
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FilenameUtils;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.SelfResolvingDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.query.ArtifactResolutionQuery;
import org.gradle.api.artifacts.result.ArtifactResult;
import org.gradle.api.artifacts.result.ComponentArtifactsResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.jvm.JvmLibrary;
import org.gradle.language.base.artifact.SourcesArtifact;
import org.zeroturnaround.zip.ZipUtil;

import com.google.common.collect.Iterables;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ArtifactInfo {
	/**
	 * Process the artifacts for the given configuration into {@link ArtifactInfo}s
	 *
	 * @param configuration The configuration to resolve dependencies for
	 * @param depHandler The dependency handler for a project, typically from {@link Project#getDependencies()}
	 *
	 * @return The artifacts for the given configuration as ArtifactInfos
	 */
	public static Set<ArtifactInfo> resolve(Configuration configuration, DependencyHandler depHandler) {
		//We need to get all of the file based dependencies that aren't transitive, and shouldn't be applied over the top of each other
		Builder<File, SelfResolvingDependency> builder = new Builder<>();
		configuration.getIncoming().getDependencies().stream().filter(dependency -> dependency instanceof SelfResolvingDependency).map(SelfResolvingDependency.class::cast).forEach(dependency -> {
			for (File file : dependency.resolve()) {
				builder.put(file, dependency);
			}
		});
		Map<File, SelfResolvingDependency> fileDependencies = builder.build();

		//Need to filter out the file dependencies, then merge the disjoint sets of artifacts together
		return Stream.concat(configuration.getResolvedConfiguration().getResolvedArtifacts().stream().filter(artifact -> !fileDependencies.containsKey(artifact.getFile())).map(artifact -> {
			if (artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier) {
				//It's a normal module dependency, keep it as an artifact so the transitives can carry over
				return new ArtifactInfo((ModuleComponentIdentifier) artifact.getId().getComponentIdentifier(), artifact.getClassifier(), artifact.getFile(), depHandler);
			} else {
				//If it's not a file nor a module identifier goodness knows what it is
				throw new RuntimeException("Unable to handle " + artifact.getFile() + ", identified as " + artifact.getId().getComponentIdentifier());
			}
		}), fileDependencies.values().stream().distinct().map(dependency -> FileArtifactInfo.create(dependency, depHandler))).collect(Collectors.toSet());
	}


	public final String group, name, version, classifier;
	public final File artifact;
	protected final DependencyHandler depHandler;
	private ModuleComponentIdentifier identifier;

	public ArtifactInfo(ModuleComponentIdentifier identifier, String classifier, File artifact, DependencyHandler depHandler) {
		this(identifier.getGroup(), identifier.getModule(), identifier.getVersion(), classifier, artifact, depHandler);

		this.identifier = identifier;
	}

	protected ArtifactInfo(String group, String name, String version, String classifier, File artifact, DependencyHandler depHandler) {
		this.group = group;
		this.name = name;
		this.version = version;
		this.classifier = classifier == null ? "" : ':' + classifier;
		this.artifact = artifact;
		this.depHandler = depHandler;
	}

	public String notation() {
		return group + ':' + name + ':' + version + classifier;
	}

	public File getFile() {
		return artifact;
	}

	public static boolean isFabricMod(File file) {
		return "jar".equals(FilenameUtils.getExtension(file.getName())) && ZipUtil.containsEntry(file, "fabric.mod.json");
	}

	public boolean isFabricMod() {
		return isFabricMod(artifact);
	}

	public Dependency asNonTransitiveDependency() {
		Dependency dep = depHandler.module(notation());
		if (dep instanceof ModuleDependency) {
			((ModuleDependency) dep).setTransitive(false);
		}
		return dep;
	}

	public Optional<File> getSources() {
		@SuppressWarnings("unchecked") //Following what IdeDependencyResult#resolveAuxiliaryArtifacts does
		ArtifactResolutionQuery query = depHandler.createArtifactResolutionQuery().forComponents(identifier).withArtifacts(JvmLibrary.class, SourcesArtifact.class);

		for (ComponentArtifactsResult result : query.execute().getResolvedComponents()) {
			for (ArtifactResult srcArtifact : result.getArtifacts(SourcesArtifact.class)) {
				if (srcArtifact instanceof ResolvedArtifactResult) {
					return Optional.of(((ResolvedArtifactResult) srcArtifact).getFile());
				}
			}
		}

		return Optional.empty();
	}

	@Override
	public String toString() {
		return notation();
	}


	public static class FileArtifactInfo extends ArtifactInfo {
		public static ArtifactInfo create(SelfResolvingDependency dependency, DependencyHandler depHandler) {
			Builder<String, File> builder = new Builder<>();

			Set<File> files = dependency.resolve();
			switch (files.size()) {
			case 0: //Don't think Gradle would ever let you do this
				throw new IllegalStateException("Empty dependency?");

			case 1: //Single file dependency
				builder.put("", Iterables.getOnlyElement(files));
				break;

			default: //File collection, try work out the classifiers
				List<File> sortedFiles = files.stream().sorted(Comparator.comparing(File::getName, Comparator.comparingInt(String::length))).collect(Collectors.toList());

				//First element in sortedFiles is the one with the shortest name, we presume all the others are different classifier types of this
				File shortest = sortedFiles.remove(0);
				String shortestName = FilenameUtils.removeExtension(shortest.getName()); //name.jar -> name

				for (File file : sortedFiles) {
					if (!file.getName().startsWith(shortestName)) {
						//If there is another file which doesn't start with the same name as the presumed classifier-less one we're out of our depth
						throw new IllegalArgumentException("Unable to resolve classifiers for " + dependency + " (failed to sort " + files + ')');
					}
				}

				//We appear to be right, therefore this is the normal dependency file we want
				builder.put("", shortest);

				int start = shortestName.length();
				sortedFiles.stream().collect(Collectors.collectingAndThen(Collectors.<File, String, File>toMap(file -> {
					//Now we just have to work out what classifier type the other files are, this shouldn't even return an empty string
					String classifier = FilenameUtils.removeExtension(file.getName()).substring(start);

					//The classifier could well be separated with a dash (thing name.jar and name-sources.jar), we don't want that leading dash
					return classifier.charAt(0) == '-' ? classifier.substring(1) : classifier;
				}, Function.identity(), (keyA, keyB) -> {
					throw new InvalidUserDataException("Duplicate classifiers " + keyA.getName() + " and " + keyB.getName());
				}), builder::putAll));
			}

			Map<String, File> classifierToFile = builder.build();
			String name, version;
			boolean isFabricMod;

			File root = classifierToFile.get(""); //We've built the classifierToFile map, now to try find a name and version for our dependency
			if (isFabricMod = isFabricMod(root)) {
				//It's a Fabric mod, see how much we can extract out
				JsonObject json = new Gson().fromJson(new String(ZipUtil.unpackEntry(root, "fabric.mod.json"), StandardCharsets.UTF_8), JsonObject.class);
				if (json == null || !json.has("id") || !json.has("version")) throw new IllegalArgumentException("Invalid Fabric mod jar: " + root + " (malformed json: " + json + ')');

				if (json.has("name")) {//Go for the name field if it's got one
					name = json.get("name").getAsString();
				} else {
					name = json.get("id").getAsString();
				}
				version = json.get("version").getAsString();
			} else {
				//Not a Fabric mod, just have to make something up
				name = FilenameUtils.removeExtension(root.getName()).replace(" :", "-");
				version = "1.0";
			}

			return new FileArtifactInfo(dependency, name, version, classifierToFile, isFabricMod, depHandler);
		}

		protected final SelfResolvingDependency dependency;
		protected final Map<String, File> classifierToFile;
		protected final boolean isFabricMod;

		public FileArtifactInfo(SelfResolvingDependency dependency, String name, String version, Map<String, File> artifacts, boolean isFabricMod, DependencyHandler depHandler) {
			super("net.fabricmc.synthetic", name, version, null, artifacts.get(""), depHandler);

			this.dependency = dependency;
			classifierToFile = artifacts;
			this.isFabricMod = isFabricMod;
		}

		@Override
		public boolean isFabricMod() {
			return isFabricMod;
		}

		@Override
		public Dependency asNonTransitiveDependency() {
			return dependency;
		}

		@Override
		public Optional<File> getSources() {
			return Optional.ofNullable(classifierToFile.get("sources"));
		}
	}
}