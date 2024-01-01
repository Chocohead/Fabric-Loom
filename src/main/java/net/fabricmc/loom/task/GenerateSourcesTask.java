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

package net.fabricmc.loom.task;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.api.decompilers.DecompilationMetadata;
import net.fabricmc.loom.api.decompilers.LoomDecompiler;
import net.fabricmc.loom.util.LineNumberRemapper;
import net.fabricmc.loom.util.progress.ProgressLogger;

public class GenerateSourcesTask extends AbstractLoomTask {
	public final LoomDecompiler decompiler;
	private Object input;
	private Object output;
	private Object lineMap;
	private Object libraries;
	private final Map<String, Object> decompilerOptions = new HashMap<>();
	private boolean skipForking;

	@Inject
	public GenerateSourcesTask(LoomDecompiler decompiler) {
		this.decompiler = decompiler;

		setGroup("fabric");
	}

	@InputFile
	public File getInput() {
		return getProject().file(input);
	}

	public void setInput(Object input) {
		this.input = input;
	}

	@OutputFile
	public File getOutput() {
		return getProject().file(output);
	}

	public void setOutput(Object output) {
		this.output = output;
	}

	@OutputFile
	public File getLineMap() {
		return getProject().file(lineMap);
	}

	public void setLineMap(Object lineMap) {
		this.lineMap = lineMap;
	}

	@InputFiles
	public FileCollection getLibraries() {
		return getProject().files(libraries);
	}

	public void setLibraries(Object libraries) {
		this.libraries = libraries;
	}

	@Input
	public Map<String, Object> getDecompilerOptions() {
		return decompilerOptions;
	}

	public void decompilerOption(String name, Object value) {
		decompilerOptions.put(name, value);
	}

	@Internal
	public boolean isSkipForking() {
		return skipForking;
	}

	public void setSkipForking(boolean skipForking) {
		this.skipForking = skipForking;
	}

	@TaskAction
	public void doTask() throws Throwable {
		Path javaDocs = getExtension().getMappingsProvider().getDecompileMappings().toAbsolutePath();
		Collection<Path> libraries = getLibraries().getFiles().stream().map(File::toPath).collect(Collectors.toSet());
		DecompilationMetadata metadata = new DecompilationMetadata(getDecompilerOptions(), !isSkipForking(), javaDocs, libraries);

		Path compiledJar = getInput().toPath();
		assert Files.exists(compiledJar);
		Path sourcesDestination = getOutput().toPath();
		Files.deleteIfExists(sourcesDestination);
		Path linemap = getLineMap().toPath();
		Files.deleteIfExists(linemap);

		decompiler.decompile(compiledJar, sourcesDestination, linemap, metadata);

		if (Files.exists(linemap)) {
			Path lineMapped = new File(getTemporaryDir(), "line-mapped.jar").toPath();
			Files.deleteIfExists(lineMapped); //Just to make sure

			remapLineNumbers(compiledJar, linemap, lineMapped);

			Files.move(lineMapped, compiledJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} else {
			getLogger().info("Skipping line mapping as " + linemap + " doesn't exist");
		}
	}

	private void remapLineNumbers(Path oldCompiledJar, Path linemap, Path linemappedJarDestination) throws IOException {
		getLogger().info(":adjusting line numbers");
		LineNumberRemapper remapper = new LineNumberRemapper();
		remapper.readMappings(linemap.toFile());

		ProgressLogger progressLogger = ProgressLogger.getProgressFactory(getProject(), getClass().getName());
		progressLogger.start("Adjusting line numbers", "linemap");

		remapper.process(progressLogger, oldCompiledJar.toFile(), linemappedJarDestination.toFile());

		progressLogger.completed();
	}
}
