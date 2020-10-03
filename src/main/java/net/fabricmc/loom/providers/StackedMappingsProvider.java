/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.apache.commons.io.FilenameUtils;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.dependencies.PhysicalDependencyProvider;
import net.fabricmc.loom.providers.StackedMappingsProvider.MappingFile.MappingType;
import net.fabricmc.loom.util.Constants;

public class StackedMappingsProvider extends PhysicalDependencyProvider {
	static final class MappingFile implements LazyMappings {
		enum MappingType {
			Tiny, TinyV1, TinyV2, TinyGz, Enigma;

			boolean needsEnlightening() {
				return this == Tiny;
			}
		}

		public final String name, version, minecraftVersion;
		public final File origin;
		public final MappingType type;
		private List<String> namespaces;

		public MappingFile(File origin, String name, String version, String minecraftVersion, MappingType type) {
			this(origin, name, version, minecraftVersion, type, null);
			assert type != MappingType.TinyV1 && type != MappingType.TinyV2;
		}

		MappingFile(File origin, String name, String version, String minecraftVersion, MappingType type, List<String> namespaces) {
			this.origin = origin;
			this.name = name;
			this.version = version;
			this.minecraftVersion = minecraftVersion;
			this.type = type;
			this.namespaces = namespaces;
		}

		private MappingFile withEnlightening(MappingType type, List<String> namespaces) {
			return new MappingFile(origin, name, version, minecraftVersion, type, namespaces);
		}

		public MappingFile enlighten() throws IOException {
			switch (type) {
			case Tiny:
				try (FileSystem fileSystem = FileSystems.newFileSystem(origin.toPath(), null);
						BufferedReader reader = Files.newBufferedReader(fileSystem.getPath("mappings/mappings.tiny"))) {
					String header = reader.readLine();

					if (header == null) {
						throw new EOFException("Empty mappings supplied in " + origin);
					} else if (header.startsWith("v1\t")) {
						return withEnlightening(MappingType.TinyV1, Arrays.asList(header.substring(3).split("\t")));
					} else if (header.startsWith("tiny\t2\t")) {
						String[] bits;
						return withEnlightening(MappingType.TinyV2, Arrays.asList(bits = header.split("\t")).subList(3, bits.length));
					} else {
						throw new IOException("Unable to guess mapping version from " + header + " in " + origin);
					}
				}

			case TinyV1:
			case TinyV2:
				assert namespaces != null;

			default:
				return this;
			}
		}

		public List<String> getNamespaces() {
			if (namespaces == null) {
				throw new IllegalArgumentException("Tried to get namespaces from unenlightened type " + type);
			}
			return namespaces;
		}

		@Override
		public ActiveMappings open() throws IOException {
			switch (type) {
			case Tiny:
			case TinyV1:
			case TinyV2:
				return new ActiveMappings() {
					private final FileSystem fs = FileSystems.newFileSystem(origin.toPath(), null);

					@Override
					public Path getMappings() {
						return fs.getPath("mappings/mappings.tiny");
					}

					@Override
					public void close() throws IOException {
						fs.close();
					}
				};

			case TinyGz:
				return new DirectMappings(origin.toPath());

			case Enigma:
				throw new UnsupportedOperationException("Cannot open Enigma mappings as a single mapping path");

			default:
				throw new AssertionError("Unexpected mapping type: " + type);
			}
		}
	}
	private final MappingsProvider realProvider = new MappingsProvider();

	@Override
	public void register(LoomDependencyManager dependencyManager) {
		super.register(dependencyManager);

		dependencyManager.addProvider(realProvider);
	}

	@Override
	public String getTargetConfig() {
		return Constants.MAPPINGS_RAW;
	}

	@Override
	public boolean isRequired() {
		return false; //Mappings can be inferred from the (required) Minecraft provider
	}

	@Override
	public boolean isUnique() {
		return false; //Multiple mappings can be defined then stacked together
	}

	@Override
	protected void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		provide(dependency.isolate(), project); //Use an isolated dependency so that multiple versions of the same group+named mappings can be used
	}

	private void provide(DependencyInfo dependency, Project project) throws Exception {
		project.getLogger().info(":stacking mappings (" + dependency.getFullName() + ' ' + dependency.getResolvedVersion() + ')');

		File mappingsFile = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find dependency " + dependency));
		String mappingsName = dependency.getFullName();

		String version = dependency.getResolvedVersion();
		String minecraftVersion, mappingsVersion;
		if (version.contains("+build.")) {
			minecraftVersion = version.substring(0, version.lastIndexOf('+'));
			mappingsVersion = version.substring(version.lastIndexOf('.') + 1);
		} else {
			try {
				char splitter = version.contains("-") ? '-' : '.';
				minecraftVersion = version.substring(0, version.lastIndexOf(splitter));
				mappingsVersion = version.substring(version.lastIndexOf(splitter) + 1);
			} catch (IndexOutOfBoundsException e) {
				throw new InvalidUserDataException("Mapping dependency with invalid version given: " + dependency, e);
			}
		}

		MappingType type;
		switch (FilenameUtils.getExtension(mappingsFile.getName())) {
		case "zip": {//Directly downloaded the enigma file (:enigma@zip)
			type = MappingType.Enigma;
			break;
		}
		case "gz": //Directly downloaded the tiny file (:tiny@gz)
			type = MappingType.TinyGz;
			break;

		case "jar": //Downloaded a jar containing the tiny jar
			type = MappingType.Tiny;
			break;

		default: //Not sure what we've ended up with, but it's not what we want/expect
			throw new IllegalStateException("Unexpected mappings base type: " + FilenameUtils.getExtension(mappingsFile.getName()) + "(from " + mappingsFile.getName() + ')');
		}

		realProvider.stackMappings(new MappingFile(mappingsFile, mappingsName, mappingsVersion, minecraftVersion, type));
	}
}