/*
 * Copyright 2019, 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;
import java.util.zip.ZipError;

import com.google.common.util.concurrent.Callables;
import com.google.gson.Gson;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.AbstractPlugin;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradleExtension.JarMergeOrder;
import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.dependencies.PhysicalDependencyProvider;
import net.fabricmc.loom.providers.openfine.Openfine;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.GradleSupport;
import net.fabricmc.loom.util.ManifestVersion;
import net.fabricmc.loom.util.MapJarsTiny;
import net.fabricmc.loom.util.MinecraftVersionInfo;
import net.fabricmc.loom.util.HexaFunction;
import net.fabricmc.loom.util.MinecraftVersionInfo.AssetIndex;
import net.fabricmc.loom.util.MinecraftVersionInfo.Library;
import net.fabricmc.loom.util.StaticPathWatcher;
import net.fabricmc.stitch.merge.JarMerger;

public class MinecraftProvider extends PhysicalDependencyProvider implements MinecraftVersionAdaptable {
	private static final class VersionKey {
		private final boolean customManifest, optifine;
		private final String value;

		public static VersionKey forVersion(String minecraftVersion) {
			return forVersion(minecraftVersion, false);
		}

		public static VersionKey forVersion(String minecraftVersion, boolean withOptifine) {
			return new VersionKey(false, minecraftVersion, withOptifine);
		}

		public static VersionKey forManifest(String manifestURL) {
			return new VersionKey(true, manifestURL, false);
		}

		private VersionKey(boolean customManifest, String value, boolean withOptifine) {
			this.customManifest = customManifest;
			optifine = withOptifine;
			this.value = value;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof VersionKey)) return false;

			VersionKey that = (VersionKey) obj;
			return customManifest == that.customManifest && optifine == that.optifine && Objects.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return Boolean.hashCode(customManifest) * value.hashCode() * Boolean.hashCode(optifine);
		}
	}
	public static class MinecraftVersion implements MinecraftVersionAdaptable {
		private final MinecraftVersionInfo versionInfo;
		private final File clientJar;
		private final File serverJar;

		private final JarMergeOrder mergeOrder;
		private final ForkJoinTask<Path> jarMerger;
		volatile Path mappings;

		MinecraftVersion(Project project, MinecraftVersionInfo versionInfo, File clientJar, File serverJar, JarMergeOrder mergeOrder, File mergedJar) {
			this.versionInfo = versionInfo;
			this.clientJar = clientJar;
			this.serverJar = serverJar;
			this.mergeOrder = mergeOrder;
			this.jarMerger = ForkJoinPool.commonPool().submit(makeMergeTask(project, project.getLogger(), mergedJar));
		}

		private Callable<Path> makeMergeTask(Project project, Logger logger, File mergedJar) {
			switch (mergeOrder) {
			case FIRST:
				return () -> {
					if (!mergedJar.exists()) {
						try {
							mergeJars(logger, clientJar, serverJar, mergedJar);
						} catch (ZipError e) {
							DownloadUtil.delete(clientJar);
							DownloadUtil.delete(serverJar);

							logger.error("Could not merge JARs! Deleting source JARs - please re-run the command and move on.", e);
							throw new RuntimeException("Error merging " + clientJar + " and " + serverJar, e);
						}
					}

					return mergedJar.toPath();
				};

			case LAST:
				return () -> {//TODO: Account for Openfine (or any other jar interfering tasks) changing the merged jar name
					if (!mergedJar.exists()) {
						synchronized (this) {
							while (mappings == null) {
								wait();
							}
						}
						JarNamingStrategy nameStrategy = makeNamingStrategy();

						Path interClient = mergedJar.toPath().resolve(JarNameFactory.CLIENT_INTERMEDIARY.getJarName(nameStrategy));
						if (Files.notExists(interClient)) {
							//Can't use the library provider yet as the configuration might need more things adding to it
							Set<File> libraries = getJavaLibraries(project);
							MapJarsTiny.remapJar(logger, clientJar.toPath(), mappings, false, libraries, interClient, "client");
						}

						Path interServer = interClient.resolveSibling(JarNameFactory.SERVER_INTERMEDIARY.getJarName(nameStrategy));
						if (Files.notExists(interServer)) {
							Set<File> libraries = Collections.emptySet(); //The server contains all its own dependencies
							MapJarsTiny.remapJar(logger, serverJar.toPath(), mappings, false, libraries, interServer, "server");
						}

						MinecraftProvider.mergeJars(logger, interClient.toFile(), interServer.toFile(), mergedJar);
					}

					return mergedJar.toPath();
				};

			case CLIENT_ONLY:
				return Callables.returning(clientJar.toPath());

			case SERVER_ONLY:
				return Callables.returning(serverJar.toPath());

			case INDIFFERENT:
			default:
				throw new IllegalStateException("Unexpected jar merge order " + mergeOrder);
			}
		}

		@Override
		public String getName() {
			return versionInfo.id;
		}

		public Collection<Library> getLibraries() {
			return Collections.unmodifiableList(versionInfo.libraries);
		}

		@Override
		public Set<File> getJavaLibraries(Project project) {
			return project.getConfigurations().detachedConfiguration(getLibraries().stream().filter(library -> library.allowed() && !library.isNative())
					.map(library -> project.getDependencies().module(library.getArtifactName())).toArray(Dependency[]::new)).getFiles();
		}

		public AssetIndex getAssetIndex() {
			return versionInfo.assetIndex;
		}

		public File getClientJar() {
			return clientJar;
		}

		public File getServerJar() {
			return serverJar;
		}

		@Override
		public Path getMergedJar() {
			return jarMerger.join();
		}

		@Override
		public JarMergeOrder getMergeStrategy() {
			return mergeOrder;
		}

		@Override
		public boolean needsIntermediaries() {
			return mergeOrder == JarMergeOrder.LAST && !jarMerger.isDone() && mappings == null;
		}

		@Override
		public void giveIntermediaries(Path mappings) {
			if (!jarMerger.isDone()) {
				synchronized (this) {
					if (this.mappings == null) {
						this.mappings = mappings;
						notify();
					}
				}
			}
		}

		@Override
		public Path getOrFindIntermediaries(LoomGradleExtension extension) {
			if (mappings != null) return mappings;

			return MinecraftVersionAdaptable.super.getOrFindIntermediaries(extension);
		}
	}
	private static final Map<VersionKey, Map<JarMergeOrder, MinecraftVersion>> VERSION_TO_VERSION = new ConcurrentHashMap<>();
	private static final byte DOWNLOAD_ATTEMPTS = 3;
	private static final Gson GSON = new Gson();

	public String minecraftVersion;
	private MinecraftVersion version;

	@Override
	public void register(LoomDependencyManager dependencyManager) {
		super.register(dependencyManager);

		dependencyManager.addProvider(new MinecraftLibraryProvider());
	}

	@Override
	protected void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		minecraftVersion = dependency.getDependency().getVersion();

		VersionKey key = extension.customManifest != null ? VersionKey.forManifest(extension.customManifest) : VersionKey.forVersion(minecraftVersion, extension.hasOptiFine());
		Map<JarMergeOrder, MinecraftVersion> mergeToVersion = VERSION_TO_VERSION.computeIfAbsent(key, k -> new EnumMap<>(JarMergeOrder.class));

		JarMergeOrder mergeOrder = extension.getJarMergeOrder();
		version = mergeToVersion.get(mergeOrder);

		if (version == null) {
			synchronized (mergeToVersion) {
				MinecraftVersion version = mergeToVersion.get(mergeOrder);

				if (version == null) {
					version = makeMergedJar(project, extension, minecraftVersion, Optional.ofNullable(extension.customManifest), mergeOrder,
						(projectAgain, versionInfo, clientJar, serverJar, actualMergeOrder, mergedJar) -> {
							JarNamingStrategy nameStrategy;
							if (extension.hasOptiFine() && actualMergeOrder != JarMergeOrder.SERVER_ONLY) {
								try {
									nameStrategy = Openfine.process(projectAgain.getLogger(), versionInfo.id, clientJar, serverJar, extension.getOptiFine());

									File optiCache = new File(clientJar.getParentFile(), "optifine");
									clientJar = new File(optiCache, JarNameFactory.CLIENT.getJarName(nameStrategy));
									mergedJar = new File(optiCache, JarNameFactory.MERGED.getJarName(nameStrategy));

									addDependency("com.github.Chocohead:OptiSine:" + Openfine.VERSION, projectAgain, Constants.MINECRAFT_DEPENDENCIES);
									GradleSupport.onlyForGroupMatching(projectAgain, AbstractPlugin.addMavenRepo(projectAgain, "Jitpack", "https://jitpack.io/"), "^([Cc][Oo][Mm]|[Ii][Oo])\\.[Gg][Ii][Tt][Hh][Uu][Bb]\\."); //Needed to fetch OptiSine from
								} catch (IOException e) {
									throw new UncheckedIOException("Error processing Optifine jar from " + extension.getOptiFine(), e);
								}
							} else {
								nameStrategy = JarNamingStrategy.forVersion(versionInfo.id);
							}

							return new MinecraftVersion(projectAgain, versionInfo, clientJar, serverJar, actualMergeOrder, mergedJar) {
								@Override
								public JarNamingStrategy makeNamingStrategy() {
									return nameStrategy;
								}

								@Override
								public Set<File> getJavaLibraries(Project project) {
									return getLibraryProvider().getLibraries();
								}
							};
					});

					mergeToVersion.put(mergeOrder, version);
					if (mergeOrder == JarMergeOrder.INDIFFERENT) {
						mergeToVersion.put(version.getMergeStrategy(), version);
					}
				}

				this.version = version;
			}
		}
	}


	public static JarMergeOrder findMergeStrategy(Project project, LoomGradleExtension extension, String version) {
		Map<JarMergeOrder, MinecraftVersion> mergeToVersion = VERSION_TO_VERSION.get(VersionKey.forVersion(version));
		if (mergeToVersion != null && mergeToVersion.containsKey(JarMergeOrder.INDIFFERENT)) return mergeToVersion.get(JarMergeOrder.INDIFFERENT).getMergeStrategy();

		MinecraftVersionInfo versionInfo;
		try (FileReader reader = new FileReader(downloadMcJson(project.getLogger(), extension, version, project.getGradle().getStartParameter().isOffline(), Optional.empty()))) {
			versionInfo = GSON.fromJson(reader, MinecraftVersionInfo.class);
		} catch (IOException e) {
			throw new UncheckedIOException("Error processing Minecraft JSON for " + version, e);
		}
		SpecialCases.enhanceVersion(versionInfo, JarMergeOrder.INDIFFERENT);

		Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Stockholm"));
		calendar.set(2012, Calendar.JULY, 22); //Day before 12w30a
		return calendar.getTime().before(versionInfo.releaseTime) ? JarMergeOrder.FIRST : JarMergeOrder.LAST;
	}

	public static MinecraftVersion makeMergedJar(Project project, LoomGradleExtension extension, String version, Optional<String> customManifest, JarMergeOrder mergeOrder) throws IOException {
		return makeMergedJar(project, extension, version, customManifest, mergeOrder, MinecraftVersion::new);
	}

	private static MinecraftVersion makeMergedJar(Project project, LoomGradleExtension extension, String version, Optional<String> customManifest, JarMergeOrder mergeOrder,
			HexaFunction<Project, MinecraftVersionInfo, File, File, JarMergeOrder, File, MinecraftVersion> versionFactory) throws IOException {
		boolean offline = project.getGradle().getStartParameter().isOffline();

		MinecraftVersionInfo versionInfo;
		try (FileReader reader = new FileReader(downloadMcJson(project.getLogger(), extension, version, offline, customManifest))) {
			versionInfo = GSON.fromJson(reader, MinecraftVersionInfo.class);
		}
		SpecialCases.enhanceVersion(versionInfo, mergeOrder);

		if (mergeOrder == JarMergeOrder.INDIFFERENT) {
			Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Stockholm"));
			calendar.set(2012, Calendar.JULY, 22); //Day before 12w30a
			mergeOrder = calendar.getTime().before(versionInfo.releaseTime) ? JarMergeOrder.FIRST : JarMergeOrder.LAST;
		}

		boolean needClient = extension.getJarMergeOrder() != JarMergeOrder.SERVER_ONLY;
		boolean needServer = extension.getJarMergeOrder() != JarMergeOrder.CLIENT_ONLY;

		JarNamingStrategy nameStrategy = JarNamingStrategy.forVersion(version);
		File clientJar = new File(extension.getUserCache(), JarNameFactory.CLIENT.getJarName(nameStrategy));
		File serverJar = new File(extension.getUserCache(), JarNameFactory.SERVER.getJarName(nameStrategy));
		File mergedJar = new File(extension.getUserCache(), mergeOrder.getJarName(nameStrategy));

		if (offline) {
			if ((!needClient || clientJar.exists()) && (!needServer || serverJar.exists())) {
				project.getLogger().debug("Found client and server " + version + " jars, presuming up-to-date");
			} else if (mergedJar.exists()) {
				//Strictly we don't need the split jars if the merged one exists, let's try go on
				project.getLogger().warn("Missing game jar(s) but merged " + version + " jar present, things might end badly");
			} else {
				throw new GradleException("Missing " + version + " jar(s); Client: " + clientJar.exists() + ", Server: " + serverJar.exists());
			}
		} else {
			if (needClient) downloadJar(project.getLogger(), version, versionInfo, clientJar, "client");
			if (needServer) downloadJar(project.getLogger(), version, versionInfo, serverJar, "server");
		}

		return versionFactory.apply(project, versionInfo, clientJar, serverJar, mergeOrder, mergedJar);
	}

	private static File downloadMcJson(Logger logger, LoomGradleExtension extension, String minecraftVersion, boolean offline, Optional<String> customManifest) throws IOException {
		File MINECRAFT_JSON = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-info.json");

		if (offline) {
			if (MINECRAFT_JSON.exists()) {
				//If there is the manifest already we'll presume that's good enough
				logger.debug("Found Minecraft {} manifest, presuming up-to-date", minecraftVersion);
			} else {
				//If we don't have the manifests then there's nothing more we can do
				throw new GradleException("Minecraft " + minecraftVersion + " manifest not found at " + MINECRAFT_JSON.getAbsolutePath());
			}
		} else {
			Optional<String> versionURL;
			if (customManifest.isPresent()) {
				logger.lifecycle("Using custom minecraft manifest");

				versionURL = customManifest;
			} else {
				File manifests = new File(extension.getUserCache(), "version_manifest.json");

				logger.debug("Downloading version manifests");
				DownloadUtil.downloadIfChanged(new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"), manifests, logger);

				try (Reader versionManifest = Files.newBufferedReader(manifests.toPath(), StandardCharsets.UTF_8)) {
					ManifestVersion mcManifest = GSON.fromJson(versionManifest, ManifestVersion.class);
					versionURL = mcManifest.versions.stream().filter(versions -> versions.id.equalsIgnoreCase(minecraftVersion)).findFirst().map(version -> version.url);
				}

				out: if (!versionURL.isPresent()) {
					String url;
					switch (minecraftVersion) {
					case "1.14.3 - Combat Test":
						logger.warn("Using old name for first Combat Test, should use 1.14_combat-212796 instead");
					case "1.14_combat-212796": //Combat Test 1
						//Extracted from https://launcher.mojang.com/experiments/combat/610f5c9874ba8926d5ae1bcce647e5f0e6e7c889/1_14_combat-212796.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.14_combat-212796.json";

					case "1.14_combat-0": //Combat Test 2
						//Extracted from https://launcher.mojang.com/experiments/combat/d164bb6ecc5fca9ac02878c85f11befae61ac1ca/1_14_combat-0.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.14_combat-0.json";
						break;

					case "1.14_combat-3": //Combat Test 3
						//Extracted from https://launcher.mojang.com/experiments/combat/0f209c9c84b81c7d4c88b4632155b9ae550beb89/1_14_combat-3.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.14_combat-3.json";
						break;

					case "1.15_combat-1": //Combat Test 4
						//Extracted from https://launcher.mojang.com/experiments/combat/ac11ea96f3bb2fa2b9b76ab1d20cacb1b1f7ef60/1_15_combat-1.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.15_combat-1.json";
						break;

					case "1_15_combat-6": //Combat Test 5
						//Extracted from https://launcher.mojang.com/experiments/combat/52263d42a626b40c947e523128f7a195ec5af76a/1_15_combat-6.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.15_combat-6.json";
						break;

					case "1.16_combat-0":
						logger.warn("Using Mojang name for sixth Combat Test, Intermediaries are published as 1_16_combat-0 instead");
					case "1_16_combat-0": //Combat Test 6
						//Extracted from https://launcher.mojang.com/experiments/combat/5a8ceec8681ed96ab6ecb9607fb5d19c8a755559/1_16_combat-0.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/139228e3b641866284e111b148d028236f51ddc9/1.16_combat-0.json";
						break;

					case "1.16_combat-3": //Combat Test 7c
						//Extracted from https://launcher.mojang.com/experiments/combat/2557b99d95588505e988886220779087d7d6b1e9/1_16_combat-3.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/dea3c65606f9bdb868fa73ab90c61bfcc64bc25d/1.16_combat-3.json";
						break;

					default:
						break out;
					}

					versionURL = Optional.of(url);
				}
			}

			if (versionURL.isPresent()) {
				if (StaticPathWatcher.INSTANCE.hasFileChanged(MINECRAFT_JSON.toPath())) {
					logger.debug("Downloading Minecraft {} manifest", minecraftVersion);
					DownloadUtil.downloadIfChanged(new URL(versionURL.get()), MINECRAFT_JSON, logger);
					StaticPathWatcher.INSTANCE.resetFile(MINECRAFT_JSON.toPath());
				}
			} else {
				throw new RuntimeException("Failed to find minecraft version: " + minecraftVersion);
			}
		}

		return MINECRAFT_JSON;
	}

	private static void downloadJar(Logger logger, String minecraftVersion, MinecraftVersionInfo versionInfo, File to, String name) throws IOException {
		downloadJar(logger, minecraftVersion, new URL(versionInfo.downloads.get(name).url), to, name, versionInfo.downloads.get(name).sha1);
	}

	private static void downloadJar(Logger logger, String minecraftVersion, URL from, File to, String name, String hash) throws IOException {
		if (!to.exists() || !Checksum.equals(to, hash) && StaticPathWatcher.INSTANCE.hasFileChanged(to.toPath())) {
			logger.debug("Downloading Minecraft {} {} jar", minecraftVersion, name);

			int attempt = 1;
			do {
				DownloadUtil.delete(to); //Clear the existing (wrong) contents out of the way
				DownloadUtil.downloadIfChanged(from, to, logger);
			} while (attempt++ <= DOWNLOAD_ATTEMPTS && !Checksum.equals(to, hash));

			if (attempt > DOWNLOAD_ATTEMPTS) {//Apparently we just couldn't get a jar which had the right hash
				throw new IllegalStateException("Unable to successfully download an intact " + minecraftVersion + ' ' + name + " jar!");
			}

			StaticPathWatcher.INSTANCE.resetFile(to.toPath());
		}
	}

	private static void mergeJars(Logger logger, File MINECRAFT_CLIENT_JAR, File MINECRAFT_SERVER_JAR, File MINECRAFT_MERGED_JAR) throws IOException {
		logger.lifecycle(":merging jars");

		try (JarMerger jarMerger = new JarMerger(MINECRAFT_CLIENT_JAR, MINECRAFT_SERVER_JAR, MINECRAFT_MERGED_JAR)) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}
	}


	@Override
	public String getName() {
		return minecraftVersion;
	}

	@Override
	public JarNamingStrategy makeNamingStrategy() {
		JarNamingStrategy out = version.makeNamingStrategy();

		MappingsProvider mappings = getProvider(MappingsProvider.class);
		if (mappings.mappingsName != null) out = out.withMappings(mappings.mappingsName + '-' + mappings.mappingsVersion);

		return out;
	}

	@Override
	public JarMergeOrder getMergeStrategy() {
		return version.getMergeStrategy();
	}

	@Override
	public boolean needsIntermediaries() {
		return version.needsIntermediaries();
	}

	@Override
	public void giveIntermediaries(Path mappings) {
		version.giveIntermediaries(mappings);
	}

	@Override
	public Path getOrFindIntermediaries(LoomGradleExtension extension) {
		return version.getOrFindIntermediaries(extension);
	}

	@Override
	public Path getMergedJar() {
		//Strictly this is only a problem if the main thread did this, but that's a pain to detect so we'll just be safe
		if (needsIntermediaries()) throw new IllegalStateException("Impending deadlock blocked");

		return version.getMergedJar();
	}

	public MinecraftLibraryProvider getLibraryProvider() {
		return getProvider(MinecraftLibraryProvider.class);
	}

	public Collection<Library> getLibraries() {
		return version.getLibraries();
	}

	@Override
	public Set<File> getJavaLibraries(Project project) {
		return version.getJavaLibraries(project);
	}

	public AssetIndex getAssetIndex() {
		return version.getAssetIndex();
	}

	public void clearCache() {
		VERSION_TO_VERSION.clear();
	}

	@Override
	public String getTargetConfig() {
		return Constants.MINECRAFT;
	}

	@Override
	public boolean isRequired() {
		return true;
	}

	@Override
	public boolean isUnique() {
		return true;
	}
}