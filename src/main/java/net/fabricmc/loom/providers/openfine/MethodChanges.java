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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

public class MethodChanges {
	private final String className;
	private final List<MethodComparison> modifiedMethods = new ArrayList<>();
	private final List<MethodNode> lostMethods = new ArrayList<>();
	private final List<MethodNode> gainedMethods = new ArrayList<>();

	public MethodChanges(String className, List<MethodNode> original, List<MethodNode> patched) {
		Map<String, MethodNode> originalMethods = original.stream().collect(Collectors.toMap(method -> method.name + method.desc, Function.identity()));
		Map<String, MethodNode> patchedMethods = patched.stream().collect(Collectors.toMap(method -> method.name + method.desc, Function.identity()));

		for (String methodName : Sets.union(originalMethods.keySet(), patchedMethods.keySet())) {
			MethodNode originalMethod = originalMethods.get(methodName);
			MethodNode patchedMethod = patchedMethods.get(methodName);

			if (originalMethod != null) {
				if (patchedMethod != null) {//Both have the method
					modifiedMethods.add(new MethodComparison(originalMethod, patchedMethod));
				} else {//Just the original has the method
					lostMethods.add(originalMethod);
				}
			} else if (patchedMethod != null) {//Just the modified has the method
				gainedMethods.add(patchedMethod);
			} else {//Neither have the method?!
				throw new IllegalStateException("Unable to find " + methodName + " in either " + className + " versions");
			}
		}

		this.className = className;
		sortModifiedMethods(patched);
		lostMethods.sort(Comparator.comparingInt(original::indexOf));
		gainedMethods.sort(Comparator.comparingInt(patched::indexOf));
	}

	void sortModifiedMethods(List<MethodNode> patched) {
		modifiedMethods.sort(Comparator.comparingInt(method -> !"<clinit>".equals(method.node.name) ? patched.indexOf(method.node) : "com/mojang/blaze3d/platform/GLX".equals(className) ? patched.size() : -1));
	}

	public boolean couldNeedLambdasFixing() {
		return modifiedMethods.stream().anyMatch(method -> !method.equal && method.hasLambdas()) && !lostMethods.isEmpty() && !gainedMethods.isEmpty();
	}

