/*
 * Copyright 2019, 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import cuchaz.enigma.command.MapSpecializedMethodsCommand;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradleExtension.JarMergeOrder;
import net.fabricmc.loom.dependencies.DependencyProvider;
import net.fabricmc.loom.dependencies.LogicalDependencyProvider;
import net.fabricmc.loom.providers.LazyMappings.ActiveMappings;
import net.fabricmc.loom.providers.MinecraftProvider.MinecraftVersion;
import net.fabricmc.loom.providers.StackedMappingsProvider.MappingFile;
import net.fabricmc.loom.providers.StackedMappingsProvider.MappingFile.MappingType;
import net.fabricmc.loom.providers.mappings.EnigmaReader;
import net.fabricmc.loom.providers.mappings.MappingBlob;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Field;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Method;
import net.fabricmc.loom.providers.mappings.TinyDuplicator;
import net.fabricmc.loom.providers.mappings.TinyReader;
import net.fabricmc.loom.providers.mappings.TinyV2toV1;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.MapJarsTiny;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.stitch.commands.CommandCorrectMappingUnions;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.IMappingProvider;

public class MappingsProvider extends LogicalDependencyProvider {
	public interface MappingFactory {//IOException throwing BiFunction<String, String, IMappingProvider>
		IMappingProvider create(String fromMapping, String toMapping) throws IOException;
	}
	public MappingFactory mcRemappingFactory;

	static final String INTERMEDIARY = "net.fabricmc.intermediary";
	private final List<MappingFile> mappingFiles = new ArrayList<>();

	public String mappingsName;
	public String minecraftVersion;
	public String mappingsVersion;

	public File MAPPINGS_DIR;
	public File MAPPINGS_MIXIN_EXPORT;

	private Path stackHistory;
	private boolean knownStack;
	private File intermediaryNames;
	// The mappings that gradle gives us
	private File MAPPINGS_TINY_BASE;
	// The mappings we use in practice
	public File MAPPINGS_TINY;
	private Path parameterNames, decompileComments;

	public Mappings getMappings() throws IOException {
		return MappingsCache.INSTANCE.get(MAPPINGS_TINY.toPath());
	}

	public Path getDecompileMappings() {
		return decompileComments;
	}

	@Override
	public Set<Class<? extends DependencyProvider>> getDependencies() {
		return ImmutableSet.of(StackedMappingsProvider.class, MinecraftProvider.class);
	}

	void stackMappings(MappingFile mappings) {
		mappingFiles.add(mappings);
	}

	@Override
	public void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProvider minecraftProvider = getProvider(MinecraftProvider.class);

		initFiles(extension, project.getLogger(), minecraftProvider);

		if (!MAPPINGS_TINY_BASE.exists() || !MAPPINGS_TINY.exists()) {
			if (!MAPPINGS_DIR.exists()) {
				MAPPINGS_DIR.mkdir();
			}

			free: if (!MAPPINGS_TINY_BASE.exists()) {
				Map<String, List<MappingFile>> versionToMappings = new HashMap<>();

				for (ListIterator<MappingFile> it = mappingFiles.listIterator(); it.hasNext();) {
					MappingFile file = it.next();

					if (file.type.needsEnlightening()) {
						it.set(file = file.enlighten()); //Need to work out what the type of the ambiguous mapping files are
					}

					versionToMappings.computeIfAbsent(file.minecraftVersion, k -> new ArrayList<>()).add(file);
				}

				for (List<MappingFile> mappings : versionToMappings.values()) {
					mappings.sort((fileA, fileB) -> {
						if (fileA.type == fileB.type) return 0;

						switch (fileA.type) {//Sort by ease of ability to extract headers
						case TinyV1:
							return -1;

						case TinyV2:
							return fileB.type == MappingType.TinyV1 ? 1 : -1;

						case TinyGz:
							return fileB.type == MappingType.Enigma ? -1 : 1;

						case Enigma:
							return 1;

						default:
						case Tiny:
							throw new IllegalArgumentException("Unexpected mapping types to compare: " + fileA.type + " and " + fileB.type);
						}
					});
				}

				//Need to see if any of the mapping files have Intermediaries for the Minecraft version they're going to be running on
				Optional<MappingFile> interProvider = searchForIntermediaries(versionToMappings.getOrDefault(minecraftVersion, Collections.emptyList()), minecraftProvider.getNeededHeaders());

				LazyMappings intermediaryMaker;
				if (interProvider.isPresent()) {
					MappingFile mappings = interProvider.get();

					if (mappingFiles.size() == 1) {
						assert mappings == Iterables.getOnlyElement(mappingFiles);

						project.getLogger().lifecycle(":extracting " + mappings.origin.getName());
						switch (mappings.type) {
						case TinyV1:
							try (FileSystem fileSystem = FileSystems.newFileSystem(mappings.origin.toPath(), null)) {
								Files.copy(fileSystem.getPath("mappings/mappings.tiny"), MAPPINGS_TINY_BASE.toPath());
							}
							break free;

						case TinyGz:
							FileUtils.copyInputStreamToFile(new GZIPInputStream(new FileInputStream(mappings.origin)), MAPPINGS_TINY_BASE);
							break free;

						case TinyV2:
							TinyV2toV1.convert(mappings.origin.toPath(), MAPPINGS_TINY_BASE.toPath(), parameterNames, decompileComments);
							break free;

						case Enigma:
						case Tiny:
						default: //Shouldn't end up here if this is the only mapping file supplied
							throw new IllegalStateException("Unexpected mappings type " + mappings.type + " from " + mappings.origin);
						}
					}

					project.getLogger().lifecycle(":Using intermediaries from " + mappings.origin.getName());
					intermediaryMaker = mappings;
				} else {
					if (!intermediaryNames.exists()) {//Grab intermediary mappings from Github
						project.getLogger().lifecycle(":Downloading intermediaries to " + intermediaryNames.getName());
						FileUtils.copyURLToFile(new URL(SpecialCases.intermediaries(minecraftVersion)), intermediaryNames);
					} else {
						project.getLogger().lifecycle(":Using intermediaries from " + intermediaryNames.getName());
					}

					if (mappingFiles.isEmpty()) {
						TinyDuplicator.duplicateV1Column(intermediaryNames.toPath(), MAPPINGS_TINY_BASE.toPath(), "intermediary", "named");
						break free;
					}

					intermediaryMaker = () -> new DirectMappings(intermediaryNames.toPath());
				}

				MappingBlob mappings = new MappingBlob();
				try (ActiveMappings intermediaries = intermediaryMaker.open()) {
					TinyReader.fillFromColumn(intermediaries.getMappings(), "intermediary", mappings);

					if (minecraftProvider.needsIntermediaries()) minecraftProvider.giveIntermediaries(intermediaries.getMappings());
				}
				Map<String, MappingBlob> versionToIntermediaries = new HashMap<>();
				Map<String, JarMergeOrder> versionToMerging = new HashMap<>();

				for (MappingFile mapping : mappingFiles) {
					project.getLogger().lifecycle(":loading " + mapping.origin.getName());

					MappingBlob gains = new MappingBlob();
					boolean nativeNames = false;

					switch (mapping.type) {
					case Enigma: {
						EnigmaReader.readEnigma(mapping.origin.toPath(), gains);

						if (gains.stream().parallel().noneMatch(classMapping -> classMapping.from.startsWith("net/minecraft/class_"))) {
							nativeNames = true;
						} else {
							assert gains.stream().parallel().filter(classMapping -> classMapping.to() != null).allMatch(classMapping -> classMapping.from.startsWith("net/minecraft/class_") || classMapping.from.matches("com\\/mojang\\/.+\\$class_\\d+")):
								gains.stream().filter(classMapping -> classMapping.to() != null && !classMapping.from.startsWith("net/minecraft/class_") && !classMapping.from.matches("com\\/mojang\\/.+\\$class_\\d+")).map(classMapping -> classMapping.from).collect(Collectors.joining(", ", "Found unexpected initial mapping classes: [", "]"));
							assert gains.streamMethods().parallel().filter(method -> method.name() != null).allMatch(method -> method.fromName.startsWith("method_") || method.fromName.equals(method.name())):
								gains.streamMethods().filter(method -> method.name() != null && !method.fromName.startsWith("method_")).map(method -> method.fromName + method.fromDesc).collect(Collectors.joining(", ", "Found unexpected method mappings: ", "]"));
							assert gains.streamFields().parallel().filter(field -> field.name() != null).allMatch(field -> field.fromName.startsWith("field_")):
								gains.streamFields().filter(field -> field.name() != null && !field.fromName.startsWith("field_")).map(field -> field.fromName).collect(Collectors.joining(", ", "Found unexpected field mappings: ", "]"));
						}

						String from;
						Path contextJar;
						if (minecraftVersion.equals(mapping.minecraftVersion)) {
							if (nativeNames) {
								if (minecraftProvider.getMergeStrategy() == JarMergeOrder.LAST) throw new InvalidUserDataException("Cannot use natively named Enigma mappings for a split named version!");
								from = Iterables.getOnlyElement(minecraftProvider.getNativeHeaders());
								contextJar = minecraftProvider.getMergedJar();
							} else {
								from = "intermediary";
								try (ActiveMappings intermediaries = intermediaryMaker.open()) {
									contextJar = MapJarsTiny.makeInterJar(project, extension, minecraftProvider, Optional.of(intermediaries.getMappings()));
								}
							}
						} else {
							MinecraftVersion version = MinecraftProvider.makeMergedJar(project, extension, mapping.minecraftVersion, Optional.empty(), JarMergeOrder.INDIFFERENT);

							if (nativeNames) {
								if (version.getMergeStrategy() == JarMergeOrder.LAST) throw new InvalidUserDataException("Cannot use natively named Enigma mappings for a split named version!");
								from = Iterables.getOnlyElement(version.getNativeHeaders());
								contextJar = version.getMergedJar();
							} else {
								from = "intermediary";
								contextJar = MapJarsTiny.makeInterJar(project, extension, version, //See if we've actually got the old Intermediaries per chance too
										searchForIntermediaries(versionToMappings.getOrDefault(mapping.minecraftVersion, Collections.emptyList()), version.getNeededHeaders()).map(mappingFile -> mappingFile.origin.toPath()));
							}
						}

						Path specialisedMappings = MAPPINGS_DIR.toPath().resolve(FilenameUtils.removeExtension(mapping.origin.getName()) + "-specialised.jar");
						try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + specialisedMappings.toUri()), Collections.singletonMap("create", "true"))) {
							Path destination = fs.getPath("mappings/mappings.tiny");

							Files.createDirectories(destination.getParent());
							MapSpecializedMethodsCommand.run(contextJar, "enigma", mapping.origin.toPath(), "tinyv2:" + from + ":named", destination);
						} catch (URISyntaxException e) {
							throw new IllegalStateException("Cannot convert jar path to URI?", e);
						} catch (IOException e) {
							throw new UncheckedIOException("Error creating mappings jar", e);
						}

						mapping = new MappingFile(specialisedMappings.toFile(), mapping.name, mapping.version, mapping.minecraftVersion, MappingType.TinyV2, ImmutableList.of(from, "named"));
					}

					case TinyV1:
					case TinyV2: {
						String origin;
						if (mapping.getNamespaces().contains("intermediary")) {
							origin = "intermediary";
						} else {
							JarMergeOrder mergeStrategy = versionToMerging.computeIfAbsent(mapping.minecraftVersion, version -> MinecraftProvider.findMergeStrategy(project, extension, version));
							if (mergeStrategy == JarMergeOrder.LAST) throw new InvalidUserDataException("Cannot use natively named mappings for a split named version!");

							nativeNames = true;
							origin = Iterables.getOnlyElement(mergeStrategy.getNativeHeaders());
						}
						assert mapping.getNamespaces().contains("named");

						try (FileSystem fileSystem = FileSystems.newFileSystem(mapping.origin.toPath(), null)) {
							TinyReader.readTiny(fileSystem.getPath("mappings/mappings.tiny"), origin, "named", gains);

							if (mapping.type == MappingType.TinyV2) {
								UnaryOperator<String> classRemapper;
								if (!origin.equals(mapping.getNamespaces().get(0))) {
									MappingBlob nativeToOrigin = new MappingBlob();
									TinyReader.readTiny(fileSystem.getPath("mappings/mappings.tiny"), mapping.getNamespaces().get(0), origin, nativeToOrigin);
									classRemapper = name -> {
										String remap = nativeToOrigin.tryMapName(name);
										return remap != null ? remap : name;
									};
								} else {
									classRemapper = null; //Origin column is the main column, descriptors won't need to be renamed
								}
								TinyReader.readComments(fileSystem.getPath("mappings/mappings.tiny"), origin, classRemapper, gains);
							}
						}
						break;
					}

					case TinyGz: {
						Collection<String> namespaces = TinyReader.readHeaders(mapping.origin.toPath());

						String origin;
						if (namespaces.contains("intermediary")) {
							origin = "intermediary";
						} else {
							JarMergeOrder mergeStrategy = versionToMerging.computeIfAbsent(mapping.minecraftVersion, version -> MinecraftProvider.findMergeStrategy(project, extension, version));
							if (mergeStrategy == JarMergeOrder.LAST) throw new InvalidUserDataException("Cannot use natively named mappings for a split named version!");

							nativeNames = true;
							origin = Iterables.getOnlyElement(mergeStrategy.getNativeHeaders());
						}
						assert namespaces.contains("named");

						TinyReader.readTiny(mapping.origin.toPath(), origin, "named", gains);
						break;
					}

					case Tiny: //Should have already enlightened this by now
						throw new IllegalStateException("Unexpected mappings type " + mapping.type + " from " + mapping.origin);
					}

					if (nativeNames) {
						MappingBlob renamer = versionToIntermediaries.computeIfAbsent(mapping.minecraftVersion, version -> {
							ActiveMappings mappingsPipe = null;
							Path intermediaryNames;
							if (!minecraftVersion.equals(version)) {
								intermediaryNames = searchForIntermediaries(versionToMappings.getOrDefault(version, Collections.emptyList()), null)
										.map(mappingFile -> mappingFile.origin.toPath()).orElseGet(() -> getIntermediaries(extension, version));
							} else {
								try {
									mappingsPipe = intermediaryMaker.open();
								} catch (IOException e) {
									throw new UncheckedIOException("Error opening Intermediary maker", e);
								}
								intermediaryNames = mappingsPipe.getMappings();
							}

							JarMergeOrder mergeStrategy = versionToMerging.computeIfAbsent(version, v -> MinecraftProvider.findMergeStrategy(project, extension, v));
							if (mergeStrategy == JarMergeOrder.LAST) throw new InvalidUserDataException("Cannot use natively named mappings for a split named version!");

							MappingBlob inters = new MappingBlob();
							try {
								TinyReader.readTiny(intermediaryNames, Iterables.getOnlyElement(mergeStrategy.getNativeHeaders()), "intermediary", inters);
							} catch (IOException e) {
								throw new UncheckedIOException("Error reading Intermediary mappings for " + version, e);
							} finally {
								if (mappingsPipe != null) {
									try {
										mappingsPipe.close();
									} catch (IOException e) {
										project.getLogger().warn("Error closing Intermediary maker", e);
									}
								}
							}
							return inters;
						});

						logErroneousMappings(project.getLogger(), gains, renamer);
						gains = gains.rename(renamer);
					}

					for (Mapping classMapping : gains) {
						//If the name has been lost since it was named there's no point including it
						if (!mappings.has(classMapping.from)) continue;

						Mapping existingClass = mappings.get(classMapping.from);
						if (existingClass.to() == null && !classMapping.from.equals(classMapping.to())) {
							mappings.acceptClass(classMapping.from, classMapping.to());
						}

						if (!existingClass.comment().isPresent()) {
							classMapping.comment().ifPresent(comment -> {
								mappings.acceptClassComment(classMapping.from, comment);
							});
						}

						for (Method method : classMapping.methods()) {
							if (!existingClass.hasMethod(method) && method.fromName.charAt(0) != '<') continue;

							Method existingMethod = existingClass.method(method);
							if (existingMethod.name() == null && !existingMethod.fromName.equals(method.name())) {
								mappings.acceptMethod(classMapping.from, method.fromName, method.fromDesc, existingClass.to(), method.name(), method.desc());
							}

							if (!existingMethod.comment().isPresent()) {
								method.comment().ifPresent(comment -> {
									mappings.acceptMethodComment(classMapping.from, method.fromName, method.fromDesc, comment);
								});
							}

							if (method.hasArgs()) {
								method.iterateArgs((index, arg) -> {
									if (existingMethod.arg(index) == null) {
										mappings.acceptMethodArg(classMapping.from, method.fromName, method.fromDesc, index, arg);
									}
								});
								method.iterateArgComments((comment, index) -> {
									if (!existingMethod.argComment(index).isPresent()) {
										mappings.acceptMethodArgComment(classMapping.from, method.fromName, method.fromDesc, index, comment);
									}
								});
							}
						}

						for (Field field : classMapping.fields()) {
							if (!existingClass.hasField(field)) continue;

							Field existingField = existingClass.field(field);
							if (existingField.name() == null && !existingField.fromName.equals(field.name())) {
								mappings.acceptField(classMapping.from, field.fromName, field.fromDesc, existingClass.to(), field.name(), field.desc());
							}

							if (!existingField.comment().isPresent()) {
								field.comment().ifPresent(comment -> {
									mappings.acceptFieldComment(classMapping.from, field.fromName, field.fromDesc, comment);
								});
							}
						}
					}
				}

				project.getLogger().lifecycle(":combining mappings");
				Map<ClassEntry, Pair<Set<MethodEntry>, Set<FieldEntry>>> intermediaryMappings;
				try (ActiveMappings intermediaries = intermediaryMaker.open()) {
					intermediaryMappings = TinyReader.readTiny(intermediaries.getMappings(), "intermediary");
				}

				project.getLogger().lifecycle(":writing " + MAPPINGS_TINY_BASE.getName());
				try (TinyWriter writer = new TinyWriter(MAPPINGS_TINY_BASE.toPath(), Stream.concat(minecraftProvider.getNeededHeaders().stream(), Stream.of("named")).toArray(String[]::new))) {
					for (Entry<ClassEntry, Pair<Set<MethodEntry>, Set<FieldEntry>>> entry : intermediaryMappings.entrySet()) {
						String[] classMappings = minecraftProvider.getNeededHeaders().stream().map(entry.getKey()::get).toArray(String[]::new);

						String className = classMappings[0];
						Mapping mapping = mappings.getOrDummy(className);

						if (!Arrays.stream(classMappings).skip(1).allMatch(Predicate.isEqual(className))) {
							String name = mapping.to();

							out: if (name == null) {
								String[] segments = className.split("\\$");

								if (segments.length > 1) {
									for (int end = segments.length - 1; end > 0; end--) {
										StringJoiner nameBits = new StringJoiner("$");
										for (int i = 0; i < end; i++) {
											nameBits.add(segments[i]);
										}

										String parent = mappings.tryMapName(nameBits.toString());
										if (parent != null) {
											StringBuilder fullName = new StringBuilder(parent);
											for (int i = end; i < segments.length; i++) {
												fullName.append('$').append(segments[i]);
											}
											name = fullName.toString();
											break out;
										}
									}
								}

								name = className;
							}

							writer.acceptClass(Stream.concat(Arrays.stream(classMappings), Stream.of(name)).toArray(String[]::new));
						}

						for (MethodEntry method : entry.getValue().getLeft()) {
							EntryTriple[] methodMappings = minecraftProvider.getNeededHeaders().stream().map(method::get).toArray(EntryTriple[]::new);

							if (Arrays.stream(methodMappings).skip(1).anyMatch(Objects::nonNull)) {
								Method methodMapping = mapping.method(methodMappings[0]);
								writer.acceptMethod(className, methodMappings[0].getDesc(), Stream.concat(Arrays.stream(methodMappings).map(nullSafe(EntryTriple::getName)), Stream.of(methodMapping.nameOr(methodMappings[0].getName()))).toArray(String[]::new));
							}
						}

						for (FieldEntry field : entry.getValue().getRight()) {
							EntryTriple[] fieldMappings = minecraftProvider.getNeededHeaders().stream().map(field::get).toArray(EntryTriple[]::new);

							if (!Arrays.stream(fieldMappings).skip(1).filter(Objects::nonNull).map(EntryTriple::getName).allMatch(Predicate.isEqual(fieldMappings[0].getName()))) {
								Field fieldMapping = mapping.field(fieldMappings[0]);
								writer.acceptField(className, fieldMappings[0].getDesc(), Stream.concat(Arrays.stream(fieldMappings).map(nullSafe(EntryTriple::getName)), Stream.of(fieldMapping.nameOr(fieldMappings[0].getName()))).toArray(String[]::new));
							}
						}
					}
				}

				if (mappings.hasArgNames()) {
					project.getLogger().lifecycle(":writing " + parameterNames.getFileName());
					try (BufferedWriter writer = Files.newBufferedWriter(parameterNames)) {
						for (Mapping mapping : mappings) {
							for (Method method : mapping.methodsWithArgs()) {
								if (!method.hasArgNames()) continue; //Just comments for the arguments

								writer.write(mapping.toOr(mapping.from));
								writer.write('/');
								writer.write(method.fromName);
								writer.write(method.fromDesc);
								writer.newLine();

								method.<IOException>iterateArgs((index, arg) -> {
									assert arg != null; //Should be skipping nulls

									writer.write('\t');
									writer.write(Integer.toString(index));
									writer.write(':');
									writer.write(' ');
									writer.write(arg);
									writer.newLine();
								});
							}
						}
					}
				}

				if (mappings.hasComments()) {
					project.getLogger().lifecycle(":writing " + decompileComments.getFileName());
					try (BufferedWriter writer = Files.newBufferedWriter(decompileComments)) {
						TinyV2toV1.writeComments(writer, mappings);
					}
				}

				if (MAPPINGS_TINY.exists()) {
					MAPPINGS_TINY.delete();
				}

				//If we've successfully joined all the mappings together, save the stack
				if (!knownStack && mappingFiles.size() > 1) writeStackHistory(mappingsVersion);
			}

			assert MAPPINGS_TINY_BASE.exists();
			if (minecraftProvider.needsIntermediaries()) minecraftProvider.giveIntermediaries(MAPPINGS_TINY_BASE.toPath());
			assert !MAPPINGS_TINY.exists();

			project.getLogger().lifecycle(":populating field names");
			String namespace;
			switch (minecraftProvider.getMergeStrategy()) {
			case FIRST:
				namespace = "official";
				break;

			case LAST:
				namespace = "intermediary";
				break;

			case CLIENT_ONLY:
				namespace = "client";
				break;

			case SERVER_ONLY:
				namespace = "server";
				break;

			case INDIFFERENT:
			default:
				throw new IllegalStateException("Unexpected jar merge strategy " + minecraftProvider.getMergeStrategy());
			}
			CommandProposeFieldNames.run(minecraftProvider.getMergedJar().toFile(), MAPPINGS_TINY_BASE, MAPPINGS_TINY, namespace, "named", extension.getFieldInferenceFilter());
			CommandCorrectMappingUnions.run(MAPPINGS_TINY.toPath(), "intermediary", "named");
		} else {
			if (minecraftProvider.needsIntermediaries()) minecraftProvider.giveIntermediaries(MAPPINGS_TINY.toPath());
		}

		if (Files.exists(parameterNames)) {
			//Merge the tiny mappings with parameter names
			Map<String, String[]> lines = new HashMap<>();

			try (BufferedReader reader = Files.newBufferedReader(parameterNames)) {
				for (String line = reader.readLine(), current = null; line != null; line = reader.readLine()) {
					if (current == null || line.charAt(0) != '\t') {
						current = line;
					} else {
						int split = line.indexOf(':'); //\tno: name
						int number = Integer.parseInt(line.substring(1, split));
						String name = line.substring(split + 2);

						String[] lineSet = lines.get(current);
						if (lineSet == null) {
							//The args are written backwards so the biggest index is first
							lines.put(current, lineSet = new String[number + 1]);
						}
						lineSet[number] = name;
					}
				}
			}

			mcRemappingFactory = (fromM, toM) -> new IMappingProvider() {
				private final IMappingProvider normal = TinyRemapperMappingsHelper.create(extension, getMappings(), fromM, toM);

				@Override
				public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap) {
					load(classMap, fieldMap, methodMap);
					if ("intermediary".equals(fromM)) {
						localMap.putAll(lines);
					}
				}

				@Override
				public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap) {
					normal.load(classMap, fieldMap, methodMap);
				}

				@Override
				public String suggestLocalName(String type, boolean plural) {
					return normal.suggestLocalName(type, plural);
				}
			};
		} else {
			mcRemappingFactory = (fromM, toM) -> TinyRemapperMappingsHelper.create(extension, getMappings(), fromM, toM);
		}

		File mappingJar;
		if (mappingFiles.size() == 1 && Iterables.getOnlyElement(mappingFiles).enlighten().type == MappingType.TinyV1) {
			mappingJar = Iterables.getOnlyElement(mappingFiles).origin;
			if (MAPPINGS_TINY.lastModified() < mappingJar.lastModified()) MAPPINGS_TINY.setLastModified(mappingJar.lastModified() - 1);
		} else {
			mappingJar = new File(MAPPINGS_DIR, FilenameUtils.removeExtension(MAPPINGS_TINY.getName()) + ".jar");

			if (!mappingJar.exists() || mappingJar.lastModified() < MAPPINGS_TINY.lastModified()) {
				try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + mappingJar.toURI()), Collections.singletonMap("create", "true"))) {
					Path destination = fs.getPath("mappings/mappings.tiny");

					Files.createDirectories(destination.getParent());
					Files.copy(MAPPINGS_TINY.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
				} catch (URISyntaxException e) {
					throw new IllegalStateException("Cannot convert jar path to URI?", e);
				} catch (IOException e) {
					throw new UncheckedIOException("Error creating mappings jar", e);
				}
			}
		}

		assert mappingJar.exists() && mappingJar.lastModified() >= MAPPINGS_TINY.lastModified();
		addDependency(mappingJar, project, Constants.MAPPINGS);
	}

	private static Optional<MappingFile> searchForIntermediaries(List<MappingFile> mappings, Collection<String> interHeaders) {
		return mappings.stream().filter(file -> {
			try {
				List<String> headers;
				switch (file.type) {
				case Enigma: //Never will (unless it goes Notch <=> Intermediary which is pointless to be in Enigma's format)
					return false;

				case TinyV1:
				case TinyV2:
					headers = file.getNamespaces();
					break;

				case TinyGz:
					headers = TinyReader.readHeaders(file.origin.toPath());
					break;

				default:
				case Tiny:
					throw new IllegalArgumentException("Unexpected mapping types to read: " + file.type);
				}

				return headers.containsAll(interHeaders);
			} catch (IOException e) {
				throw new UncheckedIOException("Error reading mapping file from " + file.origin, e);
			}
		}).findFirst();
	}

	public static Path getIntermediaries(LoomGradleExtension extension, String version) {
		File intermediaryNames = new File(extension.getUserCache(), "mappings/" + version + '/' + INTERMEDIARY + "-intermediary.tiny");

		if (!intermediaryNames.exists()) {
			try {
				FileUtils.copyURLToFile(new URL(SpecialCases.intermediaries(version)), intermediaryNames);
			} catch (IOException e) {
				throw new UncheckedIOException("Error downloading Intermediary mappings for " + version, e);
			}
		}

		return intermediaryNames.toPath();
	}

	private static void logErroneousMappings(Logger logger, MappingBlob mappings, MappingBlob fallback) {
		//Sometimes Yarn versions include their own mappings without Intermediary backing (which is bad really)
		Map<String, Pair<String, Map<String, String>>> yarnOnlyMappings = new HashMap<>();

		for (Mapping mapping : mappings) {
			String notch = mapping.from;

			if (!fallback.has(notch)) {
				assert fallback.tryMapName(notch) == null;
				throw new IllegalStateException("Extra class mapping missing from fallback! Unable to find " + notch + " (mapped as " + mapping.to() + ')');
			}
			Mapping other = fallback.getOrDummy(notch); //Won't be a dummy if combined is not null

			for (Method method : mapping.methods()) {
				if (other.hasMethod(method)) continue;
				notch = method.fromName;

				if (notch.charAt(0) != '<' && !notch.equals(method.nameOr(notch))) {
					//Changing Notch names without intermediaries to back it up is not cross-version safe and shouldn't be done
					//throw new IllegalStateException("Extra mappings missing from fallback! Unable to find " + mapping.from + '#' + method.fromName + method.fromDesc + " (" + mapping.to + '#' + method.name() + ')');

					//Yarn sometimes does however, so we'll just the cases where it does and not use them
					yarnOnlyMappings.computeIfAbsent(mapping.from, k -> Pair.of(mapping.to(), new HashMap<>())).getRight().put(method.fromName + method.fromDesc, method.name());
				}
			}

			for (Field field : mapping.fields()) {
				if (other.hasField(field)) continue;

				yarnOnlyMappings.computeIfAbsent(mapping.from, k -> Pair.of(mapping.to(), new HashMap<>())).getRight().put(field.fromDesc + ' ' + field.fromName, field.name());
				//throw new IllegalStateException("Extra mapping missing from fallback! Unable to find " + mapping.from + '#' + field.fromName + " (" + field.fromDesc + ')');
			}
		}

		if (!yarnOnlyMappings.isEmpty() && logger.isWarnEnabled()) {//We should crash from this, but that's a nuisance as Yarn has to get fixed
			logger.warn("Invalid Yarn mappings (ie missing Intermediaries) found:");

			for (Entry<String, Pair<String, Map<String, String>>> entry : yarnOnlyMappings.entrySet()) {
				String notch = entry.getKey();
				String yarn = entry.getValue().getLeft();
				logger.warn("\tIn " + notch + " (" + yarn + ')');

				//Split the methods apart from the fields
				Map<Boolean, List<Entry<String, String>>> extras = entry.getValue().getRight().entrySet().stream().collect(Collectors.partitioningBy(extra -> extra.getKey().contains("(")));

				printExtras(logger, extras.get(Boolean.TRUE), "methods");
				printExtras(logger, extras.get(Boolean.FALSE), "fields");

				logger.warn(""); //Empty line to break up the classes
			}
		}
	}

	private static void printExtras(Logger logger, List<Entry<String, String>> extras, String type) {
		if (!extras.isEmpty()) {
			logger.warn("\t\tExtra mapped " + type + ':');

			for (Entry<String, String> extra : extras) {
				logger.warn("\t\t\t" + extra.getKey() + " => " + extra.getValue());
			}
		}
	}

	private static <T, R> Function<T, R> nullSafe(Function<T, R> test) {
		return thing -> thing != null ? test.apply(thing) : null;
	}

	private String readStackHistory() {
		if (Files.notExists(stackHistory)) {
			return "1";
		} else {
			List<String> expected = mappingFiles.stream().map(mappings -> mappings.name + '-' + mappings.version + ' ' + mappings.minecraftVersion).collect(Collectors.toList());
			assert !expected.isEmpty();

			try (BufferedReader reader = Files.newBufferedReader(stackHistory)) {
				String currentVersion = reader.readLine();
				String newestVersion = currentVersion;
				int currentPosition = 0;
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					assert !line.isEmpty();

					if (line.charAt(0) != '\t') {
						if (currentPosition == expected.size()) {
							assert currentVersion != null;
							knownStack = true;
							return currentVersion;
						}

						currentVersion = line;
						currentPosition = 0;
					} else if (currentVersion != null) {
						assert line.charAt(0) == '\t';

						if (currentPosition >= expected.size() || !line.regionMatches(1, expected.get(currentPosition), 0, line.length() - 1)) {
							currentVersion = null;
						} else {
							currentPosition++;
						}
					}
				}

				return Integer.toString(Integer.parseUnsignedInt(newestVersion) + 1);
			} catch (IOException e) {
				throw new UncheckedIOException("Error reading stack history file at " + stackHistory, e);
			}
		}
	}

	private void writeStackHistory(String version) {
		assert !knownStack; //No need to go through all this if it's already in the history

		StringBuilder addition = new StringBuilder(version).append('\n');

		for (MappingFile mapping : mappingFiles) {
			addition.append('\t').append(mapping.name).append('-').append(mapping.version).append(' ').append(mapping.minecraftVersion).append('\n');
		}

		byte data[] = addition.toString().getBytes(StandardCharsets.UTF_8);
		ByteBuffer active;
		if (data.length >= 4096) {
			active = ByteBuffer.wrap(data);
		} else {
			active = ByteBuffer.allocate(4096);
			active.put(data);
			active.flip();
		}
		assert active.limit() == data.length;

		ByteBuffer passive = ByteBuffer.allocate(active.capacity());

		try (FileChannel channel = FileChannel.open(stackHistory, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
			assert channel.position() == 0;

			if (passive.capacity() != active.capacity()) {//It's important the amount of the file read is the same as the amount written
				throw new IllegalArgumentException("Inconsistent capacities between buffers: " + passive.capacity() + " and " + active.capacity());
			}

			long offset = passive.capacity() - active.limit(); //Account for reading/writing not necessarily being at the same start/end positions
			assert offset >= 0;
			do {
				long position = channel.position();
				if (position > 0 && offset > 0) {
					channel.position(position + offset);
				}

				passive.clear(); //Ensure the buffer's limit == capacity, as well as position == 0

				int read;
				do {
					read = channel.read(passive);
				} while (read != -1 && passive.hasRemaining());
				//System.out.println("Read in \"" + new String(passive.array(), 0, passive.position()) + '"');
				passive.flip();

				channel.position(position);

				//System.out.println("Writing \"" + new String(active.array(), active.position(), active.remaining()) + '"');
				while (active.hasRemaining()) {
					channel.write(active);
				}

				ByteBuffer swap = passive;
				passive = active;
				active = swap;
			} while (active.limit() >= active.capacity());

			while (active.hasRemaining()) {
				//System.out.println("Writing final \"" + new String(active.array(), active.position(), active.remaining()) + '"');
				channel.write(active);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing stack history file to " + stackHistory, e);
		}
	}

	private void initFiles(LoomGradleExtension extension, Logger logger, MinecraftProvider minecraftProvider) {
		MAPPINGS_DIR = new File(extension.getUserCache(), "mappings/" + minecraftProvider.minecraftVersion);

		switch (mappingFiles.size()) {
		case 0:
			logger.lifecycle(":setting up mappings (" + INTERMEDIARY + ' ' + minecraftProvider.minecraftVersion + ')');
			mappingsName = INTERMEDIARY;
			mappingsVersion = minecraftVersion = minecraftProvider.minecraftVersion;
			break;

		case 1: {
			MappingFile mappings = Iterables.getOnlyElement(mappingFiles);
			logger.lifecycle(":setting up mappings (" + mappings.name + ' ' + mappings.version + '@' + mappings.minecraftVersion + ')');
			mappingsName = mappings.name;
			mappingsVersion = mappings.version;
			minecraftVersion = mappings.minecraftVersion;
			break;
		}

		default: {
			logger.lifecycle(":setting up mappings (" + mappingFiles.size() + " files in stack)");
			stackHistory = new File(MAPPINGS_DIR, "stack.history").toPath();

			mappingsName = "stack";
			mappingsVersion = readStackHistory();
			//The stack could be made up of multiple Minecraft versions, so we'll just use the version the stack will run on
			minecraftVersion = minecraftProvider.minecraftVersion;
			break;
		}
		}

		intermediaryNames = new File(MAPPINGS_DIR, INTERMEDIARY + "-intermediary.tiny");
		MAPPINGS_TINY_BASE = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + '-' + mappingsVersion + "-base.tiny");
		MAPPINGS_TINY = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + '-' + mappingsVersion + ".tiny");
		parameterNames = new File(MAPPINGS_DIR, mappingsName + "-params-" + minecraftVersion + '-' + mappingsVersion).toPath();
		decompileComments = parameterNames.resolveSibling(mappingsName + "-tiny-" + minecraftVersion + '-' + mappingsVersion + "-decomp.tiny");

		MAPPINGS_MIXIN_EXPORT = new File(extension.getProjectBuildCache(), "mixin-map-" + minecraftVersion + '-' + mappingsVersion + ".tiny");
	}

	public void clearFiles() {
		MAPPINGS_TINY.delete();
		MAPPINGS_TINY_BASE.delete();
		intermediaryNames.delete();
		try {
			Files.deleteIfExists(parameterNames);
			Files.deleteIfExists(decompileComments);
		} catch (IOException e) {
			e.printStackTrace(); //That's troublesome
		}
	}
}
