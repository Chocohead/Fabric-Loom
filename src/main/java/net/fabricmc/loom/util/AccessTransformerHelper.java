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
package net.fabricmc.loom.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.IOUtils;

import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.AbstractCopyTask;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.StringZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class AccessTransformerHelper {
	private interface ClassProcessor {//Consumer<String> which throws an IOException
		void accept(String name) throws IOException;
	}
	private interface MethodProcessor {//BiConsumer<String, String> which throws an IOException
		void accept(String className, String method) throws IOException;
	}
	private static final String MAGIC_AT_NAME = "silky.at";
	private static final String MAGICALLY_BAD_AT_NAME = "silky.aw";
	private static final String BAD_AT_NAME = "accessWidener";

	public static void copyInAT(LoomGradleExtension extension, AbstractCopyTask task) {
		if (extension.hasAT()) {
			String atName = extension.getAT().getName();
			task.from(extension.getAT(), spec -> {
				spec.rename(oldName -> {
					if (!atName.equals(oldName)) {
						//We only expect to include the AT, not anything extra
						throw new IllegalArgumentException("Unexpected file name: " + oldName + " (expected " + atName + ')');
					}
					return MAGIC_AT_NAME;
				});
			});
		}
	}

	public static boolean obfATs(LoomGradleExtension extension, Task task, TinyRemapper tiny, OutputConsumerPath consumer) throws IOException {
		if (extension.hasAT()) {
			File at = new File(task.getTemporaryDir(), MAGIC_AT_NAME);

			try (BufferedWriter writer = new BufferedWriter(new FileWriter(at))) {
				writer.write("#Remapped from " + extension.getAT().getName());
				writer.newLine();

				readATs(new FileReader(extension.getAT()), writer, tiny.getRemapper());
			}

			consumer.addNonClassFile(at.toPath(), at.getName()); //Add at to the root of the obf'd jar
			return true;
		} else {
			return false;
		}
	}

	public static boolean convertATs(LoomGradleExtension extension, Task task, TinyRemapper tiny, OutputConsumerPath consumer) throws IOException {
		if (extension.hasAT()) {
			File at = new File(task.getTemporaryDir(), MAGICALLY_BAD_AT_NAME);

			try (BufferedWriter writer = new BufferedWriter(new FileWriter(at))) {
				writer.write(BAD_AT_NAME + "\tv1\tintermediary");
				writer.newLine();
				writer.write("#Remapped from " + extension.getAT().getName());
				writer.newLine();

				Remapper remapper = tiny.getRemapper();
				readATs(new FileReader(extension.getAT()), name -> {
					writer.write("extendable\tclass\t");
					writer.write(remapper.map(name));
					writer.newLine();
				}, (className, method) -> {
					writer.write("extendable\tmethod\t");
					writer.write(remapper.map(className));
					writer.write('\t');
					int split = method.indexOf('(');
					String name = method.substring(0, split);
					String desc = method.substring(split);
					writer.write(remapper.mapMethodName(className, name, desc));
					writer.write('\t');
					writer.write(remapper.mapMethodDesc(desc));
					writer.newLine();
				});
			}

			consumer.addNonClassFile(at.toPath(), at.getName()); //Add at to the root of the obf'd jar
			return true;
		} else {
			return false;
		}
	}

	public static boolean noteConversion(Logger logger, File modJar) {
		return ZipUtil.transformEntry(modJar, new ZipEntryTransformerEntry("fabric.mod.json", new StringZipEntryTransformer() {
			@Override
			protected String transform(ZipEntry zipEntry, String input) throws IOException {
				JsonObject json = NestedJars.GSON.fromJson(input, JsonObject.class);

				if (!json.has(BAD_AT_NAME)) {
					json.addProperty(BAD_AT_NAME, MAGICALLY_BAD_AT_NAME);
				} else {
					logger.warn("Already have AW in " + modJar + ": " + json.get(BAD_AT_NAME));
				}

				return NestedJars.GSON.toJson(json);
			}
		}));
	}

	public static boolean deobfATs(File jar, TinyRemapper tiny, OutputConsumerPath output) throws IOException {
		Path temp = Files.createTempDirectory("fabric-loom");

		try (ZipFile zip = new ZipFile(jar)) {
			boolean hasWritten = false;
			ZipEntry entry = zip.getEntry(MAGIC_AT_NAME);

			if (entry != null) {
				Path at = temp.resolve(MAGIC_AT_NAME);

				try (Reader in = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8);
						BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(at), StandardCharsets.UTF_8))) {
					readATs(in, writer, tiny.getRemapper());
				}

				output.addNonClassFile(at, MAGIC_AT_NAME);
				hasWritten = true;
			}

			entry = zip.getEntry("fabric.mod.json");

			off: if (entry != null) {
				try (Reader in = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
					JsonElement json = JsonParser.parseReader(in);

					if (!json.isJsonObject() || !json.getAsJsonObject().has(BAD_AT_NAME) || (entry = zip.getEntry(json.getAsJsonObject().get(BAD_AT_NAME).getAsString())) == null) {
						break off;
					}
				}

				Path aw = temp.resolve(entry.getName());
				Files.createDirectories(aw.getParent());

				out: try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8));
						OutputStream out = Files.newOutputStream(aw);
						BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
					reader.mark(2048); //It should only need to read the expected header
					String[] header = reader.readLine().split("\\s+");

					if (header.length != 3 || !BAD_AT_NAME.equals(header[0])) {
						throw new UnsupportedOperationException("Invalid access access widener header " + header + " in " + jar);
					}

					if (!"v1".equals(header[1])) {
						throw new RuntimeException("Unsupported access widener format " + header[1] + " in " + jar);
					}

					switch (header[2]) {
					case "named":
						reader.reset();
						IOUtils.copy(reader, out, StandardCharsets.UTF_8);
						break out; //Probably nothing to do

					case "intermediary":
						writer.write(BAD_AT_NAME + "\tv1\tnamed");
						writer.newLine();
						break;

					default:
						throw new IllegalArgumentException("Unexpected access widener namespace: " + header[2] + " in " + jar);
					}

					Remapper remapper = tiny.getRemapper();
					for (String line = reader.readLine(); line != null; line = reader.readLine()) {
						int split = line.indexOf('#');
						if (split >= 0) line = line.substring(0, split);

						line = line.trim(); //Clip off whitespace
						if (line.isEmpty()) continue;

						String[] parts = line.split("\\s+");
						switch (parts[1]) {
						case "class":
							if (parts.length != 3) {
								throw new RuntimeException("Expected (<access>\tclass\t<className>) got " + line + " in " + jar);
							}

							writer.write(parts[0]);
							writer.write("\tclass\t");
							writer.write(remapper.map(parts[2]));
							writer.newLine();
							break;

						case "field":
							if (parts.length != 5) {
								throw new RuntimeException("Expected (<access>\tfield\t<className>\t<fieldName>\t<fieldDesc>) got " + line + " in " + jar);
							}

							writer.write(parts[0]);
							writer.write("\tfield\t");
							writer.write(remapper.map(parts[2]));
							writer.write('\t');
							writer.write(remapper.mapFieldName(parts[2], parts[3], parts[4]));
							writer.write('\t');
							writer.write(remapper.mapDesc(parts[4]));
							writer.newLine();
							break;

						case "method":
							if (parts.length != 5) {
								throw new RuntimeException("Expected (<access>\tmethod\t<className>\t<methodName>\t<methodDesc>) got " + line + " in " + jar);
							}

							writer.write(parts[0]);
							writer.write("\tmethod\t");
							writer.write(remapper.map(parts[2]));
							writer.write('\t');
							writer.write(remapper.mapMethodName(parts[2], parts[3], parts[4]));
							writer.write('\t');
							writer.write(remapper.mapMethodDesc(parts[4]));
							writer.newLine();
							break;

						default:
							throw new UnsupportedOperationException("Unsupported type " + parts[1] + " on line " + line);
						}
					}
				}

				output.addNonClassFile(aw, entry.getName());
				hasWritten = true;
			}

			return hasWritten;
		} finally {
			Files.walkFileTree(temp, new DeletingFileVisitor());
		}
	}

	private static void readATs(Reader from, BufferedWriter to, Remapper remapper) throws IOException {
		readATs(from, name -> {
			to.write(remapper.map(name));
			to.newLine();
		}, (className, method) -> {
			to.write(remapper.map(className));
			to.write(' ');
			int split = method.indexOf('(');
			String name = method.substring(0, split);
			String desc = method.substring(split);
			to.write(remapper.mapMethodName(className, name, desc));
			to.write(remapper.mapMethodDesc(desc));
			to.newLine();
		});
	}

	public static Set<Pair<String, String>> loadATs(File from) throws IOException {
		Set<Pair<String, String>> targets = new HashSet<>();

		readATs(new FileReader(from), name -> {
			targets.add(Pair.of(name, null));
		}, (className, method) -> {
			targets.add(Pair.of(className, method));
		});

		return targets;
	}

	private static void readATs(Reader from, ClassProcessor rawClassEater, MethodProcessor methodEater) throws IOException {
		try (BufferedReader reader = new BufferedReader(from)) {
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				line = line.trim(); //Clip off whitespace
				if (line.isEmpty() || line.startsWith("#")) continue;

				int split = line.indexOf(' ');
				if (split > 0) {
					methodEater.accept(line.substring(0, split++), line.substring(split));
				} else {
					rawClassEater.accept(line);
				}
			}
		}
	}

	private static class ZipAT extends ByteArrayZipEntryTransformer {
		/** The class name of the type we're aiming to transform */
		public final String className;
		/** Whether to transform methods to public (as traditionally) or protected (as idiomatic) */
		private final boolean traditional;
		/** A set of all methods we're aiming to transform in {@link #className} */
		private final Set<String> transforms;
		/** Whether to transform the access of {@link #className} itself */
		private final boolean selfAT;
		/** A set of all inner classes that need to be transformed */
		private final Set<String> innerTransforms = new HashSet<>();
		/** Whether we have been used (ie {@link #transform(ZipEntry, byte[])} has been called) */
		boolean hasTransformed = false;

		ZipAT(boolean traditional, Entry<String, Set<String>> entry, String wildcard) {
			this(traditional, entry.getKey(), entry.getValue(), wildcard);
		}

		ZipAT(boolean traditional, String className, Set<String> transforms, String wildcard) {
			this.className = className;
			this.traditional = traditional;
			this.transforms = transforms;

			if (selfAT = transforms.remove(wildcard)) {
				//Remember to do the inner class attribute too (if there is one)
				innerTransforms.add(className);
			}
		}

		public boolean changesOwnAccess() {
			return selfAT;
		}

		void addInnerTransform(Set<String> name) {
			innerTransforms.addAll(name);
		}

		@Override
		protected boolean preserveTimestamps() {
			return true;
		}

		@Override
		protected byte[] transform(ZipEntry zipEntry, byte[] data) throws IOException {
			if (hasTransformed) throw new IllegalStateException("Transformer for " + className + " was attempted to be reused");
			hasTransformed = true; //We only expect to be run once (although aren't technically limited to prevent it)

			ClassReader reader = new ClassReader(data);
			ClassWriter writer = new ClassWriter(reader, 0);

			Set<String> expectedTransforms = new HashSet<>(transforms);
			reader.accept(new ClassVisitor(Opcodes.ASM7, writer) {
				private int flipBits(int access, int to) {
					access &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
					access |= to;
					access &= ~Opcodes.ACC_FINAL;
					return access;
				}

				@Override
				public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
					super.visit(version, selfAT ? flipBits(access, Opcodes.ACC_PUBLIC) : access, name, signature, superName, interfaces);
				}

				@Override
				public void visitInnerClass(String name, String outerName, String innerName, int access) {
					super.visitInnerClass(name, outerName, innerName, innerTransforms.contains(name) ? flipBits(access, Opcodes.ACC_PUBLIC) : access);
				}

				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					if (!transforms.isEmpty()) {
						return new MethodVisitor(api, super.visitMethod(expectedTransforms.remove(name.concat(descriptor)) ?
																					flipBits(access, traditional ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED) : access, name, descriptor, signature, exceptions)) {
							@Override
							public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
								super.visitMethodInsn(opcode == Opcodes.INVOKESPECIAL && !"<init>".equals(name) && transforms.contains(name.concat(descriptor)) ?
														Opcodes.INVOKEVIRTUAL : opcode, owner, name, descriptor, isInterface);
							}
						};
					} else {
						return super.visitMethod(access, name, descriptor, signature, exceptions);
					}
				}
			}, 0);
			if (!expectedTransforms.isEmpty()) {//There's still more we never found, not so good that
				throw new IllegalStateException("Ran through class " + className + " but couldn't find " + expectedTransforms);
			}

			return writer.toByteArray();
		}
	}

	public static class ZipEntryAT extends ZipEntryTransformerEntry {
		public ZipEntryAT(ZipAT transformer) {
			super(transformer.className + ".class", transformer);
		}

		/** Whether the transformer for this entry has been applied */
		public boolean didTransform() {
			return ((ZipAT) getTransformer()).hasTransformed;
		}
	}

	public static ZipEntryAT[] makeZipATs(Set<String> classPool, Map<String, Set<String>> transforms, String wildcard, boolean traditional) {
		Map<String, ZipAT> transformers = transforms.entrySet().stream().collect(Collectors.toMap(Entry::getKey, entry -> new ZipAT(traditional, entry, wildcard)));

		Set<String> classChanges = transformers.entrySet().stream().filter(entry -> entry.getValue().changesOwnAccess()).map(Entry::getKey).collect(Collectors.toSet());
		if (!classChanges.isEmpty()) {
			Map<String, Set<String>> rootClasses = new HashMap<>();

			for (String className : classChanges) {
				int split = className.indexOf('$');
				if (split > 0) {
					//If an access change happens to an inner class we'll have to muck about with inner attributes
					rootClasses.computeIfAbsent(className.substring(0, split), k -> new HashSet<>()).add(className);
				}
			}

			if (!rootClasses.isEmpty()) {
				for (Entry<String, Set<String>> rootEntry : rootClasses.entrySet()) {
					String rootClass = rootEntry.getKey();

					//Find "all" nested classes to update the access flags
					for (String pool : classPool) {
						if (pool.startsWith(rootClass)) {
							if (transformers.containsKey(pool)) {
								transformers.get(pool).addInnerTransform(rootEntry.getValue());
							} else {
								ZipAT transformer = new ZipAT(traditional, pool, Collections.emptySet(), null);
								transformers.put(pool, transformer);
								transformer.addInnerTransform(rootEntry.getValue());
							}
						}
					}
				}
			}
		}

		return transformers.values().stream().map(ZipEntryAT::new).toArray(ZipEntryAT[]::new);
	}
}