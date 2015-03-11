/*
 * Copyright 2014 the original author or authors.
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


package org.gradle.integtests.resource.s3.fixtures

import org.gradle.test.fixtures.maven.DelegatingMavenModule
import org.gradle.test.fixtures.maven.MavenFileModule
import org.gradle.test.fixtures.maven.MavenModule

class MavenS3Module extends DelegatingMavenModule<MavenS3Module> implements MavenModule {
    MavenFileModule backingModule
    S3Server server
    String bucket
    String repositoryPath

    MavenS3Module(S3Server server, MavenFileModule backingModule, String repositoryPath, String bucket) {
        this.bucket = bucket
        this.server = server
        this.backingModule = backingModule
        this.repositoryPath = repositoryPath
    }

    S3Resource getPom() {
        new S3Resource(server, pomFile, repositoryPath, bucket)
    }

    S3Resource getArtifact() {
        new S3Resource(server, artifactFile, repositoryPath, bucket)
    }

    S3Resource getMetaData() {
        new S3Resource(server, backingModule.metaDataFile, repositoryPath, bucket)
    }

    S3Resource getArtifactSha1() {
        new S3Resource(server, backingModule.sha1File(artifactFile), repositoryPath, bucket)
    }

    S3Resource getPomSha1() {
        new S3Resource(server, backingModule.sha1File(pomFile), repositoryPath, bucket)
    }

    S3Resource getMavenRootMetaData() {
        new S3Resource(server, backingModule.rootMetaDataFile, repositoryPath, bucket)
    }
}
