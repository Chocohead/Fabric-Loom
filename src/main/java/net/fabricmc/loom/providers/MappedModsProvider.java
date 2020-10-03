/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.zeroturnaround.zip.ZipUtil;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.DependencyProvider;
import net.fabricmc.loom.dependencies.LogicalDependencyProvider;
import net.fabricmc.loom.util.AccessTransformerHelper;
import net.fabricmc.loom.util.Closer;
import net.fabricmc.loom.util.SourceRemapper;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class MappedModsProvider extends LogicalDependencyProvider {
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Set<File> extraClasspath = new HashSet<>();
	private final Multimap<File, File> sourced = HashMultimap.create();
	private final Multimap<File, File> unsourced = HashMultimap.create();
	private final Multimap<File, File> sources = HashMultimap.create();

	@Override
	public Set<Class<? extends DependencyProvider>> getDependencies() {
		return ImmutableSet.of(MinecraftMappedProvider.class, MappedModsResolver.class);
	}

	void queueRemap(File input, File output, boolean hasSources) {
		if (!hasSources) {
			(sourced.containsKey(input) ? sourced : unsourced).put(input, output);
		} else {
			sourced.put(input, output);

			Collection<File> outputs = unsourced.removeAll(input);
			if (outputs != null) sourced.putAll(input, outputs);
		}
	}

	void noteClasspath(File input) {
		extraClasspath.add(input);
	}

	void queueRemap(File input, File output) {
		sources.put(input, output);
	}

	@Override
	public void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		//If there's jars which need remapping, let's remap them
		if (!sourced.isEmpty() || !unsourced.isEmpty()) remapJars(project.getLogger(), extension);

		//If there's source jars which need remapping, let's remap them too
		if (!sources.isEmpty()) remapSources(project, postPopulationScheduler);
	}

	private void remapJars(Logger logger, LoomGradleExtension extension) throws Exception {
		MinecraftMappedProvider mappedProvider = getProvider(MinecraftMappedProvider.class);
		MappingsProvider mappingsProvider = getProvider(MappingsProvider.class);

		final String fromM = "intermediary";
		final String toM = "named";
		logger.lifecycle(":remapping {} mod(s) (TinyRemapper, " + fromM + " -> " + toM + ')', sourced.size() + unsourced.size());

		// If the sources don't exist, we want remapper to give nicer names to the missing variable names.
		// However, if the sources do exist, if remapper gives names to the parameters that prevents IDEs (at least IDEA)
		// from replacing the parameters with the actual names from the sources.
		boolean sourcesExist = !sourced.isEmpty();

		TinyRemapper remapper = TinyRemapper.newRemapper()
						.withMappings(TinyRemapperMappingsHelper.create(extension, mappingsProvider.getMappings(), fromM, toM))
						.ignoreConflicts(extension.shouldBulldozeMappings())
						.renameInvalidLocals(!sourcesExist)
						.keepInputData(sourcesExist && !unsourced.isEmpty()) //Retain the class data if the second remapper will use it too
						.build();

		try (Closer closer = Closer.create((sourcesExist ? !unsourced.isEmpty() ? sourced.size() + 1 + unsourced.size() : sourced.size() : unsourced.size()) + 1)) {
			closer.register(remapper::finish);
			remapper.readClassPathAsync(mappedProvider.MINECRAFT_INTERMEDIARY_JAR.toPath());
			remapper.readClassPathAsync(mappedProvider.getMapperPaths().stream().map(File::toPath).toArray(Path[]::new));
			remapper.readClassPathAsync(extraClasspath.stream().map(File::toPath).toArray(Path[]::new));

			final class Mod implements Closeable {
				public final File input;
				public final InputTag tag;
				private final File output;
				private final Collection<File> outputs;
				private OutputConsumerPath outputConsumer;

				public Mod(File input, InputTag tag, Collection<File> outputs) {
					this.input = input;
					this.tag = tag;
					Iterator<File> it = outputs.iterator();
					output = it.next();
					this.outputs = ImmutableList.copyOf(it);
				}

				public OutputConsumerPath startRemapping(TinyRemapper remapper) throws IOException {
					if (outputConsumer != null) throw new IllegalStateException("Already started remapping");
					outputConsumer = new OutputConsumerPath(output.toPath());

					outputConsumer.addNonClassFiles(input.toPath());
					remapper.apply(outputConsumer, tag);

					if (AccessTransformerHelper.deobfATs(input, remapper, outputConsumer)) {
						logger.info("Found and remapped access transformer in {}", input.getName());
					}

					String modJSON = new String(ZipUtil.unpackEntry(input, "fabric.mod.json"), StandardCharsets.UTF_8);
					JsonObject json = GSON.fromJson(modJSON, JsonObject.class);

					if (json.has("jars")) {//Remove any nested jar entries if there are any
						json.remove("jars");

						File temp = File.createTempFile("fabric.mod", ".json");
						Files.asCharSink(temp, StandardCharsets.UTF_8).write(GSON.toJson(json));

						outputConsumer.addNonClassFile(temp.toPath(), "fabric.mod.json");
						temp.deleteOnExit(); //Done with it now
					}

					return outputConsumer;
				}

				@Override
				public void close() throws IOException {
					if (outputConsumer != null) {
						outputConsumer.close();
						output.setLastModified(input.lastModified());

						for (File extra : outputs) {
							Files.copy(output, extra);
						}
					}
				}

				@Override
				public String toString() {//Little bit of a process to reconstruct a view of the original output collection
					return "Mod[" + input + " => " + Iterables.toString(Iterables.concat(Collections.singleton(output), outputs)) + ']';
				}
			}

			List<Mod> sourcedMods = new ArrayList<>(sourced.size());
			for (Entry<File, Collection<File>> entry : sourced.asMap().entrySet()) {
				InputTag tag = remapper.createInputTag();
				remapper.readInputsAsync(tag, entry.getKey().toPath());
				sourcedMods.add(new Mod(entry.getKey(), tag, entry.getValue()));
			}

			List<Mod> unsourcedMods = new ArrayList<>(unsourced.size());
			for (Entry<File, Collection<File>> entry : unsourced.asMap().entrySet()) {
				InputTag tag = remapper.createInputTag();
				remapper.readInputsAsync(tag, entry.getKey().toPath());
				unsourcedMods.add(new Mod(entry.getKey(), tag, entry.getValue()));
			}

			//Watch out for any naming accidents putting things in the wrong place
			assert sourced.size() == sourcedMods.size();
			assert unsourced.size() == unsourcedMods.size();

			TinyRemapper bonusRemapper = null;
			if (sourcesExist) {
				if (!unsourcedMods.isEmpty()) {
					bonusRemapper = remapper.cloner().renameInvalidLocals(true).build();
					closer.register(bonusRemapper::finish);
				}
			} else {
				bonusRemapper = remapper;
				remapper = null;
			}

			//Make sure we've got the remappers we expect for the work we've got to do
			assert sourcedMods.isEmpty() == (remapper == null);
			assert unsourcedMods.isEmpty() == (bonusRemapper == null);

			for (Mod mod : sourcedMods) {
				closer.register(mod).startRemapping(remapper);
			}
			for (Mod mod : unsourcedMods) {
				closer.register(mod).startRemapping(bonusRemapper);
			}
		}
	}

	private void remapSources(Project project, Consumer<Runnable> scheduler) {
		List<Pair<File, File>> queue = new ArrayList<>();
		Multimap<File, File> extra = ArrayListMultimap.create();

		for (Entry<File, Collection<File>> entry : sources.asMap().entrySet()) {
			Iterator<File> it = entry.getValue().iterator();
			File output = it.next();
			while (it.hasNext()) extra.put(output, it.next());

			queue.add(Pair.of(entry.getKey(), output));
		}

		scheduler.accept(() -> {
			try {
				SourceRemapper.remapSources(project, queue, true);

				for (Pair<File, File> task : queue) {
					//Set the remapped sources creation date to match the sources if we're likely succeeded in making it
					task.getRight().setLastModified(task.getLeft().lastModified());
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Error remapping sources", e);
			}
		});

		if (!extra.isEmpty()) {
			scheduler.accept(() -> {
				for (Entry<File, File> entry : extra.entries()) {
					try {
						Files.copy(entry.getKey(), entry.getValue());
					} catch (IOException e) {
						throw new UncheckedIOException("Error copying remapped source from " + entry.getKey() + " to " + entry.getValue(), e);
					}
				}
			});
		}
	}
}