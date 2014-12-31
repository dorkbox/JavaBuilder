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
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.Build;
import dorkbox.BuildOptions;
import dorkbox.build.Project;
import dorkbox.build.ProjectJava;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.PreJarAction;
import dorkbox.license.License;
import dorkbox.util.FileUtil;
import dorkbox.util.LocationResolver;

@Build.Builder
public class JavaBuilder {

    // ALSO copied over to OAK build dir (as an example), so make sure to update in both locations
    // ~/dorkbox/eclipse/jre/bin/java -jar dist/OAK.jar build oak
    // ~/dorkbox/eclipse/jre/bin/java -Xrunjdwp:transport=dt_socket,server=y,address=1044 -jar dist/JavaBuilder.jar build javabuilder dist

    public static final String name = "JavaBuilder";
    public static final String root = BuildStrings.path(BuildStrings.ProjectPath.GitHub, name);
    public static final String src = BuildStrings.path(root, "src");
    public static List<License> license = License.list(Licenses.OAK.OAK,
                                                       Licenses.OAK.Scar,
                                                       Licenses.DorkboxUtil.DorkboxUtil,
                                                       Licenses.DorkboxUtil.MigBase64,
                                                       Licenses.DorkboxUtil.FilenameUtils,
                                                       Licenses.DorkboxUtil.AnnotationDetector,

                                                       LicenseLibs.BouncyCastle,
                                                       LicenseLibs.FastMD5,
                                                       LicenseLibs.LzmaJava,
                                                       LicenseLibs.SLF4J,

                                                       LicenseLibs.JavaTar,
                                                       LicenseLibs.WildCard,
                                                       LicenseLibs.OpenJDK,
                                                       LicenseLibs.YamlBeans);


    public static void dist(BuildOptions buildOptions) throws Exception {
        buildOptions.compiler.targetJavaVersion = 6;

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
            BuildLog.start().message("Copying jar dependencies");

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
            BuildLog.finish();
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
                public void executeBeforeJarHappens(File outputDir) throws IOException {
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
                    } catch (NotFoundException e) {
                        throw new IOException("Error with javassist", e);
                    } catch (CannotCompileException e) {
                        throw new IOException("Error with javassist", e);
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
                            .sourcePath(sources)
                            .options(buildOptions)
                            .jar().setDateLatest()
                                  .preJarAction(preJarAction)
                                  .includeSourceAsSeparate()
                                  .outputFile(Build.path(libsPath, "dorkboxUtil.jar"));
            project.build();
        }


        PreJarAction preJarAction = new PreJarAction() {
            @Override
            public void executeBeforeJarHappens(File outputDir) throws IOException {
                Build.log().message("Installing license files...");
                File targetLocation = new File(outputDir, Build.path("dorkbox", "license"));
                License.install(targetLocation);
            }
        };

        // now build the project
        ProjectJava project = ProjectJava.create(name)
                        .classPath(new Paths(Build.path(root, "libs"), Project.Jar_Pattern, "!jdkRuntimes"))
                        .license(license)
                        .extraFiles(new Paths(root, "README.md"))
                        .sourcePath(src, Project.Java_Pattern)
                        .options(buildOptions)
                        .jar().setDateLatest()
                              .mainClass(dorkbox.Build.class)
                              .preJarAction(preJarAction)
                              .includeSourceAsSeparate()
                              .outputFile(Build.path(distDir, name + ".jar"));
        project.build();


        // now copy all of these files into our libs dir for our builder
        if (!runningFromJar){
            // ALSO copy this file into OUR libs dir (so we can use it!)
            File rootPath = LocationResolver.get(JavaBuilder.class);
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