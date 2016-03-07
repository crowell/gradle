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

package org.gradle.api

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.util.GradleVersion

class CrossBuildScriptCachingIntegrationSpec extends AbstractIntegrationSpec {

    FileTreeBuilder root
    File cachesDir
    File scriptCachesDir
    File remappedCachesDir

    def setup() {
        executer.requireOwnGradleUserHomeDir()
        executer.requireIsolatedDaemons()
        root = new FileTreeBuilder(testDirectory)
        cachesDir = new File(testDirectoryProvider.getTestDirectory().file("user-home"), 'caches')
        def versionCaches = new File(cachesDir, GradleVersion.current().version)
        scriptCachesDir = new File(versionCaches, 'scripts')
        remappedCachesDir = new File(versionCaches, 'scripts-remapped')
    }

    def "identical build files are compiled once"() {
        given:
        root {
            core {
                'core.gradle'(this.simpleBuild())
            }
            module1 {
                'module1.gradle'(this.simpleBuild())
            }
            'settings.gradle'(settings('core', 'module1'))
        }

        when:
        run 'help'

        then:
        def settingsHash = uniqueRemapped('settings')
        def coreHash = uniqueRemapped('core')
        def module1Hash = uniqueRemapped('module1')

        and:
        remappedCacheSize() == 3 // one for each build script
        scriptCacheSize() == 2 // one for settings, one for the 2 identical scripts
        coreHash == module1Hash
        hasCachedScripts(settingsHash, coreHash)
    }

    def "can have two build files with same contents and file name"() {
        given:
        root {
            core {
                'build.gradle'(this.simpleBuild())
            }
            module1 {
                'build.gradle'(this.simpleBuild())
            }
            'settings.gradle'("include 'core', 'module1'")
        }

        when:
        run 'help'

        then:
        def settingsHash = uniqueRemapped('settings')
        def buildHashes = hasRemapped('build')

        and:
        remappedCacheSize() == 3 // one for each build script
        scriptCacheSize() == 2 // one for settings, one for the 2 identical scripts
        buildHashes.size() == 2 // two build.gradle files in different dirs
        hasCachedScripts(settingsHash, *buildHashes)
    }

    def "can have two build files with different contents and same file name"() {
        given:
        root {
            core {
                'build.gradle'(this.simpleBuild())
            }
            module1 {
                'build.gradle'(this.simpleBuild('different contents'))
            }
            'settings.gradle'("include 'core', 'module1'")
        }

        when:
        run 'help'

        then:
        def settingsHash = uniqueRemapped('settings')
        def buildHashes = hasRemapped('build')

        and:
        remappedCacheSize() == 3 // one for each build script
        scriptCacheSize() == 3 // one for settings, one for each build.gradle file
        buildHashes.size() == 2 // two build.gradle files in different dirs
        hasCachedScripts(settingsHash, *buildHashes)
    }

    def "cache size increases when build file changes"() {
        given:
        root {
            core {
                'build.gradle'(this.simpleBuild())
            }
            module1 {
                'build.gradle'(this.simpleBuild())
            }
            'settings.gradle'("include 'core', 'module1'")
        }
        run 'help'
        sleep(500)

        when:
        root {
            module1 {
                'build.gradle'(this.simpleBuild('different contents'))
            }
        }
        run 'help'

        then:
        def settingsHash = uniqueRemapped('settings')
        def buildHashes = hasRemapped('build')
        remappedCacheSize() == 3 // one for each build script
        scriptCacheSize() == 3 // one for settings, one for each build.gradle file
        buildHashes.size() == 3 // two build.gradle files in different dirs + one new build.gradle
        hasCachedScripts(settingsHash, *buildHashes)

    }

