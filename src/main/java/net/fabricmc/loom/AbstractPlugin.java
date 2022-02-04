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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import groovy.util.Node;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.idea.model.IdeaModel;

import org.objectweb.asm.Type;

import net.fabricmc.loom.YarnGithubResolver.MappingContainer;
import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.dependencies.RemappedConfigurationEntry;
import net.fabricmc.loom.providers.LaunchProvider;
import net.fabricmc.loom.providers.MappedModsCollectors;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.providers.MinecraftProvider;
import net.fabricmc.loom.providers.StackedMappingsProvider;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import net.fabricmc.loom.task.RemapJarTask;
import net.fabricmc.loom.task.RemapSourcesJarTask;
import net.fabricmc.loom.task.RemappingJar;
import net.fabricmc.loom.util.AccessTransformerHelper;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.GroovyXmlUtil;
import net.fabricmc.loom.util.SetupIntelijRunConfigs;
import net.fabricmc.mappings.EntryTriple;

public class AbstractPlugin implements Plugin<Project> {
	protected Project project;

	public static boolean isRootProject(Project project) {
		return project.getRootProject() == project;
	}

	private Configuration makeConfiguration(String name) {
		Configuration config = project.getConfigurations().maybeCreate(name);

		config.setCanBeConsumed(false);
		config.setTransitive(false);

		return config;
	}

	private void extendWith(String name, Configuration... configs) {
		project.getConfigurations().getByName(name).extendsFrom(configs);
	}

	protected void addAfterEvaluate(Runnable task) {
		project.afterEvaluate(project -> {
			assert this.project == project;

			if (project.getState().getFailure() == null) {
				task.run();
			} else {
				project.getLogger().debug("Skipped " + task + " as project has error: " + project.getState().getFailure());
			}
		});
	}

