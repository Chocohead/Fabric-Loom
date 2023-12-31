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

package net.fabricmc.loom.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.zeroturnaround.zip.FileSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.StringZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.task.RemapJarTask;

public class NestedJars {
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static boolean addNestedJars(Project project, Logger logger, File modJar) {
		logger.debug("Looking for nested jars for {}", modJar);
		List<File> containedJars = getContainedJars(project, logger);

		if (containedJars.isEmpty()) {
			logger.debug("Found nothing to nest");
			return false;
		}

		logger.debug("Found {} nested jars: {}", containedJars.size(), containedJars);
		return addJars(modJar, containedJars);
	}

	public static boolean addJars(File modJar, List<File> containedJars) {
		ZipUtil.addOrReplaceEntries(modJar, containedJars.stream().map(file -> new FileSource("META-INF/jars/" + file.getName(), file)).toArray(ZipEntrySource[]::new));
		return ZipUtil.transformEntry(modJar, new ZipEntryTransformerEntry("fabric.mod.json", new StringZipEntryTransformer() {
			@Override
			protected String transform(ZipEntry zipEntry, String input) throws IOException {
				JsonObject json = GSON.fromJson(input, JsonObject.class);
				JsonArray nestedJars = json.getAsJsonArray("jars");

				if (nestedJars == null || !json.has("jars")) {
					nestedJars = new JsonArray();
				}

				for (File file : containedJars) {
					JsonObject jsonObject = new JsonObject();
					jsonObject.addProperty("file", "META-INF/jars/" + file.getName());
					nestedJars.add(jsonObject);
				}

				json.add("jars", nestedJars);

				return GSON.toJson(json);
			}
		}));
	}

	private static List<File> getContainedJars(Project project, Logger logger) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		List<File> fileList = new ArrayList<>();

		Configuration configuration = project.getConfigurations().getByName(Constants.INCLUDE);
		DependencySet dependencies = configuration.getDependencies();

		logger.debug("Finding {} include dependencies", dependencies.size());
		for (Dependency dependency : dependencies) {
			if (dependency instanceof ProjectDependency) {
				ProjectDependency projectDependency = (ProjectDependency) dependency;
				Project dependencyProject = projectDependency.getDependencyProject();
				logger.debug("Passing dependent project: {} (from {})", dependencyProject, projectDependency);

				//TODO change this to allow just normal jar tasks, so a project can have a none loom sub project
				Collection<Task> remapJarTasks = dependencyProject.getTasksByName("remapJar", false);
				Collection<Task> jarTasks = dependencyProject.getTasksByName("jar", false);
				logger.debug("Found {} remapJar tasks and {} jar tasks", remapJarTasks.size(), jarTasks.size());

				for (Task task : remapJarTasks.isEmpty() ? jarTasks : remapJarTasks) {
					if (task instanceof RemapJarTask) {
						fileList.add(((RemapJarTask) task).getArchivePath());
					} else if (task instanceof AbstractArchiveTask) {
						fileList.add(((AbstractArchiveTask) task).getArchivePath());
					}
				}
			} else {
				logger.debug("Passing included dependency: {}", dependency);
				fileList.addAll(prepareForNesting(logger, extension, configuration.files(dependency), dependency));
			}
		}

		for (File file : fileList) {
			if (!file.exists()) {
				throw new RuntimeException("Failed to include nested jars, as it could not be found @ " + file.getAbsolutePath());
			}

			if (file.isDirectory() || !file.getName().endsWith(".jar")) {
				throw new RuntimeException("Failed to include nested jars, as file was not a jar: " + file.getAbsolutePath());
			}
		}

		return fileList;
	}

	//Looks for any deps that require a sub project to be built first
	public static List<RemapJarTask> getRequiredTasks(Project project) {
		List<RemapJarTask> remapTasks = new ArrayList<>();

		Configuration configuration = project.getConfigurations().getByName(Constants.INCLUDE);
		DependencySet dependencies = configuration.getDependencies();

		for (Dependency dependency : dependencies) {
			if (dependency instanceof ProjectDependency) {
				ProjectDependency projectDependency = (ProjectDependency) dependency;
				Project dependencyProject = projectDependency.getDependencyProject();

				for (Task task : dependencyProject.getTasksByName("remapJar", false)) {
					if (task instanceof RemapJarTask) {
						remapTasks.add((RemapJarTask) task);
					}
				}
			}
		}

		return remapTasks;
	}

	//This is a good place to do pre-nesting operations, such as adding a fabric.mod.json to a library
	private static List<File> prepareForNesting(Logger logger, LoomGradleExtension extension, Set<File> files, Dependency dependency) {
		logger.debug("Preparing {} files for nesting: {}", files.size(), files);
		List<File> fileList = new ArrayList<>();

		for (File file : files) {
			//A lib that doesnt have a mod.json, we turn it into a fake mod
			if (!ZipUtil.containsEntry(file, "fabric.mod.json")) {
				logger.debug("Preparing non-mod file: {}", file);

				File tempDir = new File(extension.getUserCache(), "temp/modprocessing");
				if (!tempDir.exists()) {
					tempDir.mkdirs();
				}

				File tempFile = new File(tempDir, file.getName());
				if (tempFile.exists()) {
					tempFile.delete();
				}

				try {
					logger.debug("Copying from {} to {}", file, tempFile);
					FileUtils.copyFile(file, tempFile);
				} catch (IOException e) {
					throw new RuntimeException("Failed to copy file", e);
				}

				logger.debug("Adding a fabric.mod.json to {}", tempFile);
				ZipUtil.addEntry(tempFile, "fabric.mod.json", getMod(dependency, extension.getIncludeTweakers()).getBytes());
				fileList.add(tempFile);
			} else {
				logger.debug("Preparing mod file: {}", file);

				//Default copy the jar right in
				fileList.add(file);
			}
		}

		logger.debug("Produced {} nest ready files: {}", fileList.size(), fileList);
		return fileList;
	}

	//Generates a barebones mod for a dependency
	private static String getMod(Dependency dependency, List<BiConsumer<Dependency, JsonObject>> includeTweakers) {
		JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("schemaVersion", 1);
		jsonObject.addProperty("id", (dependency.getGroup() + "_" + dependency.getName()).replaceAll("\\.", "_").toLowerCase(Locale.ENGLISH));
		jsonObject.addProperty("version", dependency.getVersion());
		jsonObject.addProperty("name", dependency.getName());

		JsonObject custom = new JsonObject();
		custom.addProperty("fabric-loom:generated", true);
		jsonObject.add("custom", custom);

		for (BiConsumer<Dependency, JsonObject> tweaker : includeTweakers) {
			tweaker.accept(dependency, jsonObject);
		}

		return GSON.toJson(jsonObject);
	}
}
