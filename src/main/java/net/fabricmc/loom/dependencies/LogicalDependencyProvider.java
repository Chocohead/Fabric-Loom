/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.dependencies;

import java.util.Set;

/**
 * A {@link DependencyProvider} which waits for and runs after other {@link DependencyProvider}s
 *
 * @author Chocohead
 */
public abstract class LogicalDependencyProvider extends DependencyProvider {
	@Override
	protected final String getType() {
		return "logical";
	}

	@Override
	public abstract Set<Class<? extends DependencyProvider>> getDependencies();
}