/*
 * Copyright 2015 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.build;

/**
 * Contains maven dependency info for a project
 */
public
class MavenInfo<T extends Project<T>> {

    private final String groupId;
    private final String artifactId;
    private final String version;

    public
    MavenInfo(final String groupId, final String artifactId, final String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public
    String getGroupId() {
        return groupId;
    }

    public
    String getArtifactId() {
        return artifactId;
    }

    public
    String getVersion() {
        return version;
    }
}
