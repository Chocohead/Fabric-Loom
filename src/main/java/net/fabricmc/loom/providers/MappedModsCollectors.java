/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.util.Constants;

public final class MappedModsCollectors {
	private MappedModsCollectors() {
	}

	public static Set<Class<? extends MappedModsCollector>> all() {
		return ImmutableSet.of(ModCompile.class, ModApi.class, ModImplementation.class, ModRuntime.class, ModCompileOnly.class);
	}

	public static void addAll(LoomDependencyManager dependencyManager) {
		dependencyManager.addProvider(new ModCompile());
		dependencyManager.addProvider(new ModApi());
		dependencyManager.addProvider(new ModImplementation());
		dependencyManager.addProvider(new ModRuntime());
		dependencyManager.addProvider(new ModCompileOnly());
	}

	public static class ModCompile extends MappedModsCollector {
		public ModCompile() {
			super(Constants.MOD_COMPILE);
		}
	}

	public static class ModApi extends MappedModsCollector {
		public ModApi() {
			super(Constants.MOD_API);
		}
	}

	public static class ModImplementation extends MappedModsCollector {
		public ModImplementation() {
			super(Constants.MOD_IMPLEMENTATION);
		}
	}

	public static class ModRuntime extends MappedModsCollector {
		public ModRuntime() {
			super(Constants.MOD_RUNTIME);
		}
	}

	public static class ModCompileOnly extends MappedModsCollector {
		public ModCompileOnly() {
			super(Constants.MOD_COMPILE_ONLY);
		}
	}
}