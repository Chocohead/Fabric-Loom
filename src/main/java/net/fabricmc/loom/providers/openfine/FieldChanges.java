/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.FieldNode;

import com.google.common.collect.Sets;

public class FieldChanges {
	private final List<FieldComparison> modifiedFields = new ArrayList<>();
	private final List<FieldNode> lostFields = new ArrayList<>();
	private final List<FieldNode> gainedFields = new ArrayList<>();

	public FieldChanges(String className, List<FieldNode> original, List<FieldNode> patched) {
		Map<String, FieldNode> originalFields = original.stream().collect(Collectors.toMap(field -> field.name + field.desc, Function.identity()));
		Map<String, FieldNode> patchedFields = patched.stream().collect(Collectors.toMap(field -> field.name + field.desc, Function.identity()));

		for (String fieldName : Sets.union(originalFields.keySet(), patchedFields.keySet())) {
			FieldNode originalField = originalFields.get(fieldName);
			FieldNode patchedField = patchedFields.get(fieldName);

			if (originalField != null) {
				if (patchedField != null) {//Both have the field
					modifiedFields.add(new FieldComparison(originalField, patchedField));
				} else {//Just the original has the field
					lostFields.add(originalField);
				}
			} else if (patchedField != null) {//Just the modified has the field
				gainedFields.add(patchedField);
			} else {//Neither have the field?!
				throw new IllegalStateException("Unable to find " + fieldName + " in either " + className + " versions");
			}
		}

		modifiedFields.sort(Comparator.comparingInt(field -> original.indexOf(field.node)));
		lostFields.sort(Comparator.comparingInt(original::indexOf));
		gainedFields.sort(Comparator.comparingInt(patched::indexOf));
	}

	public void annotate(Annotator annotator) {
		lostFields.stream().map(field -> field.name + '#' + field.desc).forEach(annotator::dropField);
		gainedFields.stream().map(field -> field.name + ";;" + field.desc).forEach(annotator::addField);
		modifiedFields.stream().filter(FieldComparison::hasChanged).collect(Collectors.toMap(comparison -> comparison.node.name + ";;" + comparison.node.desc, FieldComparison::toChangeSet)).forEach(annotator::addChangedField);
	}
}