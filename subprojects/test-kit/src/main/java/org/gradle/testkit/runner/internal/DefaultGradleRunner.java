/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.testkit.runner.internal;

import org.apache.commons.io.output.WriterOutputStream;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.internal.GradleDistributionLocator;
import org.gradle.api.internal.classpath.DefaultGradleDistributionLocator;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.classloader.ClasspathUtil;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.testkit.runner.*;
import org.gradle.testkit.runner.internal.io.SynchronizedOutputStream;
import org.gradle.util.CollectionUtils;
import org.gradle.util.GUtil;

import java.io.File;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

public class DefaultGradleRunner extends GradleRunner {

    public static final String TEST_KIT_DIR_SYS_PROP = "org.gradle.testkit.dir";
    public static final String DEBUG_SYS_PROP = "org.gradle.testkit.debug";
    public static final String IMPLEMENTATION_CLASSPATH_PROP_KEY = "implementation-classpath";
    public static final String PLUGIN_METADATA_FILE_NAME = "plugin-under-test-metadata.properties";

    private final GradleExecutor gradleExecutor;

    private GradleProvider gradleProvider;
    private TestKitDirProvider testKitDirProvider;
    private File projectDirectory;
    private List<String> arguments = Collections.emptyList();
    private List<String> jvmArguments = Collections.emptyList();
    private ClassPath classpath = ClassPath.EMPTY;
    private boolean debug;
    private OutputStream standardOutput;
    private OutputStream standardError;
    private boolean forwardingSystemStreams;

    public DefaultGradleRunner() {
        this(new ToolingApiGradleExecutor(), calculateTestKitDirProvider(SystemProperties.getInstance()));
    }

    DefaultGradleRunner(GradleExecutor gradleExecutor, TestKitDirProvider testKitDirProvider) {
        this.gradleExecutor = gradleExecutor;
        this.testKitDirProvider = testKitDirProvider;
        this.debug = Boolean.getBoolean(DEBUG_SYS_PROP);
    }

    private static TestKitDirProvider calculateTestKitDirProvider(SystemProperties systemProperties) {
        Map<String, String> systemPropertiesMap = systemProperties.asMap();
        if (systemPropertiesMap.containsKey(TEST_KIT_DIR_SYS_PROP)) {
            return new ConstantTestKitDirProvider(new File(systemPropertiesMap.get(TEST_KIT_DIR_SYS_PROP)));
        } else {
            return new TempTestKitDirProvider(systemProperties);
        }
    }

    public TestKitDirProvider getTestKitDirProvider() {
        return testKitDirProvider;
    }

    @Override
    public GradleRunner withGradleVersion(String versionNumber) {
        this.gradleProvider = GradleProvider.version(versionNumber);
        return this;
    }

    @Override
    public GradleRunner withGradleInstallation(File installation) {
        this.gradleProvider = GradleProvider.installation(installation);
        return this;
    }

    @Override
    public GradleRunner withGradleDistribution(URI distribution) {
        this.gradleProvider = GradleProvider.uri(distribution);
        return this;
    }

    @Override
    public DefaultGradleRunner withTestKitDir(File testKitDir) {
        validateArgumentNotNull(testKitDir, "testKitDir");
        this.testKitDirProvider = new ConstantTestKitDirProvider(testKitDir);
        return this;
    }

    public DefaultGradleRunner withJvmArguments(List<String> jvmArguments) {
        this.jvmArguments = Collections.unmodifiableList(new ArrayList<String>(jvmArguments));
        return this;
    }

    public DefaultGradleRunner withJvmArguments(String... jvmArguments) {
        return withJvmArguments(Arrays.asList(jvmArguments));
    }

    @Override
    public File getProjectDir() {
        return projectDirectory;
    }

    @Override
    public DefaultGradleRunner withProjectDir(File projectDir) {
        this.projectDirectory = projectDir;
        return this;
    }

    @Override
    public List<String> getArguments() {
        return arguments;
    }

    @Override
    public DefaultGradleRunner withArguments(List<String> arguments) {
        this.arguments = Collections.unmodifiableList(new ArrayList<String>(arguments));
        return this;
    }

