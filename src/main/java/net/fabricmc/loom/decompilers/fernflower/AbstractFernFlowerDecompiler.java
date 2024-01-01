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

package net.fabricmc.loom.decompilers.fernflower;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.function.Supplier;

import com.google.common.collect.Maps;

import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.process.ExecResult;

import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import net.fabricmc.loom.api.decompilers.DecompilationMetadata;
import net.fabricmc.loom.api.decompilers.LoomDecompiler;
import net.fabricmc.loom.util.ConsumingOutputStream;
import net.fabricmc.loom.util.OperatingSystem;
import net.fabricmc.loom.util.progress.ProgressLogger;

public abstract class AbstractFernFlowerDecompiler implements LoomDecompiler {
	private final Project project;
	private final Logger logger;

	protected AbstractFernFlowerDecompiler(Project project) {
		this(project, project.getLogger());
	}

	protected AbstractFernFlowerDecompiler(Project project, Logger logger) {
		this.project = project;
		this.logger = logger;
	}

	public abstract Class<? extends AbstractForkedFFExecutor> fernFlowerExecutor();

	@Override
	public void decompile(Path compiledJar, Path sourcesDestination, Path linemapDestination, DecompilationMetadata metaData) {
		if (!OperatingSystem.is64Bit()) {
			throw new UnsupportedOperationException("FernFlower decompiler requires a 64bit JVM to run due to the memory requirements");
		}

		project.getLogging().captureStandardOutput(LogLevel.LIFECYCLE);

		Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        options.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1");
        options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
        options.put(IFernflowerPreferences.INDENT_STRING, "\t"); //Use a tab not three spaces :|
        options.put(IFernflowerPreferences.LOG_LEVEL, "trace");
        options.put(IFernflowerPreferences.WARN_INCONSISTENT_INNER_CLASSES, "0"); //Nothing the user can do about this
        options.put(IFernflowerPreferences.ERROR_MESSAGE, ""); //People shouldn't redistribute Minecraft classes
        options.putAll(Maps.transformValues(metaData.options, value -> {
        	if (value instanceof Boolean) {
        		return (Boolean) value ? "1" : "0";
        	}
        	return value;
        }));

		List<String> args = new ArrayList<>();

		options.forEach((k, v) -> args.add(MessageFormat.format("-{0}={1}", k, v)));
		args.add(absolutePathOf(compiledJar));
		args.add("-o=" + absolutePathOf(sourcesDestination));
		args.add("-l=" + absolutePathOf(linemapDestination));
		args.add("-m=" + absolutePathOf(metaData.javaDocs));

		//TODO, Decompiler breaks on jemalloc, J9 module-info.class?
		for (Path library : metaData.libraries) {
			args.add("-e=" + absolutePathOf(library));
		}

		ProgressLogger progressGroup = ProgressLogger.getProgressFactory(project, getClass().getName()).setDescription("Decompile");
        Supplier<ProgressLogger> loggerFactory = () -> {
            ProgressLogger child = progressGroup.newChild(getClass());
            child.setDescription("decompile worker");
            child.started();
            return child;
        };
        Stack<ProgressLogger> freeLoggers = new Stack<>();
        Map<String, ProgressLogger> inUseLoggers = new HashMap<>();

        OutputStream stdOutput = new ConsumingOutputStream(line -> {
            if (line.startsWith("Listening for transport")) {
                System.out.println(line);
                return;
            }

            int sepIdx = line.indexOf("::");
            if (sepIdx < 1) {
            	logger.error("Unprefixed line: " + line);
            	return;
            }
            String id = line.substring(0, sepIdx).trim();
            String data = line.substring(sepIdx + 2).trim();

            ProgressLogger logger = inUseLoggers.get(id);

            String[] segs = data.split(" ");
            if (segs[0].equals("waiting")) {
                if (logger != null) {
                    logger.progress("Idle..");
                    inUseLoggers.remove(id);
                    freeLoggers.push(logger);
                }
            } else {
                if (logger == null) {
                    if (!freeLoggers.isEmpty()) {
                        logger = freeLoggers.pop();
                    } else {
                        logger = loggerFactory.get();
                    }
                    inUseLoggers.put(id, logger);
                }

                if (data.startsWith(Severity.INFO.prefix)) {
                	logger.progress(data.substring(Severity.INFO.prefix.length()));
                } else if (data.startsWith(Severity.TRACE.prefix)) {
                	logger.progress(data.substring(Severity.TRACE.prefix.length()));
                } else if (data.startsWith(Severity.WARN.prefix)) {
                	this.logger.warn(data.substring(Severity.WARN.prefix.length()));
                } else {
                	this.logger.error(data.substring(Severity.ERROR.prefix.length()));
                }
            }
        });
        OutputStream errOutput = System.err;

        try {
	        progressGroup.started();

	        if (metaData.fork) {
		        ExecResult result = project.javaexec(spec -> {
		        	spec.classpath(getClasspath(project));
		            spec.setMain(fernFlowerExecutor().getName());
		            spec.jvmArgs("-Xms200m", "-Xmx3G");
		            spec.setArgs(args);
		            spec.setErrorOutput(errOutput);
		            spec.setStandardOutput(stdOutput);
		        });

		        result.rethrowFailure();
		        result.assertNormalExitValue();
	        } else {
	        	PrintStream out = System.out;
	        	PrintStream err = System.err;

	        	try {//The necessary classpath things are probably present...
	        		System.setOut(new PrintStream(stdOutput, true));
					System.setErr(new PrintStream(errOutput, true));

	        		Method main = fernFlowerExecutor().getDeclaredMethod("main", String[].class);
	        		assert main.isAccessible();
	        		main.invoke(null, new Object[] {args.toArray(new String[0])});
	        	} catch (InvocationTargetException e) {
	        		throw new RuntimeException("Error running " + fernFlowerExecutor(), e.getCause());
	        	} catch (ReflectiveOperationException e) {
	        		throw new RuntimeException("Error starting " + fernFlowerExecutor(), e);
	        	} finally {
					System.setOut(out);
					System.setErr(err);
				}
	        }
        } finally {
	        inUseLoggers.values().forEach(ProgressLogger::completed);
	        freeLoggers.forEach(ProgressLogger::completed);
	        progressGroup.completed();
        }
	}

	private static String absolutePathOf(Path path) {
		return path.toAbsolutePath().toString();
	}
}
