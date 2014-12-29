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
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.Build;
import dorkbox.BuildOptions;
import dorkbox.build.ProjectBasics;
import dorkbox.build.ProjectJava;
import dorkbox.build.util.PreJarAction;
import dorkbox.license.License;
import dorkbox.util.FileUtil;
import dorkbox.util.LocationResolver;

@Build.Builder
public class OAK {

    // ALSO copied over to OAK build dir (as an example), so make sure to update in both locations
    // ~/dorkbox/eclipse/jre/bin/java -jar dist/OAK.jar build oak
    // ~/dorkbox/eclipse/jre/bin/java -Xrunjdwp:transport=dt_socket,server=y,address=1044 -jar dist/OAK.jar build oak

    public static final String name = "OAK";
    public static final String root = BuildStrings.path(BuildStrings.ProjectPath.GitHub, name);
    public static final String src = BuildStrings.path(root, "src");
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


    public static void build(BuildOptions buildOptions) throws Exception {
        // Unsafe creates compiler warnings. Disable them!
        buildOptions.compiler.suppressSunWarnings = true;

        buildOptions.compiler.targetJavaVersion = 6;

        buildOptions.compiler.deleteOnComplete = true;

        buildOptions.compiler.jar.buildJar = true;
        buildOptions.compiler.jar.includeSourceAsSeparate = true;



        // if we are running from a jar, DO NOT try to do certain things
        String utilSourcePath = LocationResolver.get(dorkbox.Build.class).getAbsolutePath();
        boolean runningFromJar = utilSourcePath.endsWith(".jar");

        if (!runningFromJar) {
            // install license files
            License.install(root, license);
        }

        String distDir = FileUtil.normalizeAsFile(Build.path(root, "dist"));
        File dists = new File(distDir);
        FileUtil.delete(dists, name + ".jar", name + "-src.zip");
        dists.mkdirs();


        String libsPath = FileUtil.normalizeAsFile(Build.path(root, "libs"));
        if (!runningFromJar) {
            // copy over jar deps
            File libDir = new File(libsPath);
            FileUtil.delete(libDir, "dorkboxUtil.jar", "dorkboxUtil-src.zip");
            libDir.mkdirs();

            Build.copyDirectory(Dirs.JavaTar, libsPath);
            Build.copyDirectory(Dirs.YAML, libsPath);
            Build.copyDirectory(Dirs.WildCard, libsPath);

            List<File> parseDir = FileUtil.parseDir(Build.path(Dirs.Dependencies, "logging"));
            for (File next : parseDir) {
                String name = next.getName();
                if (name.startsWith("slf4j") ||
                                name.startsWith("logback-classic") ||
                                name.startsWith("logback-core")) {

                    File file = new File(libDir, name);
                    FileUtil.copyFile(next, file);
                }
            }

            parseDir = FileUtil.parseDir(Build.path(Dirs.Dependencies, "BouncyCastleCrypto"));
            for (File next : parseDir) {
                String name = next.getName();
                if ((name.endsWith(".jar") || name.endsWith(".zip")) &&
                                (name.startsWith("bcpkix-jdk15on-") || name.startsWith("bcprov-jdk15on-"))) {

                    File file = new File(libDir, name);
                    Build.copyFile(next, file);
                }
            }

            parseDir = FileUtil.parseDir(Build.path(Dirs.Dependencies, "lzma-java"));
            for (File next : parseDir) {
                String name = next.getName();
                if (name.startsWith("lzma-java")) {
                    File file = new File(libDir, name);
                    Build.copyFile(next, file);
                }
            }

            Build.copyDirectory(Build.path(Dirs.Dependencies, "fast-md5"), FileUtil.normalize(Build.path(libsPath, "fast-md5")));

            // copy over the rt.jars for different versions of java, so we can 'cross compile' for different targets
            String jdkDist = FileUtil.normalize(Build.path(libsPath, "jdkRuntimes"));
            Build.copyDirectory(Dirs.OpenJDK_Runtime, jdkDist);
        }

        // now create the dorkboxUtil.jar jar + source + license

        if (!runningFromJar) {
            Paths sources = new Paths();
            sources.add(Build.getClassPath(dorkbox.util.OS.class));
            sources.add(Build.getClassPath(dorkbox.util.OsType.class));
            sources.add(Build.getClassPath(dorkbox.util.FileUtil.class));  // also apache filename utils
            sources.add(Build.getClassPath(dorkbox.util.LZMA.class)); // LZMA
            sources.add(Build.getClassPath(dorkbox.util.Sys.class));

            // have to specifiy a class in the package we want to get
            sources.add(Build.getClassPathPackage(dorkbox.util.annotation.AnnotationDetector.class));  // annotation detector

            sources.add(Build.getClassPath(dorkbox.util.CountingLatch.class));

            sources.add(Build.getClassPath(dorkbox.util.properties.SortedProperties.class));
            sources.add(Build.getClassPath(dorkbox.util.properties.PropertiesProvider.class));

            // have to specifiy a class in the package we want to get
            sources.add(Build.getClassPathPackage(dorkbox.util.process.ProcessProxy.class));

            sources.add(Build.getClassPath(dorkbox.util.Base64Fast.class));  //Base64 mig
            sources.add(Build.getClassPath(dorkbox.util.crypto.Crypto.class));
            sources.add(Build.getClassPath(dorkbox.util.crypto.CryptoX509.class));

            sources.add(Build.getClassPath(dorkbox.util.gwt.GwtSymbolMapParser.class));

            sources.add(Build.getClassPath(dorkbox.util.LocationResolver.class));

            PreJarAction preJarAction = new PreJarAction() {
                @Override
                public void executeBeforeJarHappens(File outputDir) throws Exception {
                    ClassPool cp = ClassPool.getDefault();
                    InputStream ins = null;
                    try {
                        // removing netty requirement from crypto, as only VERY LITTLE is needed by us!
                        File file = new File(outputDir, Build.path("dorkbox", "util", "crypto", "Crypto$AES.class"));
                        ins = new FileInputStream(file);
                        CtClass cryptoClass = cp.makeClass(ins);
                        CtMethod[] methods = cryptoClass.getMethods();
                        for (CtMethod method : methods) {
                            String name = method.getName();

                            if (name.equals("encrypt") || name.equals("decrypt")) {
                                CtClass[] parameterTypes = method.getParameterTypes();
                                if (parameterTypes.length > 0 &&
                                        parameterTypes[0].getName().equals("dorkbox.util.crypto.bouncycastle.GCMBlockCipher_ByteBuf")) {
                                    cryptoClass.removeMethod(method);
                                }
                            }
                        }
                        cryptoClass.writeFile(outputDir.getAbsolutePath());
                    } finally {
                        if (ins != null) {
                            ins.close();
                        }
                    }
                }
            };

            // this is only done here, since this is a VERY limited version of our utils.
            ProjectJava project = ProjectJava.create(name + "-" + "Dorkbox-Util")
                            .license(Licenses.DorkboxUtil.DorkboxUtil)
                            .license(Licenses.DorkboxUtil.MigBase64)
                            .license(Licenses.DorkboxUtil.FilenameUtils)
                            .preJarAction(preJarAction)
                            .outputFile(Build.path(libsPath, "dorkboxUtil.jar"))
                            .sourcePath(sources);

            project.build(buildOptions);
        }


        PreJarAction preJarAction = new PreJarAction() {
            @Override
            public void executeBeforeJarHappens(File outputDir) throws Exception {
                Build.log().message("Installing license files...");
                File targetLocation = new File(outputDir, Build.path("dorkbox", "license"));
                License.install(targetLocation);
            }
        };

        // now build the project
        ProjectJava project = ProjectJava.create(name)
                        .classPath(new Paths(Build.path(root, "libs"), ProjectBasics.Jar_Pattern, "!jdkRuntimes"))
                        .preJarAction(preJarAction)

                        .license(license)
                        .extraFiles(new Paths(root, "README.md"))
                        .mainClass(dorkbox.Build.class)
                        .outputFile(Build.path(distDir, name + ".jar"))
                        .sourcePath(src, ProjectBasics.Java_Pattern);


        project.build(buildOptions);


        // now copy all of these files into our libs dir for our builder
        if (!runningFromJar){
            // ALSO copy this file into OUR libs dir (so we can use it!)
            File rootPath = LocationResolver.get(OAK.class);
            String destLibsDir = new File(rootPath.getParentFile(), "libs").getAbsolutePath();

            // ALSO copy this file into OUR libs dir (so we can use it!)
            Build.log().message();
            Build.log().message("Copying jars into our location.");
            Build.copyFile(project.outputFile.getAbsolutePath(), Build.path(destLibsDir, project.name + ".jar"));

            // copy all of the files from the libs dir
            FileUtil.copyDirectory(libsPath, destLibsDir);


            // and the license files
            Build.log().message("Copying license files to our location.");
            Paths paths = new Paths(root, "LICENSE*");
            for (File file : paths.getFiles()) {
                FileUtil.copyFile(file.getAbsolutePath(), Build.path(destLibsDir, file.getName()));
            }
        }
    }
}