    def "script is cached in build scope in-memory cache"() {
        executer.requireDaemon()

        given:
        root {
            gradle {
                'collectStats.gradle'('''
                    def buildScopeCache = project.services.get(org.gradle.groovy.scripts.ScriptCompilerFactory).scriptClassCompiler
                    def crossBuildScopeCache = gradle.services.get(org.gradle.groovy.scripts.internal.CrossBuildInMemoryCachingScriptClassCache)

                    def toSimpleNames(keys) {
                        keys.collect { key ->
                            String className = key.className
                            String dslId = key.dslId
                            "${className.substring(0, className.indexOf('_'))}:$dslId"
                        }
                    }

                    gradle.buildFinished {
                        println "Build scope cache size: ${buildScopeCache.cachedCompiledScripts.size()}"
                        println "Cross-build scope cache size: ${crossBuildScopeCache.cachedCompiledScripts.size()}"
                        println "Build scope cache contents: ${toSimpleNames(buildScopeCache.cachedCompiledScripts.keySet())}"
                        println "Cross-build scope cache contents: ${toSimpleNames(crossBuildScopeCache.cachedCompiledScripts.asMap().keySet())}"
                        println "Cross-build scope cache stats: ${crossBuildScopeCache.cachedCompiledScripts.stats()}"
                    }
                    ''')
            }
            'build.gradle'('apply from: "gradle/collectStats.gradle"')
        }
        Set scripts = ['settings:cp_settings', 'build:cp_proj', 'settings:settings', 'build:proj', 'collectStats:cp_dsl', 'collectStats:dsl']

        when:
        run 'help'
        def stats = crossBuildCacheStats()

        then:
        buildScopeCacheSize() == scripts.size()
        crossBuildScopeCacheSize() == scripts.size()

        buildScopeCacheContents() == scripts
        crossBuildScopeCacheContents() == scripts
        stats.hitCount == 0
        stats.missCount == scripts.size()

        when:
        run 'help'
        stats = crossBuildCacheStats()

        then:
        buildScopeCacheSize() == scripts.size()
        crossBuildScopeCacheSize() == scripts.size()
        buildScopeCacheContents() == scripts
        crossBuildScopeCacheContents() == scripts
        stats.hitCount == scripts.size()
        stats.missCount == scripts.size()
    }

    def "remapping scripts doesn't mix up classes with same name"() {
        given:
        root {
            'build.gradle'('''
                    task greet()
                    apply from: "gradle/one.gradle"
                    apply from: "gradle/two.gradle"
                ''')
            gradle {
                'one.gradle'('''
                    class Greeter { String toString() { 'Greetings from One!' } }
                    greet.doLast() { println new Greeter() }
                ''')

                'two.gradle'('''
                    class Greeter { String toString() { 'Greetings from Two!' } }
                    greet.doLast() { println new Greeter() }
                ''')
            }
        }

        when:
        run 'greet'

        then:
        outputContains 'Greetings from One!'
        outputContains 'Greetings from Two!'
    }

    int buildScopeCacheSize() {
        def m = output =~ /(?s).*Build scope cache size: (\d+).*/
        m.matches()
        m.group(1).toInteger()
    }

    int crossBuildScopeCacheSize() {
        def m = output =~ /(?s).*Cross-build scope cache size: (\d+).*/
        m.matches()
        m.group(1).toInteger()
    }

    Set<String> buildScopeCacheContents() {
        def m = output =~ /(?s).*Build scope cache contents: \[(.+?)\].*/
        m.matches()
        m.group(1).split(", ") as Set
    }

    Set<String> crossBuildScopeCacheContents() {
        def m = output =~ /(?s).*Cross-build scope cache contents: \[(.+?)\].*/
        m.matches()
        m.group(1).split(", ") as Set
    }

    Map<String, Integer> crossBuildCacheStats() {
        def m = output =~ /(?s).*Cross-build scope cache stats: CacheStats\{((?:(?:\p{Alnum}+=\d+)(?:, )?)+)}.*/
        m.matches()
        def stats = [:]
        m = m.group(1) =~ /(\p{Alnum}+)=(\d+)/
        while (m.find()) {
            stats[m.group(1)] = m.group(2).toInteger()
        }
        stats
    }

    List<String> hasRemapped(String buildFile) {
        def remapped = remappedCachesDir.listFiles().findAll { it.name.startsWith(buildFile) }
        if (remapped) {
            def contentHash = remapped*.list().flatten()
            return contentHash
        }
        throw new AssertionError("Cannot find a remapped build script for '${buildFile}.gradle'")
    }

    String uniqueRemapped(String buildFile) {
        def hashes = hasRemapped(buildFile)
        assert hashes.size() == 1
        hashes[0]
    }

    int remappedCacheSize() {
        remappedCachesDir.list().length
    }

    int scriptCacheSize() {
        scriptCachesDir.list().length
    }

    void hasCachedScripts(String... contentHashes) {
        Set foundInCache = scriptCachesDir.list() as Set
        Set expected = contentHashes as Set
        assert foundInCache == expected
    }

    String simpleBuild(String comment = '') {
        """
            // ${comment}
            apply plugin:'java'
        """
    }

    String settings(String... projects) {
        String includes = "include ${projects.collect { "'$it'" }.join(', ')}"
        """
            $includes
            rootProject.children.each { project ->
                project.projectDir = new File(project.name)
                project.buildFileName = "\${project.name}.gradle"
            }
        """
    }
}