	@Override
	public void apply(Project target) {
		this.project = target;

		project.getLogger().lifecycle("Fabric Loom: " + AbstractPlugin.class.getPackage().getImplementationVersion());

		// Apply default plugins
		project.apply(Collections.singletonMap("plugin", "java"));
		project.apply(Collections.singletonMap("plugin", "eclipse"));
		project.apply(Collections.singletonMap("plugin", "idea"));

		LoomGradleExtension extension = project.getExtensions().create("minecraft", LoomGradleExtension.class, project);

		// Add default repositories
		addDirectoryRepo(target, "UserCacheFiles", extension.getUserCache());
		addDirectoryRepo(target, "UserLocalCacheFiles", extension.getRootProjectBuildCache());
		addDirectoryRepo(target, "UserLocalRemappedMods", extension.getRemappedModCache());
		addMavenRepo(target, "Fabric", "https://maven.fabricmc.net/");
		//addMavenRepo(target, "SpongePowered", "http://repo.spongepowered.org/maven/");
		addMavenRepo(target, "Mojang", "https://libraries.minecraft.net/");
		target.getRepositories().mavenCentral();

		// Create default configurations
		makeConfiguration(Constants.MINECRAFT).setCanBeResolved(false); //Only used for determining the Minecraft version to use
		makeConfiguration(Constants.MAPPINGS_RAW); //Used for stacking mappings
		makeConfiguration(Constants.INCLUDE); //Used for including jars in the remapped jar, acts non-transitively

		/** Used to hold the final mappings being used, will be resolved when extended (probably) */
		Configuration mappings = makeConfiguration(Constants.MAPPINGS);
		/** All the libraries which the the chosen Minecraft version depends on in isolation */
		Configuration minecraftLibraries = makeConfiguration(Constants.MINECRAFT_LIBRARIES);
		/** All the libraries which the Minecraft version depends on along with those the installer JSON adds on top */
		Configuration minecraftDependencies = project.getConfigurations().maybeCreate(Constants.MINECRAFT_DEPENDENCIES);

		/** The Minecraft jar remapped to the intermediary namespace */
		Configuration minecraftIntermediary = makeConfiguration(Constants.MINECRAFT_INTERMEDIARY);
		/** The Minecraft jar remapped to the named namespace*/
		Configuration minecraftNamed = makeConfiguration(Constants.MINECRAFT_NAMED);

		/** All compile dependencies coming from the mod* configurations (such as modCompile but not modRuntimeOnly) */
		Configuration modCompileClasspath = makeConfiguration(Constants.MOD_COMPILE_CLASSPATH);
		modCompileClasspath.setTransitive(true); //Transitive to carry of the transitivity from the individual configurations
		/** All the dependencies on the modCompileClasspath remapped to the named namespace */
		Configuration modCompileClasspathMapped = makeConfiguration(Constants.MOD_COMPILE_CLASSPATH_MAPPED);

		for (RemappedConfigurationEntry entry : Constants.MOD_COMPILE_ENTRIES) {
			/** The mods that are to be remapped to the current named mappings */
			Configuration compileMods = makeConfiguration(entry.getSourceConfiguration());
			compileMods.setTransitive(true); //Explicitly transitive to allow JiJ'd jars to be pulled in via maven instead
			/** The mods that have been remapped to the current named mappings */
			Configuration compileModsMapped = makeConfiguration(entry.getRemappedConfiguration());

			entry.getTargetConfiguration(project.getConfigurations()).extendsFrom(compileModsMapped);

			if (entry.isOnModCompileClasspath()) {
				modCompileClasspath.extendsFrom(compileMods);
				modCompileClasspathMapped.extendsFrom(compileModsMapped);
			}
		}

		minecraftDependencies.extendsFrom(minecraftLibraries);
		minecraftIntermediary.extendsFrom(minecraftDependencies);
		minecraftNamed.extendsFrom(minecraftDependencies);

		extendWith(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME, minecraftNamed, mappings);
		extendWith(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, minecraftNamed, mappings);

		if (!extension.ideSync()) {
			extendWith(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, minecraftNamed, modCompileClasspathMapped, mappings);
		}

		configureIDEs();
		configureCompile();
		configureScala();

		for (JavaCompile javaCompileTask : project.getTasks().withType(JavaCompile.class)) {
			if (!javaCompileTask.getName().contains("Test") && !javaCompileTask.getName().contains("test")) {
				//Allow adding extra mappings onto a compile for Mixin to generate remaps with
				//The MinecraftVersion is not really relevant so we'll simplify things and pass null
				MappingContainer extraMappings = javaCompileTask.getExtensions().create("mappings", MappingContainer.class, (String) null);
				extraMappings.setFrom("named");
				extraMappings.setTo("intermediary");

				javaCompileTask.doFirst(new Action<Task>() {
					@Override
					public void execute(Task task) {

						String from = extraMappings.getFrom();
						String to = extraMappings.getTo();

						task.getLogger().lifecycle(":setting java compiler args");
						try {
							Collections.addAll(javaCompileTask.getOptions().getCompilerArgs(),
									"-AinMapFileNamedIntermediary=" + extension.getMappingsProvider().MAPPINGS_TINY.getCanonicalPath(),
									"-AoutMapFileNamedIntermediary=" + extension.getMappingsProvider().MAPPINGS_MIXIN_EXPORT.getCanonicalPath(),
									"-AoutRefMapFile=" + new File(javaCompileTask.getDestinationDir(), extension.getRefmapName(task)).getCanonicalPath(),
									"-AdefaultObfuscationEnv=" + from + ':' + to);
						} catch (IOException e) {
							throw new UncheckedIOException("Unable to canonicalise path", e);
						}

						if (extension.hasTokens()) {
							StringBuilder arg = new StringBuilder("-Atokens=");

							for (Entry<String, String> entry : extension.getTokens().entrySet()) {
								arg.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
							}

							task.getLogger().info("Appending {} Mixin tokens", extension.getTokens().size());
							javaCompileTask.getOptions().getCompilerArgs().add(arg.substring(0, arg.length() - 1));
						}

						if (extraMappings.isEmpty()) return; //Nothing to else do

						Path mappingFile;
						try {
							mappingFile = Files.createTempFile(task.getTemporaryDir().toPath(), "mixin-extra", ".tiny");
						} catch (IOException e) {
							throw new UncheckedIOException("Failed to create temporary mappings file", e);
						}

						try (TinyWriter writer = new TinyWriter(mappingFile, from, to)) {
							for (Entry<String, String> entry : extraMappings.getClasses().entrySet()) {
								writer.acceptClass(entry.getKey(), entry.getValue());
							}

							for (Entry<EntryTriple, String> entry : extraMappings.getMethods().entrySet()) {
								EntryTriple method = entry.getKey();
								writer.acceptMethod(method.getOwner(), method.getDesc(), method.getName(), entry.getValue());
							}

							for (Entry<EntryTriple, String> entry : extraMappings.getFields().entrySet()) {
								EntryTriple field = entry.getKey();
								writer.acceptField(field.getOwner(), field.getDesc(), field.getName(), entry.getValue());
							}

							//If there are any class members, the signatures might include Minecraft types which need stating explicitly
							//It could be missed but then they wouldn't get remapped which is not very useful
							if (!extraMappings.getMethods().isEmpty() || !extraMappings.getFields().isEmpty()) {
								Map<String, String> classes;
								try {
									classes = extension.getMappingsProvider().getMappings().getClassEntries().stream()
											.collect(Collectors.toMap(entry -> entry.get(from), entry -> entry.get(to)));
								} catch (IOException e) {
									throw new UncheckedIOException("Error getting complete mappings", e);
								}

								Map<String, String> extraClasses = new HashMap<>();

								for (EntryTriple member : extraMappings.getMethods().keySet()) {
									if (classes.containsKey(member.getOwner())) {
										extraClasses.put(member.getOwner(), classes.get(member.getOwner()));
									}

									for (Type argument : Type.getArgumentTypes(member.getDesc())) {
										String argumentType = argument.getInternalName();

										if (classes.containsKey(argumentType)) {
											extraClasses.put(argumentType, classes.get(argumentType));
										}
									}

									String returnType = Type.getReturnType(member.getDesc()).getInternalName();
									if (classes.containsKey(returnType)) {
										extraClasses.put(returnType, classes.get(returnType));
									}
								}

								for (EntryTriple member : extraMappings.getFields().keySet()) {
									if (classes.containsKey(member.getOwner())) {
										extraClasses.put(member.getOwner(), classes.get(member.getOwner()));
									}

									String returnType = Type.getType(member.getDesc()).getInternalName();
									if (classes.containsKey(returnType)) {
										extraClasses.put(returnType, classes.get(returnType));
									}
								}

								for (Entry<String, String> entry : extraClasses.entrySet()) {
									writer.acceptClass(entry.getKey(), entry.getValue());
								}
							}
						} catch (IOException e) {
							throw new UncheckedIOException("Failed to write extra Mixin mappings file", e);
						}

						task.getLogger().info("Appending extra Mixin mappings at {}", mappingFile);

						StringBuilder arg = new StringBuilder("-AinMapExtraFiles");
						arg.append(Character.toTitleCase(from.charAt(0))).append(from.substring(1)); //Capitalise the namespaces
						arg.append(Character.toTitleCase(to.charAt(0))).append(to.substring(1));
						javaCompileTask.getOptions().getCompilerArgs().add(arg.append('=').append(mappingFile.toAbsolutePath()).toString());
					}
				});
			}
		}

		configureMaven();
	}

