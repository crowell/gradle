/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.plugin.devel.plugins.internal.tasks;

import com.google.common.base.Joiner;
import org.gradle.api.DefaultTask;
import org.gradle.api.Incubating;
import org.gradle.api.Transformer;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.SystemProperties;
import org.gradle.util.CollectionUtils;
import org.gradle.util.GUtil;

import java.io.*;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Properties;

/**
 * Custom task for generating the metadata for a plugin user test.
 *
 * @since 2.13
 */
@Incubating
public class PluginUnderTestMetadata extends DefaultTask {

    public static final String IMPLEMENTATION_CLASSPATH_PROP_KEY = "implementation-classpath";
    public static final String METADATA_FILE_NAME = "plugin-under-test-metadata.properties";
    private FileCollection pluginClasspath;
    private File outputDirectory;

    /**
     * The code under test. Defaults to {@code sourceSets.main.runtimeClasspath}.
     */
    @InputFiles
    public FileCollection getPluginClasspath() {
        return pluginClasspath;
    }

    public void setPluginClasspath(FileCollection pluginClasspath) {
        this.pluginClasspath = pluginClasspath;
    }

    /**
     * The target output directory used for writing the classpath manifest. Defaults to {@code "$buildDir/$task.name"}.
     */
    @OutputDirectory
    public File getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    @TaskAction
    public void generate() {
        Properties properties = new Properties();

        if (!getPluginClasspath().isEmpty()) {
            List<String> paths = CollectionUtils.collect(getPluginClasspath(), new Transformer<String, File>() {
                @Override
                public String transform(File file) {
                    return file.getAbsolutePath().replaceAll("\\\\", "/");
                }
            });
            StringBuilder implementationClasspath = new StringBuilder();
            Joiner.on(File.pathSeparator).appendTo(implementationClasspath, paths);
            properties.setProperty(IMPLEMENTATION_CLASSPATH_PROP_KEY, implementationClasspath.toString());
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GUtil.saveProperties(properties, outputStream);

        try {
            OutputStream fos = null;

            try {
                fos = new FileOutputStream(new File(getOutputDirectory(), METADATA_FILE_NAME));
                fos.write(stripTimestamp(outputStream));
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] stripTimestamp(ByteArrayOutputStream outputStream) {
        Charset propertiesCharSet = Charset.forName("8859_1");
        String originalProperties = new String(outputStream.toByteArray(), propertiesCharSet);
        String lineSeparator = SystemProperties.getInstance().getLineSeparator();
        String modifiedProperties = originalProperties.substring(originalProperties.indexOf(lineSeparator) + lineSeparator.length());
        return modifiedProperties.getBytes(propertiesCharSet);
    }
}