    @Override
    public DefaultGradleRunner withArguments(String... arguments) {
        return withArguments(Arrays.asList(arguments));
    }

    @Override
    public List<? extends File> getPluginClasspath() {
        return classpath.getAsFiles();
    }

    @Override
    public GradleRunner withPluginClasspath() {
        this.classpath = DefaultClassPath.of(readPluginClasspath());
        return this;
    }

    @Override
    public GradleRunner withPluginClasspath(Iterable<? extends File> classpath) {
        List<File> f = new ArrayList<File>();
        for (File file : classpath) {
            // These objects are going across the wire.
            // 1. Convert any subclasses back to File in case the subclass isn't available in Gradle.
            // 2. Make them absolute here to deal with a different root at the server
            f.add(new File(file.getAbsolutePath()));
        }
        if (!f.isEmpty()) {
            this.classpath = new DefaultClassPath(f);
        }
        return this;
    }

    @Override
    public boolean isDebug() {
        return debug;
    }

    @Override
    public GradleRunner withDebug(boolean flag) {
        this.debug = flag;
        return this;
    }

    @Override
    public GradleRunner forwardStdOutput(Writer writer) {
        if (forwardingSystemStreams) {
            forwardingSystemStreams = false;
            this.standardError = null;
        }
        validateArgumentNotNull(writer, "standardOutput");
        this.standardOutput = toOutputStream(writer);
        return this;
    }

    @Override
    public GradleRunner forwardStdError(Writer writer) {
        if (forwardingSystemStreams) {
            forwardingSystemStreams = false;
            this.standardOutput = null;
        }
        validateArgumentNotNull(writer, "standardError");
        this.standardError = toOutputStream(writer);
        return this;
    }

    @Override
    public GradleRunner forwardOutput() {
        forwardingSystemStreams = true;
        OutputStream systemOut = new SynchronizedOutputStream(System.out);
        this.standardOutput = systemOut;
        this.standardError = systemOut;
        return this;
    }

    private static OutputStream toOutputStream(Writer standardOutput) {
        return new WriterOutputStream(standardOutput, Charset.defaultCharset());
    }

    private void validateArgumentNotNull(Object argument, String argumentName) {
        if (argument == null) {
            throw new IllegalArgumentException(String.format("%s argument cannot be null", argumentName));
        }
    }

    @Override
    public BuildResult build() {
        return run(new Action<GradleExecutionResult>() {
            public void execute(GradleExecutionResult gradleExecutionResult) {
                if (!gradleExecutionResult.isSuccessful()) {
                    throw new UnexpectedBuildFailure(createDiagnosticsMessage("Unexpected build execution failure", gradleExecutionResult), createBuildResult(gradleExecutionResult));
                }
            }
        });
    }

    @Override
    public BuildResult buildAndFail() {
        return run(new Action<GradleExecutionResult>() {
            public void execute(GradleExecutionResult gradleExecutionResult) {
                if (gradleExecutionResult.isSuccessful()) {
                    throw new UnexpectedBuildSuccess(createDiagnosticsMessage("Unexpected build execution success", gradleExecutionResult), createBuildResult(gradleExecutionResult));
                }
            }
        });
    }

    @SuppressWarnings("StringBufferReplaceableByString")
    String createDiagnosticsMessage(String trailingMessage, GradleExecutionResult gradleExecutionResult) {
        String lineBreak = SystemProperties.getInstance().getLineSeparator();
        StringBuilder message = new StringBuilder();
        message.append(trailingMessage);
        message.append(" in ");
        message.append(getProjectDir().getAbsolutePath());
        message.append(" with arguments ");
        message.append(getArguments());

        String output = gradleExecutionResult.getOutput();
        if (output != null && !output.isEmpty()) {
            message.append(lineBreak);
            message.append(lineBreak);
            message.append("Output:");
            message.append(lineBreak);
            message.append(output);
        }

        return message.toString();
    }

