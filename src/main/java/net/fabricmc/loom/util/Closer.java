/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.util;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Stack;

/** Like {@link com.google.common.io.Closer Guava's Closer} but closes the resources as a {@link Queue} rather than as a {@link Stack} */
public final class Closer implements AutoCloseable {
	private final Queue<AutoCloseable> resources;

	public static Closer create() {
		return new Closer();
	}

	public static Closer create(int expectedSize) {
		return new Closer(expectedSize);
	}

	private Closer() {
		resources = new ArrayDeque<>();
	}

	private Closer(int expectedSize) {
		resources = new ArrayDeque<>(expectedSize);
	}

	public <C extends AutoCloseable> C register(C closeable) {
		if (closeable != null) {
			resources.add(closeable);
		}

		return closeable;
	}

	@Override
	public void close() throws Exception {
		Exception thrown = null;

		while (!resources.isEmpty()) {
			try {
				resources.remove().close();
			} catch (Exception e) {
				if (thrown == null) {
					thrown = e;
				} else {
					thrown.addSuppressed(e);
				}
			}
		}

		if (thrown != null) throw thrown;
	}
}