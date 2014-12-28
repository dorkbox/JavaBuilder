import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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

    // ~/dorkbox/eclipse/jre/bin/java -jar dist/OAK.jar build oak
    // ~/dorkbox/eclipse/jre/bin/java -Xrunjdwp:transport=dt_socket,server=y,address=1044 -jar dist/OAK.jar build oak


    public static void build(BuildOptions buildOptions) throws Exception {
        // Unsafe creates compiler warnings. Disable them!
        buildOptions.compiler.suppressSunWarnings = true;

        buildOptions.compiler.targetJavaVersion = 6;

        buildOptions.compiler.forceRebuild = true;
        buildOptions.compiler.deleteOnComplete = true;
        buildOptions.compiler.jar.buildJar = true;


        // if we are running from a jar, DO NOT try to do certain things
        String utilSourcePath = LocationResolver.get(dorkbox.Build.class).getAbsolutePath();
        boolean runningFromJar = utilSourcePath.endsWith(".jar");

        if (!runningFromJar) {
            // install license files
            License.install(BuildStrings.JavaBuilder.root, BuildStrings.JavaBuilder.license);
        }

        String distDir = FileUtil.normalizeAsFile(Build.path(BuildStrings.JavaBuilder.root, "dist"));
        File dists = new File(distDir);
        dists.mkdirs();


        String libsPath = FileUtil.normalizeAsFile(Build.path(BuildStrings.JavaBuilder.root, "libs"));
        if (!runningFromJar) {
            // copy over jar deps
            File libDir = new File(libsPath);
            libDir.mkdirs();

            Build.copyDirectory(Dirs.JavaTar, libsPath);
            Build.copyDirectory(Dirs.YAML, libsPath);
            Build.copyDirectory(Dirs.WildCard, libsPath);

            List<File> parseDir = FileUtil.parseDir(Build.path(Dirs.Dependencies, "logging"));
            for (File next : parseDir) {
                String name = next.getName();
                if (name.startsWith("slf4j") ||
                                name.startsWith("logback-classic") ||
                                name.startsWith("logback-cored")) {

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
                public void executeBeforeJarHappens(String outputDir) throws Exception {
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
                        cryptoClass.writeFile(outputDir);
                    } finally {
                        if (ins != null) {
                            ins.close();
                        }
                    }
                }
            };

            // this is only done here, since this is a VERY limited version of our utils.
            ProjectJava project = ProjectJava.create("DorkboxUtil")
                            .includeSourceInJar()
                            .license(Licenses.DorkboxUtil.DorkboxUtil)
                            .license(Licenses.DorkboxUtil.MigBase64)
                            .license(Licenses.DorkboxUtil.FilenameUtils)
                            .preJarAction(preJarAction)
                            .sourcePath(sources);

            project.build(buildOptions);
            // now put it in our libs
            Build.copyFile(Build.path("staging", project.outputFile), Build.path(libsPath, "dorkboxUtil.jar"));
        }


        PreJarAction preJarAction = new PreJarAction() {
            @Override
            public void executeBeforeJarHappens(String outputDir) throws Exception {
                Build.log().message("Installing license files...");
                File targetLocation = new File(outputDir, Build.path("dorkbox", "license"));
                License.install(targetLocation);
            }
        };

        // now build the project
        ProjectJava project = ProjectJava.create(BuildStrings.JavaBuilder.name)
                        .classPath(new Paths(Build.path(BuildStrings.JavaBuilder.root, "libs"), ProjectBasics.Jar_Pattern, "!jdkRuntimes"))
                        .includeSourceInJar()
                        .preJarAction(preJarAction)

                        .license(BuildStrings.JavaBuilder.license)
                        .extraFiles(new Paths(BuildStrings.JavaBuilder.root, "README.md"))
                        .mainClass(dorkbox.Build.class)
                        .sourcePath(BuildStrings.JavaBuilder.src, ProjectBasics.Java_Pattern);


        project.build(buildOptions);

        // now put it in our dist dir
        String targetPath = Build.path(distDir, project.name + ".jar");
        Build.moveFile(Build.path(BuildStrings.STAGING, project.outputFile), targetPath);


        // now copy all of these files into our libs dir for our builder
        if (!runningFromJar){
            // ALSO copy this file into OUR libs dir (so we can use it!)
            File rootPath = LocationResolver.get(OAK.class);
            String destLibsDir = new File(rootPath.getParentFile(), "libs").getAbsolutePath();

            // ALSO copy this file into OUR libs dir (so we can use it!)
            Build.log().message("Copying jars into our location.");
            Build.copyFile(targetPath, Build.path(destLibsDir, project.name + ".jar"));

            // copy all of the files from the libs dir
            FileUtil.copyDirectory(libsPath, destLibsDir);


            // and the license files
            Build.log().message("Copying license files to our location.");
            Paths paths = new Paths(BuildStrings.JavaBuilder.root, "LICENSE*");
            for (File file : paths.getFiles()) {
                FileUtil.copyFile(file.getAbsolutePath(), Build.path(destLibsDir, file.getName()));
            }
        }
    }
}
