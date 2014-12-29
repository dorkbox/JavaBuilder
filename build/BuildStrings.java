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
import java.util.ArrayList;

import dorkbox.Build;
import dorkbox.license.License;
import dorkbox.util.FileUtil;

public class BuildStrings {

    // this is the ROOT name for this project and for the launcher. This is necessary because of how class parsing is done
    public static final String Dir_Pattern  = "./";

    public static final String STAGING = "staging";

    public static class ProjectPath {
        public static final String Resources = Build.path("..", "..", "resources");
        public static final String GitHub = Build.path("..", "..", "github_projects");
    }

    public static class JavaBuilder {
        public static final String name = "OAK";
        public static final String root = path(BuildStrings.ProjectPath.GitHub, name);
        public static final String src = path(root, "src");
        public static ArrayList<License> license = new ArrayList<License>() {
            private static final long serialVersionUID = 1L;
            {
                add(Licenses.OAK.OAK);
                add(Licenses.OAK.Scar);
                add(Licenses.DorkboxUtil.DorkboxUtil);
                add(Licenses.DorkboxUtil.MigBase64);
                add(Licenses.DorkboxUtil.FilenameUtils);
                add(Licenses.DorkboxUtil.AnnotationDetector);

                add(LicenseLibs.BouncyCastle);
                add(LicenseLibs.FastMD5);
                add(LicenseLibs.LzmaJava);
                add(LicenseLibs.SLF4J);

                add(LicenseLibs.JavaTar);
                add(LicenseLibs.WildCard);
                add(LicenseLibs.OpenJDK);
                add(LicenseLibs.YamlBeans);
            }
        };
    }

    private static String path(String... path) {
        return FileUtil.normalizeAsFile(Build.path(path));
    }
}
