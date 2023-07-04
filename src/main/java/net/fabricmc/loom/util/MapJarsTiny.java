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

package net.fabricmc.loom.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.JarNameFactory;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.providers.MinecraftProvider;
import net.fabricmc.loom.providers.MinecraftVersionAdaptable;
import net.fabricmc.loom.providers.mappings.MappingBlob;
import net.fabricmc.loom.util.AccessTransformerHelper.ZipEntryAT;
import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.stitch.commands.CommandFixNesting;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

public class MapJarsTiny {
	public static void mapJars(MinecraftProvider jarProvider, MinecraftMappedProvider mapProvider, Project project) throws IOException {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		Path[] classpath = mapProvider.getMapperPaths().stream().map(File::toPath).toArray(Path[]::new);

		interJar: {
			String fromM;
			switch (jarProvider.getMergeStrategy()) {
			case FIRST:
				fromM = "official";
				break;

			case CLIENT_ONLY:
				fromM = "client";
				break;

			case SERVER_ONLY:
				fromM = "server";
				break;

			case LAST:
				if (!mapProvider.getIntermediaryJar().exists()) {//It may already exist if the merged jar is purely in Intermediary names
					Files.copy(jarProvider.getMergedJar(), mapProvider.getIntermediaryJar().toPath());
				} else {
					assert jarProvider.getMergedJar().toFile().equals(mapProvider.getIntermediaryJar());
				}
				break interJar;

			case INDIFFERENT:
			default:
				throw new IllegalStateException("Unexpected jar merge strategy " + jarProvider.getMergeStrategy());
			}

			mapJar(project.getLogger(), extension, mappingsProvider, jarProvider.getMergedJar(), classpath, mapProvider.getIntermediaryJar(), fromM, "intermediary");
		}

		mapJar(project.getLogger(), extension, mappingsProvider, mapProvider.getIntermediaryJar().toPath(), classpath, mapProvider.getMappedJar(), "intermediary", "named");
		CommandFixNesting.run(mapProvider.getMappedJar());

		if (extension.shouldAddVersionIfNeeded() && !ZipUtil.containsEntry(mapProvider.getMappedJar(), "version.json")) addVersionJSON(mapProvider.getMappedJar(), jarProvider.minecraftVersion);
	}

	private static void mapJar(Logger logger, LoomGradleExtension extension, MappingsProvider mappingsProvider, Path input, Path[] classpath, File output, String fromM, String toM) throws IOException {
		remapJar(logger, input, mappingsProvider.mcRemappingFactory.create(fromM, toM), extension.shouldBulldozeMappings(), classpath, output.toPath(), fromM, toM);
	}

	public static Path makeInterJar(Project project, LoomGradleExtension extension, MinecraftVersionAdaptable version, Optional<Path> intermediaryMappings) throws IOException {
		String fromM;
		JarNameFactory nameFactory;
		switch (version.getMergeStrategy()) {
		case FIRST:
			fromM = "official";
			nameFactory = JarNameFactory.MERGED_INTERMEDIARY;
			break;

		case CLIENT_ONLY:
			fromM = "client";
			nameFactory = JarNameFactory.CLIENT_INTERMEDIARY;
			break;

		case SERVER_ONLY:
			fromM = "server";
			nameFactory = JarNameFactory.SERVER_INTERMEDIARY;
			break;

		case LAST:
			if (version.needsIntermediaries()) {
				if (intermediaryMappings.isPresent()) {
					intermediaryMappings.ifPresent(version::giveIntermediaries);
				} else {//Would've been nice if ifPresent returned a boolean for if it is
					version.getOrFindIntermediaries(extension);
				}
			}

			return version.getMergedJar();

		case INDIFFERENT:
		default:
			throw new IllegalStateException("Unexpected jar merge strategy " + version.getMergeStrategy());
		}

		Path interJar = extension.getUserCache().toPath().resolve(nameFactory.getJarName(version.makeNamingStrategy()));

		if (Files.notExists(interJar)) {
			remapJar(project.getLogger(), version.getMergedJar(), //Long method calls are long
					intermediaryMappings.orElseGet(() -> version.getOrFindIntermediaries(extension)),
					false, version.getJavaLibraries(project), interJar, fromM);
		}

		return interJar;
	}

