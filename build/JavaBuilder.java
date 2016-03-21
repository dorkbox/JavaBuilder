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

import com.esotericsoftware.wildcard.Paths;
import dorkbox.Build;
import dorkbox.BuildOptions;
import dorkbox.Builder;
import dorkbox.Instructions;
import dorkbox.Version;
import dorkbox.build.Project;
import dorkbox.build.ProjectJava;
import dorkbox.build.SimpleArgs;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.PreJarAction;
import dorkbox.license.License;
import dorkbox.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;


// @formatter:off
@Instructions
public class JavaBuilder {

    // ALSO copied over to JavaBuilder build dir (as an example), so make sure to update in both locations
    // ~/dorkbox/eclipse/jre/bin/java -jar dist/JavaBuilder.jar build javabuilder 
    // ~/dorkbox/eclipse/jre/bin/java -Xrunjdwp:transport=dt_socket,server=y,address=1044 -jar dist/JavaBuilder.jar build javabuilder

    public static final String name = "JavaBuilder";


    public static final String root = BuildStrings.path(BuildStrings.ProjectPath.GitHub, name);
    public static final String src = BuildStrings.path(root, "src");
    public static final File readme = new File(root, "README.md");

    // Version will increment/update only if the project needed to build. This is current, not the new one that will be used to build
    public static Version version = Version.get("v2.24").sourceFile(name, src, dorkbox.Build.class);

    public static List<License> license = License.list(Licenses.JavaBuilder.JavaBuilder,
                                                       Licenses.JavaBuilder.Scar,
                                                       Licenses.DorkboxUtil.DorkboxUtil,
                                                       Licenses.DorkboxUtil.MigBase64,
                                                       Licenses.DorkboxUtil.FilenameUtils,
                                                       Annotations.project,

                                                       LicenseLibs.JavaParser,
                                                       LicenseLibs.BouncyCastle,
                                                       LicenseLibs.FastMD5,
                                                       LicenseLibs.LzmaJava,
                                                       LicenseLibs.SLF4J,

                                                       LicenseLibs.JavaTar,
                                                       LicenseLibs.WildCard,
                                                       LicenseLibs.OpenJDK,
                                                       LicenseLibs.YamlBeans);

    public static class Dist {
        public static final String jar = BuildStrings.path(root, name + Project.JAR_EXTENSION);
        public static final String src = BuildStrings.path(root, name + Project.SRC_EXTENSION);
    }

    public static
    void dist(BuildOptions buildOptions, SimpleArgs args) throws Exception {
        String distPath = FileUtil.normalizeAsFile(Builder.path(root, "dist"));
        String libsPath = FileUtil.normalizeAsFile(Builder.path(root, "libs"));

        PreJarAction preJarAction = new PreJarAction() {
            @Override
            public void executeBeforeJarHappens(File outputDir) throws IOException {
                BuildLog.println("Installing license files...");
                File targetLocation = new File(outputDir, Builder.path("dorkbox", "license"));
                License.install(targetLocation);
            }
        };

        // now build the project
        Project project = ProjectJava.create(name)
                                     .license(license)
                                     .version(version)
                                     .description("Java project management and build tool, using the Java language.")

                                     .classPath(new Paths(Builder.path(root, "libs"), Project.Jar_Pattern, "!jdkRuntimes"))
                                     .extraFiles(readme)

                                     .options(buildOptions)
                                     .sourcePath(src, Project.Java_Pattern)
                                     .jar().overrideDate(Builder.buildDate)
                                           .mainClass(Build.class)
                                           .preJarAction(preJarAction)
                                           .includeSourceAsSeparate()
                                           .outputFile(Builder.path(distPath, name));

        // saves the version info if successful
        final boolean build = project.build(6);
        project.cleanup();
    }
}

