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

package net.fabricmc.loom;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonObject;

import org.cadixdev.mercury.Mercury;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.file.FileCollection;
import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.plugins.BasePluginConvention;

import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.providers.JarNameFactory;
import net.fabricmc.loom.providers.JarNamingStrategy;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.providers.MinecraftProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.GradleSupport;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper.LocalNameSuggestor;
import net.fabricmc.stitch.commands.CommandProposeFieldNames.NameAcceptor;

public class LoomGradleExtension {
	/** The order in which the Minecraft client and server jars should be merged together and remapped */
	public enum JarMergeOrder {
		/** Run based on whether the Minecraft version is from before 23th July 2012 */
		INDIFFERENT(null) {
			@Override
			public String getJarName(JarNamingStrategy strategy) {
				throw new UnsupportedOperationException("Cannot get jar name of indifferent merge order");
			}
		},
		/** Always merge jars before mappings are present, regardless of version */
		FIRST(JarNameFactory.MERGED),
		/** Always remap jars before merging, regardless of version */
		LAST(JarNameFactory.MERGED_INTERMEDIARY),
		/** Don't merge the jars at all, instead just use the client jar */
		CLIENT_ONLY(JarNameFactory.CLIENT),
		/** Don't merge the jars at all, instead just use the server jar */
		SERVER_ONLY(JarNameFactory.SERVER);

		private JarMergeOrder(JarNameFactory namer) {
			this.namer = namer;
		}

		public String getJarName(JarNamingStrategy strategy) {
			return namer.getJarName(strategy);
		}

		public Set<String> getNativeHeaders() {
			switch (this) {
			case FIRST:
				return Collections.singleton("official");

			case LAST:
				return ImmutableSet.of("client", "server");

			case CLIENT_ONLY:
				return Collections.singleton("client");

			case SERVER_ONLY:
				return Collections.singleton("server");

			case INDIFFERENT:
				throw new UnsupportedOperationException("Indifferent merge order doesn't have headers");

			default:
				throw new IllegalStateException("Unexpected jar merge order " + this);
			}
		}

		public List<String> getNeededHeaders() {
			return ImmutableList.<String>builder().add("intermediary").addAll(getNativeHeaders()).build();
		}

		private final JarNameFactory namer;
	}
	public String runDir = "run";
	public String refmapName;
	public final Map<String, String> taskToRefmap = new HashMap<>();
	public String loaderLaunchMethod;
	public boolean remapMod = true;
	public boolean autoGenIDERuns = true;
	public boolean extractJars = false;
	public String customManifest = null;

	private JarMergeOrder mergeOrder = JarMergeOrder.INDIFFERENT;
	private boolean bulldozeMappings;
	private NameAcceptor fieldInferenceFilter = (inputMapping, originalName, replacementName) -> originalName.startsWith("field_");
	private final List<LocalNameSuggestor> nameSuggestors = new ArrayList<>();
	private final Map<String, String> tokens = new HashMap<>();
	private File atFile;
	private File optifine;
	private List<Path> unmappedModsBuilt = new ArrayList<>();

	//Not to be set in the build.gradle
	private final Project project;
	private LoomDependencyManager dependencyManager;
	private boolean parallelLoad;
	private JsonObject installerJson;
	private Mercury[] srcMercuryCache = new Mercury[2];

	public Mercury getOrCreateSrcMercuryCache(int id, Supplier<Mercury> factory) {
		return srcMercuryCache[id] != null ? srcMercuryCache[id] : (srcMercuryCache[id] = factory.get());
	}

	public LoomGradleExtension(Project project) {
		this.project = project;

		//Common Java types which get silly local names from the capitalisation by default
		addLocalName(URL.class.getName(), "url");
		addLocalName(URI.class.getName(), "uri");

		//Gradle 6 tightens the rules for resolving configurations off thread (by not allowing it at all)
		//There are ways around it, but said ways need implementing within LoomDependencyManager
		parallelLoad = GradleSupport.majorGradleVersion(project) < 6;
	}

	public void addUnmappedMod(Path file) {
		unmappedModsBuilt.add(file);
	}

	public List<Path> getUnmappedMods() {
		return Collections.unmodifiableList(unmappedModsBuilt);
	}

	public void setInstallerJson(JsonObject object) {
		this.installerJson = object;
	}

	public JsonObject getInstallerJson() {
		return installerJson;
	}

	public File getUserCache() {
		File userCache = new File(project.getGradle().getGradleUserHomeDir(), "caches" + File.separator + "fabric-loom");

		if (!userCache.exists()) {
			userCache.mkdirs();
		}

		return userCache;
	}

	public File getRootProjectPersistentCache() {
		File projectCache = new File(project.getRootProject().file(".gradle"), "loom-cache");

		if (!projectCache.exists()) {
			projectCache.mkdirs();
		}

		return projectCache;
	}

