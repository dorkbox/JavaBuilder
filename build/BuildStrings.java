/*
 * Copyright 2012 dorkbox, llc
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
import dorkbox.Builder;
import dorkbox.util.FileUtil;

public class BuildStrings {

    // this is the ROOT name for this project and for the launcher. This is necessary because of how class parsing is done
    public static final String Dir_Pattern  = "./";

    public static final String STAGING = "staging";

    public static class ProjectPath {
        public static final String Resources = Builder.path("..", "..", "resources");
        public static final String GitHub = Builder.path("..", "..", "github_projects");
    }

    static String path(String... path) {
        return FileUtil.normalizeAsFile(Builder.path(path));
    }
}