	public boolean tryFixLambdas(Map<String, String> fixes) {//How do you fix lambdas? With dozens of other lambdas of course
		List<MethodNode> gainedLambdas = gainedMethods.stream().filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$")).collect(Collectors.toList());
		if (gainedLambdas.isEmpty()) return true; //Nothing looks like a lambda

		Set<String> possibleLambdas = gainedLambdas.stream().map(method -> className + '#' + method.name + method.desc).collect(Collectors.toSet()); //The collection of lambdas we're looking to fix, any others are irrelevant from the point of view that they're probably fine

		if (gainedLambdas.size() == lostMethods.size()) {
			int[] lambdaDemand = modifiedMethods.stream().mapToLong(comparison -> comparison.getLambads().stream().filter(possibleLambdas::contains).count()).filter(count -> count > 0).mapToInt(Math::toIntExact).toArray();
			Pattern regex = Pattern.compile("lambda\\$(\\w+)\\$(\\d+)");
			int[] lambdaSupply = gainedLambdas.stream().map(method -> regex.matcher(method.name)).filter(Matcher::matches).sorted(Comparator.comparingInt(matcher -> Integer.parseInt(matcher.group(2)))).collect(Collectors.groupingBy(matcher -> matcher.group(1), LinkedHashMap::new, Collectors.counting())).values().stream().mapToInt(Long::intValue).toArray();

			if (Arrays.equals(lambdaDemand, lambdaSupply)) {//The gained lambdas match completely with the lost methods, map directly
				Streams.forEachPair(lostMethods.stream(), gainedLambdas.stream(), (lost, gained) -> addFix(fixes, gained, lost));
				gainedMethods.removeAll(gainedLambdas);
				lostMethods.clear();
				return true; //Nothing more to do
			}
		}

		Map<String, MethodNode> newDescToLambda = gainedLambdas.stream().collect(Collectors.groupingBy(lambda -> lambda.desc)).entrySet().stream().filter(entry -> entry.getValue().size() == 1).collect(Collectors.toMap(Entry::getKey, entry -> Iterables.getOnlyElement(entry.getValue())));
		Map<String, MethodNode> oldDescToMethod = lostMethods.stream().collect(Collectors.groupingBy(lambda -> lambda.desc)).entrySet().stream().filter(entry -> entry.getValue().size() == 1).collect(Collectors.toMap(Entry::getKey, entry -> Iterables.getOnlyElement(entry.getValue())));

		Set<String> commonDescs = Sets.intersection(newDescToLambda.keySet(), oldDescToMethod.keySet()); //Unique descriptions that are found in both the lost methods and gained lambdas
		if (!commonDescs.isEmpty()) {
			boolean complete = modifiedMethods.stream().flatMap(comparison -> comparison.getLambads().stream().filter(possibleLambdas::contains)).allMatch(lambda -> commonDescs.contains(lambda.substring(lambda.indexOf('('))));

			Map<MethodNode, MethodNode> lostToGained = newDescToLambda.entrySet().stream().filter(entry -> oldDescToMethod.containsKey(entry.getKey())).collect(Collectors.toMap(entry -> oldDescToMethod.get(entry.getKey()), Entry::getValue));
			if (lostToGained.containsKey(null)) {//Should find all these
				throw new IllegalStateException("Unable to find lostMethod from " + newDescToLambda.keySet() + " => " + oldDescToMethod.keySet());
			}

			lostToGained.forEach((lost, gained) -> addFix(fixes, gained, lost));
			lostMethods.removeAll(lostToGained.keySet());
			gainedMethods.removeAll(lostToGained.values());

			if (complete) return true; //Caught all the lambdas
			gainedLambdas.retainAll(gainedMethods);
		}

		return false; //Still some lambda-like methods which couldn't be matched up
	}

	private boolean addFix(Map<String, String> fixes, MethodNode from, MethodNode to) {
		if (!from.desc.equals(to.desc)) {
			System.err.println("Description changed remapping lambda handle: " + className + '#' + from.name + from.desc + " => " + className + '#' + to.name + to.desc);
			return false; //Don't add the fix if it is wrong
		}

		fixes.put(className + '#' + from.name + from.desc, className + '#' + to.name + to.desc);

		from.name = to.name; //Apply the rename to the actual method node too
		modifiedMethods.add(new MethodComparison(to, from));
		return true;
	}

	Stream<String> lostMethods() {
		return lostMethods.stream().map(method -> method.name + method.desc);
	}

	Stream<String> gainedLambdas() {
		return gainedMethods.stream().filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$")).map(method -> method.name + method.desc);
	}

	public void refreshChanges(List<MethodNode> original) {
		Map<String, MethodNode> originalMethods = original.stream().collect(Collectors.toMap(method -> method.name + method.desc, Function.identity()));

		for (ListIterator<MethodComparison> it = modifiedMethods.listIterator(); it.hasNext();) {
			MethodComparison comparison = it.next();

			MethodNode originalMethod = originalMethods.get(comparison.node.name + comparison.node.desc);
			if (originalMethod == null) continue;

			it.set(new MethodComparison(originalMethod, comparison.node));
		}
	}

	public void annotate(Annotator annotator) {
		lostMethods.stream().map(method -> method.name.concat(method.desc)).forEach(annotator::dropMethod);
		gainedMethods.stream().map(method -> method.name + method.desc).forEach(annotator::addMethod);
		modifiedMethods.stream().filter(MethodComparison::hasChanged).collect(Collectors.toMap(comparison -> comparison.node.name + comparison.node.desc, MethodComparison::toChangeSet)).forEach(annotator::addChangedMethod);
	}
}