	public File getRootProjectBuildCache() {
		File projectCache = new File(project.getRootProject().getBuildDir(), "loom-cache");

		if (!projectCache.exists()) {
			projectCache.mkdirs();
		}

		return projectCache;
	}

	public File getProjectBuildCache() {
		File projectCache = new File(project.getBuildDir(), "loom-cache");

		if (!projectCache.exists()) {
			projectCache.mkdirs();
		}

		return projectCache;
	}

	public File getRemappedModCache() {
		File remappedModCache = new File(getRootProjectPersistentCache(), "remapped_mods");

		if (!remappedModCache.exists()) {
			remappedModCache.mkdir();
		}

		return remappedModCache;
	}

	public File getNestedModCache() {
		File nestedModCache = new File(getRootProjectPersistentCache(), "nested_mods");

		if (!nestedModCache.exists()) {
			nestedModCache.mkdir();
		}

		return nestedModCache;
	}

	public File getNativesJarStore() {
		File natives = new File(getUserCache(), "natives/jars");

		if (!natives.exists()) {
			natives.mkdirs();
		}

		return natives;
	}

	public File getNativesDirectory() {
		File natives = new File(getUserCache(), "natives/" + getMinecraftProvider().minecraftVersion);

		if (!natives.exists()) {
			natives.mkdirs();
		}

		return natives;
	}

	public File getDevLauncherConfig() {
		return new File(getRootProjectPersistentCache(), "launch.cfg");
	}

	@Nullable
	private static ModuleVersionIdentifier findDependency(Project p, Collection<Configuration> configs, BiPredicate<String, String> groupNameFilter) {
		for (Configuration config : configs) {
			for (ResolvedArtifact artifact : config.getResolvedConfiguration().getResolvedArtifacts()) {
				ModuleVersionIdentifier module = artifact.getModuleVersion().getId();

				String group = module.getGroup();
				String name = module.getName();

				if (groupNameFilter.test(group, name)) {
					p.getLogger().debug("Loom findDependency found: " + group + ':' + name + ':' + module.getVersion());
					return module;
				}
			}
		}

		return null;
	}

	@Nullable
	private <T> T recurseProjects(Function<Project, T> projectTFunction) {
		Project p = this.project;
		T result;

		while (!AbstractPlugin.isRootProject(p)) {
			if ((result = projectTFunction.apply(p)) != null) {
				return result;
			}

			p = p.getRootProject();
		}

		result = projectTFunction.apply(p);
		return result;
	}

	@Nullable
	private ModuleVersionIdentifier getMixinDependency() {
		return recurseProjects((p) -> {
			List<Configuration> configs = new ArrayList<>();
			// check compile classpath first
			Configuration possibleCompileClasspath = p.getConfigurations().findByName("compileClasspath");

			if (possibleCompileClasspath != null) {
				configs.add(possibleCompileClasspath);
			}

			// failing that, buildscript
			configs.addAll(p.getBuildscript().getConfigurations());

			return findDependency(p, configs, (group, name) -> {
				if (name.equalsIgnoreCase("mixin") && group.equalsIgnoreCase("org.spongepowered")) {
					return true;
				}

				if (name.equalsIgnoreCase("sponge-mixin") && group.equalsIgnoreCase("net.fabricmc")) {
					return true;
				}

				return false;
			});
		});
	}

	@Nullable
	public String getMixinJsonVersion() {
		ModuleVersionIdentifier dependency = getMixinDependency();

		if (dependency != null) {
			if (dependency.getGroup().equalsIgnoreCase("net.fabricmc")) {
				if (Objects.requireNonNull(dependency.getVersion()).split("\\.").length >= 4) {
					return dependency.getVersion().substring(0, dependency.getVersion().lastIndexOf('.')) + "-SNAPSHOT";
				}
			}

			return dependency.getVersion();
		}

		return null;
	}

	public boolean hasLWJGL2() {
		return recurseProjects(project -> {
			Configuration libraries = project.getConfigurations().getByName(Constants.MINECRAFT_LIBRARIES);
			ModuleVersionIdentifier version = findDependency(project, Collections.singleton(libraries), (group, name) -> {
				return "org.lwjgl.lwjgl".equalsIgnoreCase(group) && "lwjgl".equalsIgnoreCase(name);
			});

			if (version != null) {
				try {
					int split = version.getVersion().indexOf('.');
					return split > 0 && Integer.parseInt(version.getVersion().substring(0, split)) < 3 ? Boolean.TRUE : Boolean.FALSE;
				} catch (NumberFormatException e) {
					project.getLogger().error("Unexpected leading part to LWJGL version: " + version.getVersion(), e);
					return Boolean.FALSE;
				}
			} else {
				return null;
			}
		}) == Boolean.TRUE;
	}