    private BuildResult run(Action<GradleExecutionResult> resultVerification) {
        if (projectDirectory == null) {
            throw new InvalidRunnerConfigurationException("Please specify a project directory before executing the build");
        }

        File testKitDir = createTestKitDir(testKitDirProvider);

        GradleProvider effectiveDistribution = gradleProvider == null ? findGradleInstallFromGradleRunner() : gradleProvider;

        GradleExecutionResult execResult = gradleExecutor.run(new GradleExecutionParameters(
            effectiveDistribution,
            testKitDir,
            projectDirectory,
            arguments,
            jvmArguments,
            classpath,
            debug,
            standardOutput,
            standardError
        ));

        resultVerification.execute(execResult);
        return createBuildResult(execResult);
    }

    private BuildResult createBuildResult(GradleExecutionResult execResult) {
        return new FeatureCheckBuildResult(
            execResult.getBuildOperationParameters(),
            execResult.getOutput(),
            execResult.getTasks()
        );
    }

    private File createTestKitDir(TestKitDirProvider testKitDirProvider) {
        File dir = testKitDirProvider.getDir();
        if (dir.isDirectory()) {
            if (!dir.canWrite()) {
                throw new InvalidRunnerConfigurationException("Unable to write to test kit directory: " + dir.getAbsolutePath());
            }
            return dir;
        } else if (dir.exists() && !dir.isDirectory()) {
            throw new InvalidRunnerConfigurationException("Unable to use non-directory as test kit directory: " + dir.getAbsolutePath());
        } else if (dir.mkdirs() || dir.isDirectory()) {
            return dir;
        } else {
            throw new InvalidRunnerConfigurationException("Unable to create test kit directory: " + dir.getAbsolutePath());
        }
    }

    private static GradleProvider findGradleInstallFromGradleRunner() {
        GradleDistributionLocator gradleDistributionLocator = new DefaultGradleDistributionLocator(GradleRunner.class);
        File gradleHome = gradleDistributionLocator.getGradleHome();
        if (gradleHome == null) {
            String messagePrefix = "Could not find a Gradle installation to use based on the location of the GradleRunner class";
            try {
                File classpathForClass = ClasspathUtil.getClasspathForClass(GradleRunner.class);
                messagePrefix += ": " + classpathForClass.getAbsolutePath();
            } catch (Exception ignore) {
                // ignore
            }
            throw new InvalidRunnerConfigurationException(messagePrefix + ". Please specify a Gradle runtime to use via GradleRunner.withGradleVersion() or similar.");
        }
        return GradleProvider.installation(gradleHome);
    }

    private static List<File> readPluginClasspath() {
        URL pluginClasspathUrl = Thread.currentThread().getContextClassLoader().getResource(PLUGIN_METADATA_FILE_NAME);

        if (pluginClasspathUrl == null) {
            throw new InvalidPluginMetadataException(String.format("Test runtime classpath does not contain plugin metadata file '%s'", PLUGIN_METADATA_FILE_NAME));
        }

        Properties properties = GUtil.loadProperties(pluginClasspathUrl);
        if (!properties.containsKey(IMPLEMENTATION_CLASSPATH_PROP_KEY)) {
            throw new InvalidPluginMetadataException(String.format("Plugin metadata file '%s' does not contain expected property named '%s'", PLUGIN_METADATA_FILE_NAME, IMPLEMENTATION_CLASSPATH_PROP_KEY));
        }

        String value = properties.getProperty(IMPLEMENTATION_CLASSPATH_PROP_KEY);
        if (value != null) {
            value = value.trim();
        }

        if (value == null || value.isEmpty()) {
            throw new InvalidPluginMetadataException(String.format("Plugin metadata file '%s' has empty value for property named '%s'", PLUGIN_METADATA_FILE_NAME, IMPLEMENTATION_CLASSPATH_PROP_KEY));
        }

        String[] parsedImplementationClasspath = value.trim().split(File.pathSeparator);
        return CollectionUtils.collect(parsedImplementationClasspath, new Transformer<File, String>() {
            @Override
            public File transform(String classpathEntry) {
                return new File(classpathEntry);
            }
        });
    }

}
