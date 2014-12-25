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
package dorkbox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.build.ProjectBasics;
import dorkbox.build.ProjectJava;
import dorkbox.build.SimpleArgs;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.BuildParser;
import dorkbox.build.util.ByteClassloader;
import dorkbox.build.util.ClassByteIterator;
import dorkbox.build.util.jar.Pack200Util;
import dorkbox.util.FileUtil;
import dorkbox.util.LZMA;
import dorkbox.util.LocationResolver;
import dorkbox.util.OS;
import dorkbox.util.Sys;
import dorkbox.util.annotation.AnnotationDefaults;
import dorkbox.util.annotation.AnnotationDetector;
import dorkbox.util.properties.PropertiesProvider;

public class Build {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE})
    public @interface Builder {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE})
    public @interface Configure {
    }

    public static final String BUILD_MODE = "build";

    /** Location where settings are stored */
    public static PropertiesProvider settings = new PropertiesProvider(new File("settings.ini"));

    private ByteClassloader classloader;

    static {
        Paths.setDefaultGlobExcludes("**/.svn/**, **/.git/**");
    }

    public static void main(String[] _args) {
        for (int i=0;i<_args.length;i++) {
            _args[i] = _args[i].toLowerCase();
        }

        if (_args.length < 2) {
            System.err.println("You must specify an action, followed by what you want done.");
            System.err.println("For example:  build myProject  , which will then find and build your project");
            return;
        }

        SimpleArgs args = new SimpleArgs(_args);

        System.err.println("Dorkbox OAK: starting " + args);

        Build build = new Build();
        try {
            build.prepareXcompile();
            build.loadBuildInfo(args);
            build.start(args);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    // loads the build.oak file information
    private void loadBuildInfo(SimpleArgs args) throws Exception {
        HashMap<String, Object> data = BuildParser.parse(args);


        Paths classPaths = BuildParser.getPathsFromMap(data, "classpath");
        Paths sourcePaths = BuildParser.getPathsFromMap(data, "source");

        // always use these as the default. don't want the runtimes on our path
        classPaths.glob("libs", "**/*.jar", "!jdkRuntimes");


        String projectName = "project";

        if (data.containsKey("name")) {
            Object object = data.get("name");
            projectName = (String) object;
        }

        ByteClassloader bytesClassloader = new ByteClassloader(Thread.currentThread().getContextClassLoader());

        ProjectJava project = ProjectJava.create(projectName)
                                         .classPath(classPaths)
                                         .compilerClassloader(bytesClassloader)
                                         .sourcePath(sourcePaths);


        try {
            log().println("-= Compiling build instructions =-" + OS.LINE_SEPARATOR);
            BuildLog.stop();
            project.forceBuild(new BuildOptions(), false, false);
            ProjectBasics.reset();
            BuildLog.start();
        } catch (Exception e) {
            BuildLog.start();
            throw e;
        }

        this.classloader = bytesClassloader;
    }

    private Build() {
    }

    public static BuildLog log() {
        return new BuildLog();
    }

    public static BuildLog log(PrintStream printer) {
        return new BuildLog(printer);
    }

    private void start(SimpleArgs args) throws IOException, IllegalAccessException, IllegalArgumentException,
                                               InvocationTargetException, InstantiationException {

        BuildOptions buildOptions = new BuildOptions();
        List<Class<?>> controllers = AnnotationDetector.scan(this.classloader, new ClassByteIterator(this.classloader, null))
                                                       .forAnnotations(Build.Configure.class)
                                                       .collect(AnnotationDefaults.getType);

        if (controllers != null) {
            // do we have something to control the build process??
            // now we want to update/search for all project builders if we didn't already run our specific builder
            for (Class<?> c : controllers) {
                Class<?>[] params = new Class<?>[] {BuildOptions.class, SimpleArgs.class};
                Method buildTargeted = null;

                // setup(BuildOptions, Args)
                try {
                    buildTargeted = c.getMethod("setup", params);
                } catch (Exception e) {}

                if (buildTargeted != null) {
                    Object newInstance = c.newInstance();
                    // see if we can build a targeted build
                    buildTargeted.invoke(newInstance, buildOptions, args);
                    break;
                }
            }
        }

        // now we want to update/search for all project builders.
        if (args.getMode().equals(Build.BUILD_MODE)) {
            boolean found = false;
            List<Class<?>> builders = AnnotationDetector.scan(this.classloader, new ClassByteIterator(this.classloader, null))
                                                        .forAnnotations(Build.Builder.class)
                                                        .collect(AnnotationDefaults.getType);

            if (builders != null) {
                String projectToBuild = args.get(1);
                for (Class<?> c : builders) {
                    String simpleName = c.getSimpleName().toLowerCase();

                    if (projectToBuild.equals(simpleName)) {
                        Method build = null;

                        // 4 different build methods supported.
                        // build()
                        try {
                            build = c.getMethod(Build.BUILD_MODE, new Class<?>[] {});
                        } catch (Exception e) {}

                        if (build != null) {
                            build .invoke(c);
                            found = true;
                            break;
                        }

                        // build(Args)
                        Class<?>[] params = new Class<?>[] {SimpleArgs.class};
                        try {
                            build = c.getMethod(Build.BUILD_MODE, params);
                        } catch (Exception e) {}

                        if (build != null) {
                            build .invoke(c, args);
                            found = true;
                            break;
                        }


                        // build(BuildOptions)
                        params = new Class<?>[] {BuildOptions.class};
                        try {
                            build = c.getMethod(Build.BUILD_MODE, params);
                        } catch (Exception e) {}

                        if (build != null) {
                            build.invoke(c, buildOptions);
                            found = true;
                            break;
                        }


                        // build(BuildOptions, Args)
                        params = new Class<?>[] {BuildOptions.class, SimpleArgs.class};
                        try {
                            build = c.getMethod(Build.BUILD_MODE, params);
                        } catch (Exception e) {}

                        if (build != null) {
                            build .invoke(c, buildOptions, args);
                            found = true;
                            break;
                        }
                    }
                }
            }

            if (!found) {
                System.err.println("Unable to find a build for the target: " + args);
            }
        }

        if (controllers != null) {
            // do we have something to control the build process??
            // now we want to update/search for all project builders if we didn't already run our specific builder
            for (Class<?> c : controllers) {
                Class<?>[] params = new Class<?>[] {BuildOptions.class, SimpleArgs.class};
                Method buildTargeted = null;

                // finish(BuildOptions, Args)
                try {
                    buildTargeted = c.getMethod("takedown", params);
                } catch (Exception e) {}

                if (buildTargeted != null) {
                    Object newInstance = c.newInstance();
                    // see if we can build a targeted build
                    buildTargeted.invoke(newInstance, buildOptions, args);
                    break;
                }
            }
        }
    }

    /**
     * check to see if our jdk files have been decompressed (necessary for cross target builds)
     */
    private void prepareXcompile() throws IOException {
        String jdkDist = FileUtil.normalizeAsFile(Build.path("libs", "jdkRuntimes"));
        List<File> jdkFiles = FileUtil.parseDir(jdkDist);
        boolean first = true;
        for (File f : jdkFiles) {
            // unLZMA + unpack200
            String name = f.getName();
            String suffix = ".pack.lzma";

            if (name.endsWith(suffix)) {
                int nameLength = f.getAbsolutePath().length();
                String fixedName = f.getAbsolutePath().substring(0, nameLength - suffix.length());
                File file = new File(fixedName);

                if (!file.canRead() || file.length() == 0) {
                    if (first) {
                        first = false;
                        System.err.println("******************************************");
                        System.err.println("*  Dorkbox OAK -- Preparing environment  *");
                        System.err.println("******************************************");
                    }

                    System.err.println("*  Decompressing: " + f.getAbsolutePath());
                    InputStream inputStream = new FileInputStream(f);
                    // now uncompress
                    ByteArrayOutputStream outputStream = LZMA.decode(inputStream);

                    // now unpack
                    inputStream = new ByteArrayInputStream(outputStream.toByteArray());
                    outputStream = Pack200Util.Java.unpack200((ByteArrayInputStream) inputStream);

                    // now write to disk
                    inputStream = new ByteArrayInputStream(outputStream.toByteArray());

                    FileOutputStream fileOutputStream = new FileOutputStream(new File(fixedName));
                    Sys.copyStream(inputStream, fileOutputStream);
                    Sys.close(fileOutputStream);
                }
            }
        }

        if (!first) {
            System.err.println("*  Finished preparing environment");
            System.err.println("******************************************\n\n");
        }
    }

    public static String path(String... paths) {
        StringBuilder buffer = new StringBuilder(128);

        for (String p : paths) {
            buffer.append(p).append(File.separator);
        }

        int length = buffer.length();
        buffer.delete(length-1, length);

       return buffer.toString();
    }

    /**
     * Converts a class to it's .java file.
     */
    public static Paths getClassPath(Class<?> clazz) throws IOException {
        String rootPath = LocationResolver.get(clazz).getAbsolutePath();

        String fileName = clazz.getCanonicalName();
        String convert = fileName.replace('.', File.separatorChar) + ".java";
        File rootFile = new File(rootPath);

        if (rootFile.isDirectory()) {
            File location = rootFile.getParentFile();
            location = new File(location, "src");

            Paths path = new Paths(location.getAbsolutePath(), convert);
            return path;
        } else {
            throw new IOException("we can only support listing files that are not in a container!");
        }
    }

    /**
     * Gets all of the .java files accessible which belong to the
     * package and subpackages of the given class
     */
    public static Paths getClassPathPackage(Class<?> clazz) throws IOException {
        String rootPath = LocationResolver.get(clazz).getAbsolutePath();

        String dirName = clazz.getPackage().getName();
        String convert = dirName.replace('.', File.separatorChar);
        File rootFile = new File(rootPath);

        if (rootFile.isDirectory()) {
            File location = rootFile.getParentFile();
            location = new File(location, "src");
            location = new File(location, convert);

            Paths paths = new Paths(location.getAbsolutePath(), "**.java");
            return paths;
        } else {
            throw new IOException("we can only support listing class path packages that are not in a container!");
        }
    }

    public static final void finish(String text) {
        System.err.println("\n\n");
        System.err.println("TIME: " + new Date());
        System.err.println("FINISHED: " + text);
    }

    public static File moveFile(String source, String target) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);


        log().title("   Moving file").message("  ╭─ " + source,
                                                "╰─> " + target);

        return FileUtil.moveFile(source, target);
    }

    public static File copyFile(File source, File target) throws IOException {
        source = FileUtil.normalize(source);
        target = FileUtil.normalize(target);


        log().title("  Copying file").message("  ╭─ " + source.getAbsolutePath(),
                                                "╰─> " + target.getAbsolutePath());

        return FileUtil.copyFile(source, target);
    }

    public static void copyFile(String source, String target) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);

        log().title("  Copying file").message("  ╭─ " + source,
                                                "╰─> " + target);

        FileUtil.copyFile(source, target);
    }

    public static void copyDirectory(String source, String target, String... dirNamesToIgnore) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);

        log().title("   Copying dir").message("  ╭─ " + source,
                                                "╰─> " + target);
        FileUtil.copyDirectory(source, target, dirNamesToIgnore);
    }
}
