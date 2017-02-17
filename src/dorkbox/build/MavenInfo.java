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

import dorkbox.Version;

/**
 * Contains maven dependency info for a project
 */
public
class MavenInfo {
    private String groupId;
    private String artifactId;
    private Version version;
    private Scope scope;

    // for serialization
    private
    MavenInfo() {
    }

    public
    MavenInfo(final String groupId, final String artifactId, final Version version, final Scope scope) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.scope = scope;
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
    Version getVersion() {
        return version;
    }

    public
    Scope getScope() {
        return scope;
    }

    public
    MavenInfo setScope(final Scope scope) {
        this.scope = scope;
        return this;
    }

    public
    enum Scope {
        COMPILE, PROVIDED, RUNTIME, TEST, SYSTEM, IMPORT;
    }
}
