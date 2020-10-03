/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FilenameUtils;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.logging.Logger;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import net.fabricmc.loom.providers.JarNameFactory;
import net.fabricmc.loom.providers.JarNamingStrategy;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MappingsProvider.MappingFactory;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.stitch.util.StitchUtil;
import net.fabricmc.stitch.util.StitchUtil.FileSystemDelegate;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.MemberInstance;

import com.chocohead.optisine.OptiFineRemoved;

public class Openfine {
	public static final String VERSION = "cc6da75";

	public static JarNamingStrategy process(Logger logger, String mcVersion, File client, File server, File optifineJar) throws IOException {
		OptiFineVersion optifine = new OptiFineVersion(optifineJar);
		logger.lifecycle("Loaded OptiFine " + optifine.version + " for " + optifine.minecraftVersion);

		if (!optifine.supports(mcVersion)) {
			throw new InvalidUserDataException("Incompatible OptiFine version, requires " + optifine.minecraftVersion + " rather than " + mcVersion);
		}

		File optiCache = new File(client.getParentFile(), "optifine");
		optiCache.mkdirs();

		if (optifine.isInstaller) {
			File installer = optifineJar;
			optifineJar = new File(optiCache, FilenameUtils.removeExtension(optifineJar.getName()) + "-extract.jar");
			if (!optifineJar.exists()) extract(logger, client, installer, optifineJar);
		}

		JarNamingStrategy out = JarNamingStrategy.forVersion(mcVersion + '-' + optifine.version);

		File merged = new File(optiCache, JarNameFactory.CLIENT.getJarName(out));
		if (!merged.exists()) merge(logger, client, optifineJar, server, merged);

		return out;
	}

	private static void extract(Logger logger, File minecraft, File installer, File to) throws IOException {
		logger.info("Extracting OptiFine into " + to);

		try (URLClassLoader classLoader = new URLClassLoader(new URL[] {installer.toURI().toURL()}, Openfine.class.getClassLoader())) {
			Class<?> clazz = classLoader.loadClass("optifine.Patcher");
			Method method = clazz.getDeclaredMethod("process", File.class, File.class, File.class);
			method.invoke(null, minecraft, installer, to);
		} catch (MalformedURLException e) {
			throw new RuntimeException("Unable to use OptiFine jar at " + installer.getAbsolutePath(), e);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Error running OptiFine installer", e);
		}

		System.gc(); //The OptiFine installer leaks a ZipFile of the client jar, try to clean the reference back up
	}

	private static void merge(Logger logger, File client, File optifine, File server, File to) throws IOException {
		logger.info("Merging OptiFine into " + to);

		Set<String> mcEntries, optifineEntries, intersection;
		try (JarFile mcJar = new JarFile(client); JarFile optifineJar = new JarFile(optifine)) {
			//Comparison on ZipEntries is poorly defined so we'll use the entry names for equality
			mcEntries = ImmutableSet.copyOf(Iterators.transform(Iterators.forEnumeration(mcJar.entries()), JarEntry::getName));
			optifineEntries = ImmutableSet.copyOf(Iterators.filter(Iterators.transform(Iterators.forEnumeration(optifineJar.entries()), JarEntry::getName), name -> !name.startsWith("srg/")));

			if (mcEntries.size() > optifineEntries.size()) {
				intersection = Sets.intersection(optifineEntries, mcEntries);
			} else {
				intersection = Sets.intersection(mcEntries, optifineEntries);
			}
		}

		try (FileSystemDelegate mcFS = StitchUtil.getJarFileSystem(client, false);
				FileSystemDelegate ofFS = StitchUtil.getJarFileSystem(optifine, false);
				FileSystemDelegate serverFS = server.exists() ? StitchUtil.getJarFileSystem(server, false) : null;
				FileSystemDelegate outputFS = StitchUtil.getJarFileSystem(to, true)) {
			for (String entry : Sets.difference(mcEntries, optifineEntries)) {
				copy(mcFS.get(), outputFS.get(), entry);
			}

			for (String entry : Sets.difference(optifineEntries, mcEntries)) {
				copy(ofFS.get(), outputFS.get(), entry);
			}

			for (String entry : intersection) {
				if (entry.endsWith(".class")) {
					Path pathRawIn = mcFS.get().getPath(entry);
					Path pathPatchedIn = ofFS.get().getPath(entry);

					Path pathOut = outputFS.get().getPath(entry);
			        if (pathOut.getParent() != null) {
			            Files.createDirectories(pathOut.getParent());
			        }

			        byte[] stitchFix;
			        if (serverFS != null) {
			        	Path pathStichFix = serverFS.get().getPath(entry);
				        stitchFix = Files.isReadable(pathStichFix) ? Files.readAllBytes(pathStichFix) : null;
			        } else {
			        	stitchFix = null;
			        }

			        logger.info("Reconstructing " + entry);
			        byte[] data = ClassReconstructor.reconstruct(logger, Files.readAllBytes(pathRawIn), Files.readAllBytes(pathPatchedIn), stitchFix);

			        //BasicFileAttributes touchTime = Files.readAttributes(pathIn, BasicFileAttributes.class);
			        Files.write(pathOut, data, StandardOpenOption.CREATE_NEW);
			        //Files.getFileAttributeView(pathIn, BasicFileAttributeView.class).setTimes(touchTime.lastModifiedTime(), touchTime.lastAccessTime(), touchTime.creationTime());
				} else if (entry.startsWith("META-INF/")) {
					copy(mcFS.get(), outputFS.get(), entry);
				} else {
					copy(ofFS.get(), outputFS.get(), entry);
				}
			}
		} catch (IllegalStateException e) {
			//If an ISE is thrown something has clearly gone wrong with the merging of the jars, thus we don't want to keep the corrupted output
			if (!to.delete()) to.deleteOnExit();
			throw e;
		}
	}

