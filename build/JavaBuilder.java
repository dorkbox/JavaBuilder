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
import dorkbox.Oak;
import dorkbox.build.Project;
import dorkbox.build.ProjectJava;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.PreJarAction;
import dorkbox.license.License;
import dorkbox.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;


// @formatter:off
@Build.Builder
public class JavaBuilder {

    // ALSO copied over to JavaBuilder build dir (as an example), so make sure to update in both locations
    // ~/dorkbox/eclipse/jre/bin/java -jar dist/JavaBuilder_v1.3.jar build javabuilder
    // ~/dorkbox/eclipse/jre/bin/java -Xrunjdwp:transport=dt_socket,server=y,address=1044 -jar dist/JavaBuilder_v1.3.jar build javabuilder

    public static final String name = "JavaBuilder";
    public static final String version = "v1.3";

    public static final String root = BuildStrings.path(BuildStrings.ProjectPath.GitHub, name);
    public static final String src = BuildStrings.path(root, "src");

    public static List<License> license = License.list(Licenses.OAK.JavaBuilder,
                                                       Licenses.OAK.Scar,
                                                       Licenses.DorkboxUtil.DorkboxUtil,
                                                       Licenses.DorkboxUtil.MigBase64,
                                                       Licenses.DorkboxUtil.FilenameUtils,

                                                       Licenses.DorkboxUtil.Annotations,
                                                       Licenses.DorkboxUtil.AnnotationDetector,

                                                       LicenseLibs.BouncyCastle,
                                                       LicenseLibs.FastMD5,
                                                       LicenseLibs.LzmaJava,
                                                       LicenseLibs.SLF4J,

                                                       LicenseLibs.JavaTar,
                                                       LicenseLibs.WildCard,
                                                       LicenseLibs.OpenJDK,
                                                       LicenseLibs.YamlBeans);


    public static void build(BuildOptions buildOptions) throws Exception {
        buildOptions.compiler.targetJavaVersion = 6;

        // if we are running from a jar, DO NOT try to do certain things
        String utilSourcePath = Oak.get().getAbsolutePath();

        String distDir = FileUtil.normalizeAsFile(Build.path(root, "dist"));
        File dists = new File(distDir);
        dists.mkdirs();

        String libsPath = FileUtil.normalizeAsFile(Build.path(root, "libs"));

        BuildLog log = Build.log();

        PreJarAction preJarAction = new PreJarAction() {
            @Override
            public void executeBeforeJarHappens(File outputDir) throws IOException {
                Build.log().println("Installing license files...");
                File targetLocation = new File(outputDir, Build.path("dorkbox", "license"));
                License.install(targetLocation);
            }
        };

        // now build the project
        ProjectJava project = ProjectJava.create(name)
                        .classPath(new Paths(Build.path(root, "libs"), Project.Jar_Pattern, "!jdkRuntimes"))
                        .license(license)
                        .version(version)
                        .extraFiles(new Paths(root, "README.md"))
                        .sourcePath(src, Project.Java_Pattern)
                        .options(buildOptions)
                        .jar().overrideDate(Build.buildDate)
                              .mainClass(Oak.class)
                              .preJarAction(preJarAction)
                              .includeSourceAsSeparate()
                              .outputFile(Build.path(distDir, name + Project.JAR_EXTENSION));
        project.build();
    }
}
