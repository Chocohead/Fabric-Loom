/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.gradle.api.logging.Logger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class ClassReconstructor {
	public static byte[] reconstruct(Logger logger, byte[] original, byte[] modified, byte[] server) {
		ClassNode originalClass = read(original);
		ClassNode patchedClass = read(modified);

		assert Objects.equals(originalClass.name, patchedClass.name);
		assert Objects.equals(originalClass.superName, patchedClass.superName);
		assert Objects.equals(originalClass.signature, patchedClass.signature);

		AccessChange accessChange = AccessChange.forAccess(originalClass.access, patchedClass.access);
		FinalityChange finalityChange = FinalityChange.forAccess(originalClass.access, patchedClass.access);

		Set<String> gainedInterfaces, lostInterfaces;
		if (!Objects.equals(originalClass.interfaces, patchedClass.interfaces)) {
			Set<String> originalInterfaces = ImmutableSet.copyOf(originalClass.interfaces);
			Set<String> patchedInterfaces = ImmutableSet.copyOf(patchedClass.interfaces);

			gainedInterfaces = Sets.difference(patchedInterfaces, originalInterfaces);
			lostInterfaces = Sets.difference(originalInterfaces, patchedInterfaces);
		} else {
			gainedInterfaces = lostInterfaces = Collections.emptySet();
		}

		assert Objects.equals(originalClass.outerClass, patchedClass.outerClass);
		assert Objects.equals(originalClass.outerMethod, patchedClass.outerMethod);
		assert Objects.equals(originalClass.outerMethodDesc, patchedClass.outerMethodDesc);

		assert Objects.equals(originalClass.nestHostClass, patchedClass.nestHostClass);
		assert Objects.equals(originalClass.nestMembers, patchedClass.nestMembers);

		Annotator annotator = new Annotator(accessChange, finalityChange, gainedInterfaces, lostInterfaces);

		MethodChanges methodChanges = new MethodChanges(originalClass.name, originalClass.methods, patchedClass.methods);
		if (methodChanges.couldNeedLambdasFixing()) {
			Map<String, String> lambdaFixes = new HashMap<>();

			if (!methodChanges.tryFixLambdas(lambdaFixes)) {
				//If we persistently can't directly match up the lost and gained lambda like methods, nor match by description, more creative solutions may be needed
				logger.debug("Unable to resolve " + methodChanges.gainedLambdas().count() + " lambda(s) in " + originalClass.name + ':');
				logger.debug(methodChanges.lostMethods().collect(Collectors.joining(", ", "\tLost:   [", "]")));
				logger.debug(methodChanges.gainedLambdas().collect(Collectors.joining(", ", "\tGained: [", "]")));
				logger.debug("\tDespite finding " + lambdaFixes);
			}

			if (!lambdaFixes.isEmpty()) fixLambdas(logger, lambdaFixes, originalClass.methods, patchedClass.methods, methodChanges);
		}
		methodChanges.annotate(annotator);

		FieldChanges fieldChanges = new FieldChanges(originalClass.name, originalClass.fields, patchedClass.fields);
		fieldChanges.annotate(annotator);

		annotator.apply(patchedClass);
		stitchAnnotationFix(logger, patchedClass);
		rebuildOrder(logger, patchedClass, server);
		return write(patchedClass);
	}

	private static ClassNode read(byte[] data) {
		ClassNode node = new ClassNode();
		new ClassReader(data).accept(node, 0/*ClassReader.EXPAND_FRAMES*/);
		return node;
	}

	private static void fixLambdas(Logger logger, Map<String, String> fixes, List<MethodNode> originalMethods, List<MethodNode> patchedMethods, MethodChanges changes) {
		changes.sortModifiedMethods(patchedMethods);
		patchedMethods.forEach(method -> MethodComparison.findLambdas(method.instructions, 0, idin -> {
			Handle handle = (Handle) idin.bsmArgs[1];
			String remap = fixes.get(handle.getOwner() + '#' + handle.getName() + handle.getDesc());

			if (remap != null) {
				int split = remap.indexOf('#');
				String owner = remap.substring(0, split);
				remap = remap.substring(split + 1);

				split = remap.indexOf('(');
				String name = remap.substring(0, split);
				String desc = remap.substring(split);

				idin.bsmArgs[1] = new Handle(handle.getTag(), owner, name, desc, handle.isInterface());

				if (!desc.equals(handle.getDesc())) {//Whilst the methods aren't really equal if the descriptions are different, it's better than losing them completely
					logger.warn("Description changed remapping lambda handle: " + handle + " => " + idin.bsmArgs[1]);
				}
			}
		}));
		changes.refreshChanges(originalMethods);
	}

	private static void stitchAnnotationFix(Logger logger, ClassNode node) {
		int syntheticOffset = 0;
		String syntheticArgs = null;

		if ((node.access & Opcodes.ACC_ENUM) != 0) {
			syntheticOffset = 2;
			syntheticArgs = "(Ljava/lang/String;I";
		} else {//SyntheticParameterClassVisitor is expecting classes that have been through ProGuard, the patched class won't have been
			for (InnerClassNode innerClass : node.innerClasses) {
				if (innerClass.name.equals(node.name) && innerClass.innerName != null && innerClass.outerName != null && (innerClass.access & Opcodes.ACC_STATIC) == 0) {
					syntheticOffset = 1;
					syntheticArgs = "(L" + innerClass.outerName + ';';
					break;
				}
			}
		}

		if (syntheticOffset != 0) {
			logger.info("Fixing " + node.name + " to be offset of " + syntheticOffset);

			for (MethodNode method : node.methods) {
				if ("<init>".equals(method.name) && method.desc.startsWith(syntheticArgs)) {
					method.visibleAnnotableParameterCount += syntheticOffset;

					if (method.visibleParameterAnnotations != null) {
						for (int end = method.visibleParameterAnnotations.length, i = end - syntheticOffset; i < end; i++) {
							if (method.visibleParameterAnnotations[i] != null) {
								logger.warn("In " + node.name + '#' + method.name + method.desc);

								for (AnnotationNode annotation : method.visibleParameterAnnotations[i]) {
									logger.warn("\tLost annotation of type ".concat(annotation.desc));
								}
							}
						}

						System.arraycopy(method.visibleParameterAnnotations, 0, method.visibleParameterAnnotations, syntheticOffset, method.visibleParameterAnnotations.length - syntheticOffset);
						Arrays.fill(method.visibleParameterAnnotations, 0, syntheticOffset, null);
					}
				}
			}
		}
	}

	private static void rebuildOrder(Logger logger, ClassNode node, byte[] basis) {
		if (basis == null) return; //Nothing to rebuild from

		ClassNode basisNode = new ClassNode();
		new ClassReader(basis).accept(basisNode, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

		fix(logger, node.name, "inner class", node.innerClasses, basisNode.innerClasses, innerClass -> innerClass.name);
		fix(logger, node.name, "method", node.methods, basisNode.methods, method -> method.name + method.desc);
	}

	private static class Swap {
		public final int a, b;

		public Swap(int a, int b) {
			this.a = a;
			this.b = b;
		}

		@Override
		public String toString() {
			return "Swap[" + a + " <=> " + b + ']';
		}
	}

	private static <T> void fix(Logger logger, String name, String type, List<T> client, List<T> server, Function<T, String> mapper) {
		List<Swap> swaps = fix(logger, name, type, client.stream().map(mapper).collect(Collectors.toList()), server.stream().map(mapper).collect(Collectors.toList()));

		for (Swap swap : swaps) {
			Collections.swap(client, swap.a, swap.b);
		}

		if (!swaps.isEmpty()) logger.info("Resolved " + type + " ordering issues in " + name);
	}

	private static List<Swap> fix(Logger logger, String name, String type, List<String> client, List<String> server) {
		List<Swap> swaps = new ArrayList<>();

		boolean attemptedFix = false;
		for (int c = 0, s = 0; c < client.size() && s < server.size();) {//StitchUtil#mergePreserveOrder can deadlock under certain orderings, let's try not let it
			boolean madeShift = false;

			while (c < client.size() && s < server.size() && client.get(c).equals(server.get(s))) {
				c++;
				s++;
				madeShift = true;
			}

			while (c < client.size() && !server.contains(client.get(c))) {
				c++;
				madeShift = true;
			}

			while (s < server.size() && !client.contains(server.get(s))) {
				s++;
				madeShift = true;
			}

			if (!madeShift) {//Will deadlock in Stitch
				if (attemptedFix) {
					throw new IllegalStateException("Unable to fix " + type + " inconsistencies in " + name);
				} else {
					logger.info(Character.toUpperCase(type.charAt(0)) + type.substring(1) + " deadlock found in " + name + " at " + c + ", " + s);

					int serverSwap = client.indexOf(server.get(s));
					if (serverSwap <= c) throw new AssertionError("Client has fallen behind server in " + name + '?');

					Collections.swap(client, c, serverSwap);
					swaps.add(new Swap(c, serverSwap));
					attemptedFix = true;
				}
			} else if (attemptedFix) {//Fixed the problem
				attemptedFix = false;
			}
		}

		return swaps;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | 0/*ClassWriter.COMPUTE_FRAMES*/);
		node.accept(writer);
		return writer.toByteArray();
	}
}