	private static void copy(FileSystem fsIn, FileSystem fsOut, String entry) throws IOException {
		Path pathIn = fsIn.getPath(entry);

		Path pathOut = fsOut.getPath(entry);
        if (pathOut.getParent() != null) {
            Files.createDirectories(pathOut.getParent());
        }

        BasicFileAttributes touchTime = Files.readAttributes(pathIn, BasicFileAttributes.class);
        Files.copy(pathIn, pathOut);
        Files.getFileAttributeView(pathIn, BasicFileAttributeView.class).setTimes(touchTime.lastModifiedTime(), touchTime.lastAccessTime(), touchTime.creationTime());
	}

	public static void applyBonusMappings(MappingsProvider mappingsProvider) throws IOException {
		List<FieldEntry> extra = new ArrayList<>();

		for (FieldEntry field : mappingsProvider.getMappings().getFieldEntries()) {
			String interName = field.get("intermediary").getName();

			switch (interName) {
			case "field_1937": //Option#CLOUDS
				extra.add(namespace -> {
					EntryTriple real = field.get(namespace);
					return new EntryTriple(real.getOwner(), "official".equals(namespace) ? "CLOUDS" : "CLOUDS_OF", real.getDesc());
				});
				break;

			case "field_4062": //WorldRenderer#renderDistance
				extra.add(namespace -> {
					EntryTriple real = field.get(namespace);
					return new EntryTriple(real.getOwner(), "official".equals(namespace) ? "renderDistance" : "renderDistance_OF", real.getDesc());
				});
				break;
			}
		}

		mappingsProvider.mcRemappingFactory = new MappingFactory() {
			private final MappingFactory factory = mappingsProvider.mcRemappingFactory;

			@Override
			public IMappingProvider create(String fromMapping, String toMapping) throws IOException {
				return new IMappingProvider() {
					private final IMappingProvider wrapped = factory.create(fromMapping, toMapping);

					private void addExtras(Map<String, String> fields) {
						for (FieldEntry field : extra) {
							TinyRemapperMappingsHelper.add(field, fromMapping, toMapping, fields);
						}
					}

					@Override
					public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap) {
						wrapped.load(classMap, fieldMap, methodMap, localMap);
						addExtras(fieldMap);
					}

					@Override
					public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap) {
						wrapped.load(classMap, fieldMap, methodMap);
						addExtras(fieldMap);
					}

					@Override
					public String suggestLocalName(String type, boolean plural) {
						return wrapped.suggestLocalName(type, plural);
					}
				};
			}
		};
	}

	public static void transformRemovals(Logger logger, MappingsProvider mappingsProvider, File namedJar) throws IOException {
		Map<String, String> notchToNamed = new HashMap<>();
		mappingsProvider.mcRemappingFactory.create("official", "named").load(notchToNamed, notchToNamed, notchToNamed);

		Map<String, String> namedToNotch = new HashMap<>();
		mappingsProvider.mcRemappingFactory.create("named", "official").load(namedToNotch, new EmptyMap<>(), new EmptyMap<>());

		ZipEntryTransformerEntry[] transforms;
		try (ZipFile jar = new ZipFile(namedJar)) {
			transforms = Streams.stream(Iterators.forEnumeration(jar.entries())).map(ZipEntry::getName).filter(name -> name.endsWith(".class")).distinct().map(className -> {
				return new ZipEntryTransformerEntry(className, new ByteArrayZipEntryTransformer() {
					private ClassVisitor makeVisitor(ClassVisitor parent) {
						return new ClassVisitor(Opcodes.ASM7, parent) {
							private final String removedDescriptor = Type.getDescriptor(OptiFineRemoved.class);
							private String className;

							@Override
							public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
								super.visit(version, access, className = name, signature, superName, interfaces);
							}

							private AnnotationVisitor makeVisitor(String descriptor, AnnotationVisitor parent) {
								if (removedDescriptor.equals(descriptor)) {
									return new AnnotationVisitor(Opcodes.ASM7, parent) {
										private OptiFineRemoved.Type removalType;

										@Override
										public void visitEnum(String name, String descriptor, String value) {
											if ("type".equals(name)) {
												assert Type.getDescriptor(OptiFineRemoved.Type.class).equals(descriptor);
												removalType = OptiFineRemoved.Type.valueOf(value);
												logger.debug("Passing removal of " + removalType + " in " + className);
											}

											super.visitEnum(name, descriptor, value);
										}

										private Type remapType(Type type) {
											switch (type.getSort()) {
											case Type.ARRAY:
												return Type.getObjectType(Strings.repeat("[", type.getDimensions()).concat(remapType(type.getElementType()).getDescriptor()));

											case Type.OBJECT:
												return Type.getObjectType(notchToNamed.getOrDefault(type.getInternalName(), type.getInternalName()));

											default:
												return type;
											}
										}

										private String memberReplace(String member, boolean method) {
											int split = member.lastIndexOf(method ? '(' : '#');
											String name = member.substring(0, split);
											String desc = member.substring(method ? split : split + 1);

											String clazz = namedToNotch.get(className);
											if (clazz != null) {
												String remap = clazz + '/' + (method ? MemberInstance.getMethodId(name, desc) : MemberInstance.getFieldId(name, desc, false));
												name = notchToNamed.getOrDefault(remap, name);
											} else {
												logger.warn("Unable to find backwards mapping for ".concat(className));
											}

											Type descType = Type.getType(desc);
											if (method) {
												desc = Arrays.stream(descType.getArgumentTypes()).map(this::remapType).map(Type::getClassName).collect(Collectors.joining(", "));
												return remapType(descType.getReturnType()).getClassName() + ' ' + name + '(' + desc + ')';
											} else {
												desc = remapType(descType).getClassName();
												return desc + ' ' + name;
											}
										}

										@Override
										public void visit(String name, Object value) {
											if ("name".equals(name)) {
												if (removalType == null) {
													throw new IllegalStateException("Found annotation methods in unexpected order in ".concat(className));
												}

												switch (removalType) {
												case INTERFACE:
													value = notchToNamed.getOrDefault(value, (String) value);
													break;

												case METHOD: {
													value = memberReplace((String) value, true);
													break;
												}

												case FIELD:
													value = memberReplace((String) value, false);
													break;
												}
											}

											super.visit(name, value);
										}
									};
								} else {
									return new AnnotationVisitor(Opcodes.ASM7, parent) {
										@Override
										public AnnotationVisitor visitAnnotation(String name, String descriptor) {
											return makeVisitor(descriptor, super.visitAnnotation(name, descriptor));
										}

										@Override
										public AnnotationVisitor visitArray(String name) {
											return makeVisitor(null, super.visitArray(name));
										}
									};
								}
							}

							@Override
							public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
								return makeVisitor(descriptor, super.visitAnnotation(descriptor, visible));
							}
						};
					}

					@Override
					protected byte[] transform(ZipEntry zipEntry, byte[] input) throws IOException {
						logger.debug("Rebuilding " + className.substring(0, className.length() - 6));

						ClassWriter classWriter = new ClassWriter(0);
						new ClassReader(input).accept(makeVisitor(classWriter), 0);
						return classWriter.toByteArray();
					}

					@Override
					protected boolean preserveTimestamps() {
						return true; //Why not?
					}
				});
			}).toArray(ZipEntryTransformerEntry[]::new);
		}

		ZipUtil.transformEntries(namedJar, transforms);
	}
}