	public Project getProject() {
		return project;
	}

	protected void configureScala() {
		addAfterEvaluate(() -> {
			if (project.getPluginManager().hasPlugin("scala")) {
				ScalaCompile task = (ScalaCompile) project.getTasks().getByName("compileScala");
				LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
				project.getLogger().warn(":configuring scala compilation processing");

				try {
					task.getOptions().getCompilerArgs().add("-AinMapFileNamedIntermediary=" + extension.getMappingsProvider().MAPPINGS_TINY.getCanonicalPath());
					task.getOptions().getCompilerArgs().add("-AoutMapFileNamedIntermediary=" + extension.getMappingsProvider().MAPPINGS_MIXIN_EXPORT.getCanonicalPath());
					task.getOptions().getCompilerArgs().add("-AoutRefMapFile=" + new File(task.getDestinationDir(), extension.getRefmapName(task)).getCanonicalPath());
					task.getOptions().getCompilerArgs().add("-AdefaultObfuscationEnv=named:intermediary");
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Permit to add a Maven repository to a target project.
	 *
	 * @param target The garget project
	 * @param name   The name of the repository
	 * @param url    The URL of the repository
	 * @return An object containing the name and the URL of the repository that can be modified later
	 */
	public static MavenArtifactRepository addMavenRepo(Project target, final String name, final String url) {
		return target.getRepositories().maven(repo -> {
			repo.setName(name);
			repo.setUrl(url);
		});
	}

	public static FlatDirectoryArtifactRepository addDirectoryRepo(Project target, final String name, final Object directory) {
		return target.getRepositories().flatDir(repo -> {
			repo.setName(name);
			repo.dir(directory);
		});
	}

	/**
	 * Add Minecraft dependencies to IDE dependencies.
	 */
	protected void configureIDEs() {
		// IDEA
		IdeaModel ideaModel = (IdeaModel) project.getExtensions().getByName("idea");

		ideaModel.getModule().getExcludeDirs().addAll(project.files(".gradle", "build", ".idea", "out").getFiles());
		ideaModel.getModule().setDownloadJavadoc(true);
		ideaModel.getModule().setDownloadSources(true);
		ideaModel.getModule().setInheritOutputDirs(true);

		// ECLIPSE
		EclipseModel eclipseModel = (EclipseModel) project.getExtensions().getByName("eclipse");
	}

	public static class JarSettings {
		public boolean includeAT = true;

		public void setInclude(boolean flag) {
			includeAT = flag;
		}
	}

	/**
	 * Add Minecraft dependencies to compile time.
	 */
	protected void configureCompile() {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		JavaPluginConvention javaModule = (JavaPluginConvention) project.getConvention().getPlugins().get("java");

		SourceSet main = javaModule.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
		SourceSet test = javaModule.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);

		Javadoc javadoc = (Javadoc) project.getTasks().getByName(JavaPlugin.JAVADOC_TASK_NAME);
		javadoc.setClasspath(main.getOutput().plus(main.getCompileClasspath()));

		project.getTasks().withType(AbstractArchiveTask.class).all(task -> {
			JarSettings settings = task.getExtensions().create("AT", JarSettings.class);
			//Only include the AT by default in the main jar and sources tasks
			if (!JavaPlugin.JAR_TASK_NAME.equals(task.getName()) && !"sourcesJar".equals(task.getName())) settings.setInclude(false);

			addAfterEvaluate(() -> {
				if (settings.includeAT) {
					AccessTransformerHelper.copyInAT(extension, task);
				}
			});
		});

		if (!extension.ideSync()) {
			// Add Mixin dependencies
			project.getDependencies().add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, "net.fabricmc:fabric-mixin-compile-extensions:" + Constants.MIXIN_COMPILE_EXTENSIONS_VERSION);
		}

		project.getGradle().buildFinished(result -> {
			try {//Try avoid daemons causing caching problems
				if (extension.hasMinecraftProvider()) extension.getMinecraftProvider().clearCache();
			} catch (Throwable t) {
				project.getLogger().warn("Error cleaning up after evaluation", t);
			}
		});

		addAfterEvaluate(() -> {
			LoomDependencyManager dependencyManager = new LoomDependencyManager();
			extension.setDependencyManager(dependencyManager);

			dependencyManager.addProvider(new MinecraftProvider());
			dependencyManager.addProvider(new StackedMappingsProvider());
			dependencyManager.addProvider(new MinecraftMappedProvider());
			MappedModsCollectors.addAll(dependencyManager);
			dependencyManager.addProvider(new LaunchProvider());

			dependencyManager.handleDependencies(project);


			project.getTasks().getByName("idea").finalizedBy(project.getTasks().getByName("genIdeaWorkspace"));
			project.getTasks().getByName("eclipse").finalizedBy(project.getTasks().getByName("genEclipseRuns"));

			if (extension.autoGenIDERuns) {
				SetupIntelijRunConfigs.setup(project);
			}


			// Enables the default mod remapper
			if (extension.remapMod) {
				AbstractArchiveTask jarTask = (AbstractArchiveTask) project.getTasks().getByName(JavaPlugin.JAR_TASK_NAME);
				RemapJarTask remapJarTask = (RemapJarTask) project.getTasks().getByName("remapJar");

				if (!remapJarTask.getInput().isPresent()) {
					jarTask.setClassifier("dev");
					remapJarTask.setClassifier("");
					remapJarTask.getInput().set(jarTask.getArchivePath());
					remapJarTask.setIncludeAT(jarTask.getExtensions().getByType(JarSettings.class).includeAT);
				}

				extension.addUnmappedMod(jarTask.getArchivePath().toPath());
				remapJarTask.setAddNestedDependencies(true);

				project.getArtifacts().add("archives", remapJarTask);
				remapJarTask.dependsOn(jarTask);
				project.getTasks().getByName("build").dependsOn(remapJarTask);

				List<Task> remappingTasks = Stream.concat(project.getTasks().withType(RemapJarTask.class).matching(RemapJarTask::isAddNestedDependencies).stream(),
						project.getTasks().withType(RemappingJar.class).matching(RemappingJar::isNestJar).stream()).collect(Collectors.toList());
				if (!remappingTasks.isEmpty()) {
					TaskDependency dependency = project.getConfigurations().getByName(Constants.INCLUDE).getTaskDependencyFromProjectDependency(true, "remapJar");

					for (Task remappingTask : remappingTasks) {
						remappingTask.dependsOn(dependency);
					}
				}

				try {
					AbstractArchiveTask sourcesTask = (AbstractArchiveTask) project.getTasks().getByName("sourcesJar");
					RemapSourcesJarTask remapSourcesJarTask = (RemapSourcesJarTask) project.getTasks().findByName("remapSourcesJar");
					remapSourcesJarTask.setInput(sourcesTask.getArchivePath());
					remapSourcesJarTask.setOutput(sourcesTask.getArchivePath());
					remapSourcesJarTask.doLast(task -> project.getArtifacts().add("archives", remapSourcesJarTask.getOutput()));
					remapSourcesJarTask.dependsOn(project.getTasks().getByName("sourcesJar"));
					project.getTasks().getByName("build").dependsOn(remapSourcesJarTask);
				} catch (UnknownTaskException e) {
					// pass
				}
			} else {
				AbstractArchiveTask jarTask = (AbstractArchiveTask) project.getTasks().getByName("jar");
				extension.addUnmappedMod(jarTask.getArchivePath().toPath());
			}

			// Disable some things used by log4j via the mixin AP that prevent it from being garbage collected
			System.setProperty("log4j2.disable.jmx", "true");
			System.setProperty("log4j.shutdownHookEnabled", "false");
		});
	}

	protected void configureMaven() {
		addAfterEvaluate(() -> {
			PublishingExtension mavenPublish = project.getExtensions().findByType(PublishingExtension.class);
			if (mavenPublish == null) return; //The maven-publish plugin is not present

			for (RemappedConfigurationEntry entry : Constants.MOD_COMPILE_ENTRIES) {
				if (!entry.hasMavenScope()) {
					continue;
				}

				//Add modsCompile to maven-publish
				mavenPublish.publications(publications -> {
					Configuration compileModsConfig = project.getConfigurations().getByName(entry.getSourceConfiguration());

					for (Publication publication : publications) {
						if (publication instanceof MavenPublication) {
							((MavenPublication) publication).pom(pom -> {
								pom.withXml(xml -> {
									Node dependencies = GroovyXmlUtil.getOrCreateNode(xml.asNode(), "dependencies");
									Map<String, Dependency> extraDependencies = new HashMap<>();

									for (Dependency dependency : compileModsConfig.getAllDependencies()) {
										extraDependencies.put(dependency.getGroup() + ':' + dependency.getName(), dependency);
									}

									GroovyXmlUtil.getNodes(dependencies, "dependency").forEach(node -> {
										Optional<Node> groupId = GroovyXmlUtil.getNode(node, "groupId");
										Optional<Node> artifactId = GroovyXmlUtil.getNode(node, "artifactId");

										if (groupId.isPresent() && artifactId.isPresent()) {
											Dependency artifact = extraDependencies.remove(groupId.get().text() + ':' + artifactId.get().text());
											if (artifact != null) GroovyXmlUtil.getOrCreateNode(node, "version").setValue(artifact.getVersion());
										}
									});

									for (Dependency dependency : extraDependencies.values()) {
										Node depNode = dependencies.appendNode("dependency");
										depNode.appendNode("groupId", dependency.getGroup());
										depNode.appendNode("artifactId", dependency.getName());
										depNode.appendNode("version", dependency.getVersion());
										depNode.appendNode("scope", entry.getMavenScope());

										if (dependency instanceof ModuleDependency) {
											Set<ExcludeRule> exclusions = ((ModuleDependency) dependency).getExcludeRules();

											if (!exclusions.isEmpty()) {
												Node exclusionsNode = depNode.appendNode("exclusions");

												for (ExcludeRule rule : exclusions) {
													Node exclusionNode = exclusionsNode.appendNode("exclusion");
													exclusionNode.appendNode("groupId", rule.getGroup() == null ? "*" : rule.getGroup());
													exclusionNode.appendNode("artifactId", rule.getModule() == null ? "*" : rule.getModule());
												}
											}
										}
									}
								});
							});
						}
					}
				});
			}
		});
	}
}
