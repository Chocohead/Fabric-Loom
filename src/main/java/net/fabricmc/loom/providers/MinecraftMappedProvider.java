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

package net.fabricmc.loom.providers;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;

import org.gradle.api.Project;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import net.fabricmc.loom.AbstractPlugin;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.DependencyProvider;
import net.fabricmc.loom.dependencies.LogicalDependencyProvider;
import net.fabricmc.loom.providers.openfine.Openfine;
import net.fabricmc.loom.util.AccessTransformerHelper;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.MapJarsTiny;
import net.fabricmc.stitch.util.Pair;

public class MinecraftMappedProvider extends LogicalDependencyProvider {
    public File MINECRAFT_MAPPED_JAR;
    public File MINECRAFT_INTERMEDIARY_JAR;

    @Override
    public Set<Class<? extends DependencyProvider>> getDependencies() {
    	return ImmutableSet.of(MinecraftProvider.class, MinecraftLibraryProvider.class, MappingsProvider.class);
    }

    @Override
    public void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
    	MinecraftProvider minecraftProvider = getProvider(MinecraftProvider.class);
    	MappingsProvider mappingsProvider = getProvider(MappingsProvider.class);

        if (!mappingsProvider.MAPPINGS_TINY.exists()) {
            throw new RuntimeException("mappings file not found");
        }

        if (!minecraftProvider.getMergedJar().exists()) {
            throw new RuntimeException("input merged jar not found");
        }

        String atOffset; //Explicitly flag AT'd jars differently to vanilla/stock ones
        File cache; //Save to the project cache when ATing to simplify flagging AT changes
        Set<Pair<String, String>> targets;
        boolean atChange = false;
        if (extension.hasAT()) {
        	atOffset = "-transformed";
        	cache = new File(extension.getRootProjectPersistentCache(), "access_transformed_jars");
        	cache.mkdir();

        	//Add the transformed jars repo so that Gradle can find Minecraft
        	project.getRepositories().flatDir(repo -> {
        		repo.setName("AccessTransformedJars");
				repo.dir(cache);
			});

        	project.getLogger().info("Negotiating access transformations...");
    		targets = AccessTransformerHelper.loadATs(extension.getAT());
    		project.getLogger().info("Access transformations solved for " + targets.size() + " targets");

    		File lastAT = new File(cache, "last-seen.at");
    		if (lastAT.exists() ? !AccessTransformerHelper.loadATs(lastAT).equals(targets) : !targets.isEmpty()) {
    			Files.copy(extension.getAT(), lastAT); //Replace the old with the new
    			atChange = true;
    		}
        } else {
        	atOffset = "";
        	cache = extension.getUserCache();
        	targets = Collections.emptySet();
        }

        String intermediaryJar = minecraftProvider.minecraftVersion + "-intermediary" + atOffset + '-' + mappingsProvider.mappingsName;
        MINECRAFT_INTERMEDIARY_JAR = new File(cache, "minecraft-" + intermediaryJar + ".jar");
        String mappedJar = minecraftProvider.minecraftVersion + "-mapped" + atOffset + '-' + mappingsProvider.mappingsName + '-' + mappingsProvider.mappingsVersion;

        // Create version directory, see modmuss50/fabric-loom@83d2741d and modmuss50/fabric-loom@01dda726
        File versionDirectory = new File(cache, mappedJar);
        if (!versionDirectory.exists()) {
            versionDirectory.mkdir();
        }

        MINECRAFT_MAPPED_JAR = new File(versionDirectory, "minecraft-" + mappedJar + ".jar");

        if (!getMappedJar().exists() || !getIntermediaryJar().exists() || atChange) {
            if (getMappedJar().exists()) {
                getMappedJar().delete();
            }
            if (getIntermediaryJar().exists()) {
                getIntermediaryJar().delete();
            }
            if (extension.hasOptiFine()) Openfine.applyBonusMappings(mappingsProvider.MAPPINGS_TINY);
            new MapJarsTiny().mapJars(minecraftProvider, this, project);
            if (!targets.isEmpty()) MapJarsTiny.transform(project, targets, this, mappingsProvider);
        }

        if (!MINECRAFT_MAPPED_JAR.exists()) {
            throw new RuntimeException("mapped jar not found");
        }

        AbstractPlugin.addDirectoryRepo(project, "UserMinecraftMapped", versionDirectory);
        addDependency("net.minecraft:minecraft:" + mappedJar, project, Constants.MINECRAFT_NAMED);
        addDependency("net.minecraft:minecraft:" + intermediaryJar, project, Constants.MINECRAFT_INTERMEDIARY);
    }

    public Collection<File> getMapperPaths() {
        return getProvider(MinecraftLibraryProvider.class).getLibraries();
    }

	public File getIntermediaryJar() {
		return MINECRAFT_INTERMEDIARY_JAR;
	}

    public File getMappedJar() {
        return MINECRAFT_MAPPED_JAR;
    }
}