	public FileCollection getFernFlowerClasspath() {
		return recurseProjects(project -> {
			ConfigurationContainer configurations = project.getBuildscript().getConfigurations();
			Configuration fileClasspath = configurations.getByName(ScriptHandler.CLASSPATH_CONFIGURATION);

			if (findDependency(project, Collections.singleton(fileClasspath), (group, name) -> {
				return "com.github.Chocohead".equalsIgnoreCase(group) && "ForgedFlower".equalsIgnoreCase(name);
			}) != null) {
				return fileClasspath.plus(configurations.detachedConfiguration(project.getDependencies().localGroovy()));
			} else {
				return null;
			}
		});
	}

	public String getLoaderLaunchMethod() {
		return loaderLaunchMethod != null ? loaderLaunchMethod : "";
	}

	public void setParallelLoad(boolean inParallel) {
		parallelLoad = inParallel;
	}

	public boolean shouldLoadInParallel() {
		return parallelLoad;
	}

	public LoomDependencyManager getDependencyManager() {
		return dependencyManager;
	}

	public MinecraftProvider getMinecraftProvider() {
		return getDependencyManager().getProvider(MinecraftProvider.class);
	}

	public MinecraftMappedProvider getMinecraftMappedProvider() {
		return getDependencyManager().getProvider(MinecraftMappedProvider.class);
	}

	public MappingsProvider getMappingsProvider() {
		return getDependencyManager().getProvider(MappingsProvider.class);
	}

	public void setDependencyManager(LoomDependencyManager dependencyManager) {
		this.dependencyManager = dependencyManager;
	}

	public String getRefmapName(Task task) {
		return taskToRefmap.computeIfAbsent(task.getName(), name -> {
			if (refmapName == null || refmapName.isEmpty()) {
				String defaultRefmapName = project.getConvention().getPlugin(BasePluginConvention.class).getArchivesBaseName() + "-refmap.json";
				project.getLogger().warn("Could not find refmap definition, will be using default name: " + defaultRefmapName);
				refmapName = defaultRefmapName;
			}

			return refmapName;
		});
	}

	public boolean ideSync() {
		return Boolean.parseBoolean(System.getProperty("idea.sync.active", "false"));
	}

	public void setJarMergeOrder(String order) {
		for (JarMergeOrder mergeOrder : JarMergeOrder.values()) {
			if (mergeOrder.name().equalsIgnoreCase(order)) {
				setJarMergeOrder(mergeOrder);
				return;
			}
		}

		throw new IllegalArgumentException("Unknown merge order " + order + ", expected one of " + Arrays.toString(JarMergeOrder.values()));
	}

	public void setJarMergeOrder(JarMergeOrder order) {
		mergeOrder = order;
	}

	public JarMergeOrder getJarMergeOrder() {
		return mergeOrder;
	}

	public void setBulldozeMappings(boolean force) {
		bulldozeMappings = force;
	}

	public boolean shouldBulldozeMappings() {
		return bulldozeMappings;
	}

	public void setFieldInferenceFilter(NameAcceptor filter) {
		fieldInferenceFilter = filter;
	}

	public NameAcceptor getFieldInferenceFilter() {
		return fieldInferenceFilter;
	}

	public void addLocalName(String typeName, String localName) {
		addLocalName(typeName, localName, localName + 's');
	}

	public void addLocalName(String typeName, String localName, String pluralLocalName) {
		String internalType = Objects.requireNonNull(typeName, "Passed in a null type").replace('.', '/');

		addLocalNamer((type, plural) -> internalType.equals(type) ? plural ? pluralLocalName : localName : null);
	}

	public void addLocalNamer(LocalNameSuggestor suggestor) {
		nameSuggestors.add(suggestor);
	}

	public List<LocalNameSuggestor> getLocalSuggestors() {
		return Collections.unmodifiableList(nameSuggestors);
	}

	public void token(CharSequence name) {
        token(name, "true");
    }

    public void token(CharSequence name, CharSequence value) {
    	String cleanName = name.toString().trim();
    	if (cleanName.indexOf(';') >= 0) throw new IllegalArgumentException("Token name cannot contain ;");

    	String cleanValue = value.toString().trim();
    	if (cleanValue.indexOf(';') >= 0) throw new IllegalArgumentException("Token value cannot contain ;");

        tokens.put(cleanName, cleanValue);
    }

    public void tokens(Map<CharSequence, CharSequence> tokens) {
    	tokens.forEach(this::token);
    }

    public boolean hasTokens() {
    	return !tokens.isEmpty();
    }

    public Map<String, String> getTokens() {
    	return Collections.unmodifiableMap(tokens);
    }

	public void setAT(Object file) {
		atFile = project.file(file);
	}

	public boolean hasAT() {
		return atFile != null;
	}

	public File getAT() {
		return atFile;
	}

	public void setOptiFine(Object file) {
		optifine = project.file(file);
	}

	public boolean hasOptiFine() {
		return optifine != null;
	}

	public File getOptiFine() {
		return optifine;
	}
}
