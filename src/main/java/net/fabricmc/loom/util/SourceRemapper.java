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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingsReader;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.remapper.MercuryRemapper;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.stitch.util.StitchUtil;


public class SourceRemapper {
	private static final Map<TinyReader, MappingSet> MAPPING_CACHE = new HashMap<>();

	public static void remapSources(Project project, File source, File destination, boolean toNamed) throws IOException {
		remapSources(project, Collections.singleton(Pair.of(source, destination)), toNamed);
	}

	public static void remapSources(Project project, Iterable<Pair<File, File>> remapQueue, boolean toNamed) throws IOException {
		remapSourcesInner(project, remapQueue, toNamed);
		// TODO: FIXME - WORKAROUND https://github.com/FabricMC/fabric-loom/issues/45
		System.gc();
	}

	private static void remapSourcesInner(Project project, Iterable<Pair<File, File>> remapQueue, boolean toNamed) throws IOException {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		MappingSet mappings;
		try (TinyReader key = new TinyReader(mappingsProvider, toNamed ? "intermediary" : "named", toNamed ? "named" : "intermediary")) {
			mappings = MAPPING_CACHE.computeIfAbsent(key, reader -> {
				try {
					project.getLogger().lifecycle(":loading {} source mappings", toNamed ? "intermediary -> named" : "named -> intermediary");
					return reader.read();
				} catch (IOException e) {
					throw new UncheckedIOException("Error reading mappings from " + reader, e);
				}
			});
		}

		project.getLogger().info(":remapping source jar");

		Mercury mercury = extension.getOrCreateSrcMercuryCache(toNamed ? 1 : 0, () -> {
			Mercury m = createMercuryWithClassPath(project, toNamed);

			for (Path file : extension.getUnmappedMods()) {
				if (Files.isRegularFile(file)) {
					m.getClassPath().add(file);
				}
			}

			m.getClassPath().add(extension.getMinecraftMappedProvider().MINECRAFT_MAPPED_JAR.toPath());
			m.getClassPath().add(extension.getMinecraftMappedProvider().MINECRAFT_INTERMEDIARY_JAR.toPath());

			m.getProcessors().add(MercuryRemapper.create(mappings));

			return m;
		});

		for (Pair<File, File> task : remapQueue) {
			File source = task.getLeft();
			File destination = task.getRight();

			if (source.equals(destination)) {
				if (source.isDirectory()) {
					throw new RuntimeException("Directories must differ!");
				}

				source = new File(destination.getAbsolutePath().substring(0, destination.getAbsolutePath().lastIndexOf('.')) + "-dev.jar");

				try {
					com.google.common.io.Files.move(destination, source);
				} catch (IOException e) {
					throw new RuntimeException("Could not rename " + destination.getName() + "!", e);
				}
			}

			Path srcPath = source.toPath();
			boolean isSrcTmp = false;

			if (!source.isDirectory()) {
				// create tmp directory
				isSrcTmp = true;
				srcPath = Files.createTempDirectory("fabric-loom-src");
				ZipUtil.unpack(source, srcPath.toFile());
			}

			if (!destination.isDirectory() && destination.exists()) {
				if (!destination.delete()) {
					throw new RuntimeException("Could not delete " + destination.getName() + "!");
				}
			}

			StitchUtil.FileSystemDelegate dstFs = destination.isDirectory() ? null : StitchUtil.getJarFileSystem(destination, true);
			Path dstPath = dstFs != null ? dstFs.get().getPath("/") : destination.toPath();

			try {
				mercury.rewrite(srcPath, dstPath);
			} catch (Exception e) {
				project.getLogger().warn("Could not remap " + source.getName() + " fully!", e);
			}

			copyNonJavaFiles(srcPath, dstPath, project.getLogger(), source);

			if (dstFs != null) {
				dstFs.close();
			}

			if (isSrcTmp) {
				Files.walkFileTree(srcPath, new DeletingFileVisitor());
			}
		}
	}

	private static void copyNonJavaFiles(Path from, Path to, Logger logger, File source) throws IOException {
		Files.walk(from).forEach(path -> {
			Path targetPath = to.resolve(from.relativize(path).toString());

			if (!isJavaFile(path) && !Files.exists(targetPath)) {
				try {
					Files.copy(path, targetPath);
				} catch (IOException e) {
					logger.warn("Could not copy non-java sources '" + source.getName() + "' fully!", e);
				}
			}
		});
	}

	public static Mercury createMercuryWithClassPath(Project project, boolean toNamed) {
		Mercury m = new Mercury();

		for (File file : project.getConfigurations().getByName(Constants.MINECRAFT_DEPENDENCIES).getFiles()) {
			m.getClassPath().add(file.toPath());
		}

		if (!toNamed) {
			for (File file : project.getConfigurations().getByName("compileClasspath").getFiles()) {
				m.getClassPath().add(file.toPath());
			}
		}

		return m;
	}

	private static boolean isJavaFile(Path path) {
		String name = path.getFileName().toString();
		// ".java" is not a valid java file
		return name.endsWith(".java") && name.length() != 5;
	}

	private static class TinyReader extends MappingsReader {
		private final MappingsProvider provider;
		private final String from, to;

		public TinyReader(MappingsProvider provider, String from, String to) {
			this.provider = provider;
			this.from = from;
			this.to = to;
		}

		@Override
		public MappingSet read(final MappingSet mappings) throws IOException {
			Mappings m = provider.getMappings();

			for (ClassEntry entry : m.getClassEntries()) {
				mappings.getOrCreateClassMapping(entry.get(from))
						.setDeobfuscatedName(entry.get(to));
			}

			for (FieldEntry entry : m.getFieldEntries()) {
				EntryTriple fromEntry = entry.get(from);
				EntryTriple toEntry = entry.get(to);

				mappings.getOrCreateClassMapping(fromEntry.getOwner())
						.getOrCreateFieldMapping(fromEntry.getName(), fromEntry.getDesc())
						.setDeobfuscatedName(toEntry.getName());
			}

			for (MethodEntry entry : m.getMethodEntries()) {
				EntryTriple fromEntry = entry.get(from);
				EntryTriple toEntry = entry.get(to);

				mappings.getOrCreateClassMapping(fromEntry.getOwner())
						.getOrCreateMethodMapping(fromEntry.getName(), fromEntry.getDesc())
						.setDeobfuscatedName(toEntry.getName());
			}

			return mappings;
		}

		@Override
		public void close() { }

		@Override
		public String toString() {
			return provider.MAPPINGS_TINY.getName() + '[' + from + " => " + to + ']';
		}

		@Override
		public int hashCode() {
			return Objects.hash(provider.MAPPINGS_TINY, from, to);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof TinyReader)) return false;

			TinyReader that = (TinyReader) obj;
			return Objects.equals(from, that.from) && Objects.equals(to, that.to) && Objects.equals(provider.MAPPINGS_TINY, that.provider.MAPPINGS_TINY);
		}
	}
}
