/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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

package net.fabricmc.loom.dependencies;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Throwables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPlugin;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MappedModsResolver;
import net.fabricmc.loom.util.Constants;

public class LoomDependencyManager {
	private final List<DependencyProvider> dependencyProviderList = new ArrayList<>();
	private boolean hasHandled;

	/** Whether there is a registered {@link DependencyProvider} for the given {@link Class} */
	public boolean hasProvider(Class<? extends DependencyProvider> clazz) {
		for (DependencyProvider provider : dependencyProviderList) {
			if (provider.getClass() == clazz) {
				return true;
			}
		}

		return false;
	}

	/** Register the given {@link DependencyProvider} */
	public void addProvider(DependencyProvider provider) {
		if (dependencyProviderList.contains(provider)) {
			throw new IllegalArgumentException("Provider is already registered");
		}

		if (hasProvider(provider.getClass())) {
			throw new IllegalArgumentException("Provider of this type is already registered");
		}

		if (hasHandled) {
			throw new IllegalStateException("Dependencies have already been handled");
		}

		provider.register(this);
		dependencyProviderList.add(provider);
	}

	/** Gets the registered {@link DependencyProvider} for the given {@link Class} or {@code null} if none are registered */
	public <T extends DependencyProvider> T getProvider(Class<T> clazz) {
		for (DependencyProvider provider : dependencyProviderList) {
			if (provider.getClass() == clazz) {
				return clazz.cast(provider);
			}
		}

		return null;
	}

	/** Evaluate all the registered {@link DependencyProvider}s against the given {@link Project}, preventing the registration of any further */
	public void handleDependencies(Project project) {
		project.getLogger().lifecycle(":setting up loom dependencies");
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);

		hasHandled = true; //No time for anything else now
		DependencyGraph graph = new DependencyGraph(dependencyProviderList);
		List<Runnable> afterTasks = new ArrayList<>();

		if (extension.shouldLoadInParallel()) {
			List<CompletableFuture<?>> tasks = new ArrayList<>();
			AtomicBoolean didBreak = new AtomicBoolean();

			try {
				while (graph.waitForWork() && !didBreak.get()) {
					for (DependencyProvider provider : graph.allAvailable()) {
						tasks.add(CompletableFuture.runAsync(() -> {
							try {
								provider.provide(project, extension, afterTasks::add);
							} catch (Throwable t) {
								throw new RuntimeException("Failed to provide " + provider.getType() + " dependency of type " + provider.getClass(), t);
							}
						}).whenComplete((success, exception) -> {
							if (exception == null) {
								if (!didBreak.get()) graph.markComplete(provider);
							} else {
								didBreak.set(true);

								synchronized (graph) {
									graph.notifyAll();
								}
							}
						}));
					}
				}
			} catch (InterruptedException e) {
				throw new RuntimeException("Unexpected halt to processing dependencies", e);
			}

			if (didBreak.get()) {//Bailed early, time to unpack the exceptions
				Throwable thrown = null;

				for (CompletableFuture<?> task : tasks) {
					try {
						task.join();
					} catch (CancellationException e) {
						//Well it didn't strictly go wrong if cancelled
					} catch (CompletionException e) {
						if (thrown == null) {
							thrown = e.getCause();
						} else {
							thrown.addSuppressed(e.getCause());
						}
					} catch (Throwable t) {
						if (thrown == null) {
							thrown = t;
						} else {
							thrown.addSuppressed(t);
						}
					}
				}

				if (thrown == null) {
					throw new IllegalStateException("Error processing dependencies, unable to find cause");
				} else {
					Throwables.throwIfUnchecked(thrown);
					throw new RuntimeException("Error processing dependencies", thrown);
				}
			}
		} else {
			for (DependencyProvider provider : graph.asIterable()) {
				try {
					provider.provide(project, extension, afterTasks::add);
				} catch (Throwable t) {
					throw new RuntimeException("Failed to provide " + provider.getType() + " dependency of type " + provider.getClass(), t);
				}

				graph.markComplete(provider);
			}
		}

		if (extension.getInstallerJson() == null) {
			//If we've not found the installer JSON we've probably skipped remapping Fabric loader, let's go looking
			project.getLogger().info("Searching through modCompileClasspath for installer JSON");

			Configuration configuration = project.getConfigurations().getByName(Constants.MOD_COMPILE_CLASSPATH);
			findInstallerJson(project.getLogger(), extension, configuration, extension.getLoaderLaunchMethod());

			if (extension.getInstallerJson() == null && !extension.getLoaderLaunchMethod().isEmpty()) {
				project.getLogger().warn("Could not find installer JSON for launch method '{}', falling back", extension.getLoaderLaunchMethod());

				findInstallerJson(project.getLogger(), extension, configuration, "");
			}
		}

		if (extension.getInstallerJson() != null) {
			handleInstallerJson(project, extension, extension.getInstallerJson());
		} else {
			project.getLogger().warn("fabric-installer.json not found in classpath!");
		}

		for (Runnable runnable : afterTasks) {
			runnable.run();
		}
	}

	private static void findInstallerJson(Logger logger, LoomGradleExtension extension, Configuration configuration, String launchMethod) {
		for (File input : configuration.resolve()) {
			JsonObject jsonObject = MappedModsResolver.findInstallerJson(logger, input, extension.getLoaderLaunchMethod());

			if (jsonObject != null) {
				if (extension.getInstallerJson() != null) {
					logger.info("Found another installer JSON in {}, ignoring it!", input);
					continue;
				}

				logger.info("Found installer JSON in {}", input);
				extension.setInstallerJson(jsonObject);
			}
		}
	}

	private static void handleInstallerJson(Project project, LoomGradleExtension extension, JsonObject json) {
		Configuration mcDepsConfig = project.getConfigurations().getByName(Constants.MINECRAFT_DEPENDENCIES);
		Configuration apDepsConfig = project.getConfigurations().getByName(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME);
		Set<String> urls = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

		for (JsonElement element : json.getAsJsonObject("libraries").getAsJsonArray("common")) {
			JsonObject library = element.getAsJsonObject();

			String name = library.get("name").getAsString();

			ExternalModuleDependency modDep = (ExternalModuleDependency) project.getDependencies().create(name);
			modDep.setTransitive(false);
			mcDepsConfig.getDependencies().add(modDep);

			if (!extension.ideSync()) {
				apDepsConfig.getDependencies().add(modDep);
			}

			project.getLogger().debug("Loom adding " + name + " from installer JSON");

			if (library.has("url")) {
				urls.add(library.get("url").getAsString());
			}
		}

		for (ArtifactRepository repo : project.getRepositories()) {
			if (repo instanceof MavenArtifactRepository) {
				urls.remove(((MavenArtifactRepository) repo).getUrl().toString());
			}
		}
		for (String url : urls) {
			project.getRepositories().maven(repo -> repo.setUrl(url));
		}
	}
}