	public static void remapJar(Logger logger, Path originJar, Path intermediaryMappings, boolean bulldoze, Set<File> libraries, Path remappedJar, String originMappings) {
		remapJar(logger, originJar,
				TinyUtils.createTinyMappingProvider(intermediaryMappings, originMappings, "intermediary"),
				bulldoze, libraries.stream().map(File::toPath).toArray(Path[]::new), remappedJar, originMappings, "intermediary");
	}

	private static void remapJar(Logger logger, Path input, IMappingProvider mappings, boolean bulldozeMappings, Path[] classpath, Path output, String fromM, String toM) {
		logger.lifecycle(":Remapping minecraft (TinyRemapper, " + fromM + " -> " + toM + ')');

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(mappings)
				.ignoreConflicts(bulldozeMappings)
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.build();

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath(output)) {
			remapper.readClassPath(classpath);
			remapper.readInputs(input);
			remapper.apply(outputConsumer);
			outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);
		} catch (Exception e) {
			throw new RuntimeException("Failed to remap JAR " + input + " with mappings from " + mappings, e);
		} finally {
			remapper.finish();
		}
	}

	public static void transform(Project project, Set<Pair<String, String>> ats, MinecraftMappedProvider jarProvider, MappingsProvider mappingProvider) throws IOException {
		project.getLogger().info("Reading in mappings...");

		Mappings mappings;
		try (InputStream in = new FileInputStream(mappingProvider.MAPPINGS_TINY)) {
			mappings = net.fabricmc.mappings.MappingsProvider.readTinyMappings(in, false);
		}

		project.getLogger().info("Read in " + mappings.getClassEntries().size() + " classes");
		project.getLogger().info("Working out what we have to do");

		final String wildcard = "<*>"; //Special marker for the class itself rather than a method
		Map<String, Set<String>> transforms = new HashMap<>();
		Map<String, Set<String>> interTransforms = new HashMap<>();

		Map<Boolean, Set<Pair<String, String>>> bits = ats.stream().collect(Collectors.partitioningBy(pair -> pair.getRight() != null, Collectors.toSet()));
		Set<String> rawClasses = bits.get(Boolean.FALSE).stream().map(Pair::getLeft).collect(Collectors.toSet());

		for (ClassEntry entry : mappings.getClassEntries()) {
			String named = entry.get("named");
			if (rawClasses.contains(named)) {
				rawClasses.remove(named);

				String inter = entry.get("intermediary");
				if (inter == null) throw new IllegalStateException("Missing intermediary name for " + named);
				transforms.computeIfAbsent(named, k -> new HashSet<>()).add(wildcard);
				interTransforms.computeIfAbsent(inter, k -> new HashSet<>()).add(wildcard);
			}
		}

		Map<String, Set<String>> methods = bits.get(Boolean.TRUE).stream().collect(Collectors.groupingBy(Pair::getLeft, Collectors.mapping(Pair::getRight, Collectors.toSet())));
		for (MethodEntry entry : mappings.getMethodEntries()) {
			EntryTriple named = entry.get("named");
			Set<String> targets = methods.get(named.getOwner());

			if (targets != null && targets.contains(named.getName() + named.getDesc())) {
				EntryTriple inter = entry.get("intermediary");
				if (inter == null) throw new IllegalStateException("Missing intermediary name for " + named);
				transforms.computeIfAbsent(named.getOwner(), k -> new HashSet<>()).add(named.getName() + named.getDesc());
				interTransforms.computeIfAbsent(inter.getOwner(), k -> new HashSet<>()).add(inter.getName() + inter.getDesc());

				targets.remove(named.getName() + named.getDesc());
				if (targets.isEmpty()) methods.remove(named.getOwner());
			}
		}

		if (!methods.isEmpty()) {
			List<String> resolved = new ArrayList<>();
			UnaryOperator<String> remapper = name -> {
				for (ClassEntry classEntry : mappings.getClassEntries()) {
					if (name.equals(classEntry.get("named"))) {
						return classEntry.get("intermediary");
					}
				}

				return name;
			};

			for (Entry<String, Set<String>> entry : methods.entrySet()) {
				List<String> resolvedConstructors = new ArrayList<>();
				Set<String> unresolvedMethods = entry.getValue();

				for (String method : unresolvedMethods) {
					//Constructors aren't included as part of the mappings, but that doesn't mean that they don't need remapping
					if (method.startsWith("<init>(")) {
						resolvedConstructors.add(method);

						transforms.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).add(method);
						interTransforms.computeIfAbsent(remapper.apply(entry.getKey()), k -> new HashSet<>()).add(MappingBlob.remapDesc(method, remapper));
					}
				}

				if (!resolvedConstructors.isEmpty()) {
					unresolvedMethods.removeAll(resolvedConstructors);

					if (unresolvedMethods.isEmpty()) {
						resolved.add(entry.getKey());
					}
				}
			}

			if (!resolved.isEmpty()) resolved.forEach(methods::remove);
		}

		if (!rawClasses.isEmpty() || !methods.isEmpty()) {
			project.getLogger().error("Unable to find mappings for the following entries in access transformer:");
			rawClasses.forEach(name -> project.getLogger().error('\t' + name));
			methods.forEach((key, value) -> {
				project.getLogger().error('\t' + key + ':');
				value.forEach(name -> project.getLogger().error("\t\t" + name));
			});
			throw new InvalidUserDataException("Invalid lines found within access transformer");
		}
		project.getLogger().info("Found " + transforms.size() + " classes that need tinkering with");
		project.getLogger().lifecycle(":transforming minecraft");

		boolean traditional = project.getExtensions().getByType(LoomGradleExtension.class).useTraditionalAT();
		project.getLogger().info("Transforming intermediary jar");
		doTheDeed(jarProvider.MINECRAFT_INTERMEDIARY_JAR, mappings, "intermediary", interTransforms, wildcard, traditional);
		project.getLogger().info("Transforming named jar");
		doTheDeed(jarProvider.MINECRAFT_MAPPED_JAR, mappings, "named", transforms, wildcard, traditional);
		project.getLogger().info("Transformation complete"); //Probably, successful is another matter
	}

	private static void doTheDeed(File jar, Mappings mappings, String type, Map<String, Set<String>> transforms, String wildcard, boolean traditional) throws IOException {
		Set<String> classPool = mappings.getClassEntries().parallelStream().map(entry -> entry.get(type)).collect(Collectors.toSet());
		ZipEntryAT[] transformers = AccessTransformerHelper.makeZipATs(classPool, transforms, wildcard, traditional);

		ZipUtil.transformEntries(jar, transformers);

		if (!Arrays.stream(transformers).allMatch(ZipEntryAT::didTransform)) {
			List<String> missed = new ArrayList<>();
			for (ZipEntryAT transformer : transformers) {
				if (!transformer.didTransform()) {
					String name = transformer.getPath();
					missed.add(name.substring(0, name.length() - ".class".length()));
				}
			}
			throw new IllegalStateException("Finished transforming but missed " + missed);
		}
	}

	private static void addVersionJSON(File jar, String version) {
		//Add the minimum needed for Fabric Loader's McVersionLookup to infer a semver version
		//Included from every version since 18w47b, but never before that
		ZipUtil.addEntry(jar, "version.json", String.join("\n", "{",
				"	\"id\": \"" + version + "\",",
				"	\"release_target\": \"" + version + '"',
				"}").getBytes(StandardCharsets.UTF_8));
	}
}
