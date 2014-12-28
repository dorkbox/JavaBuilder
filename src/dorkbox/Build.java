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
import java.util.Map.Entry;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.build.ProjectBasics;
import dorkbox.build.ProjectJava;
import dorkbox.build.SimpleArgs;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.BuildParser;
import dorkbox.build.util.classloader.ByteClassloader;
import dorkbox.build.util.classloader.ClassByteIterator;
import dorkbox.build.util.jar.Pack200Util;
import dorkbox.util.FileUtil;
import dorkbox.util.LZMA;
import dorkbox.util.LocationResolver;
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

    public static boolean isJar;

    static {
        Paths.setDefaultGlobExcludes("**/.svn/**, **/.git/**");
        // are we building from a jar, or a project (from eclipse?)
        String sourceName = LocationResolver.get(dorkbox.Build.class).getName();
        isJar = sourceName.endsWith(".jar");
    }

    public static void main(String[] _args) {
        for (int i=0;i<_args.length;i++) {
            _args[i] = _args[i].toLowerCase();
        }

        if (_args.length < 2) {
            System.err.println("You must specify an action, followed by what you want done.");
            System.err.println("For example:  build myProject  , which will then find and build your project");
            System.err.println("           : see example for more specific details");
            return;
        }

        SimpleArgs args = new SimpleArgs(_args);
        build(new BuildOptions(), args);
    }

    public static void build(String projectName, String... arguments) {
        build(new BuildOptions(), projectName, arguments);
    }

    public static void build(BuildOptions buildOptions, String projectName, String... arguments) {
        String _args[] = new String[2 + arguments.length];
        _args[0] = "build";
        _args[1] = projectName;
        for (int i = 0; i < arguments.length; i++) {
            _args[i+2] = arguments[i];
        }

        SimpleArgs args = new SimpleArgs(_args);
        build(buildOptions, args);
    }

    public static void build(BuildOptions buildOptions, SimpleArgs args) {
        String title = "OAK";
        log().title(title).message("starting " + args);

        Build build = new Build();
        try {
            build.prepareXcompile();
            log().message();

            if (Build.isJar) {
                // when from eclipse, we want to run it directly (in case it changes significantly)
                build.compileBuildInstructions(args);
                log().message();
            }

            build.start(args, buildOptions);

            log().message();
            log().title(title).message("finished " + args , new Date());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private Build() {
        ProjectBasics.reset();
    }

    public static BuildLog log() {
        return new BuildLog();
    }

    public static BuildLog log(PrintStream printer) {
        return new BuildLog(printer);
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
                        log().message("******************************");
                        log().message("Preparing environment");
                        log().message("******************************");
                    }

                    log().message("  Decompressing: " + f.getAbsolutePath());
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
            log().message("Finished Preparing environment");
            log().message("******************************\n\n");
        }
    }

    // loads the build.oak file information
    private void compileBuildInstructions(SimpleArgs args) throws Exception {
        ByteClassloader bytesClassloader = new ByteClassloader(Thread.currentThread().getContextClassLoader());
        HashMap<String, HashMap<String, Object>> data = BuildParser.parse(args);

        // each entry is a build, that can have dependencies.
        for (Entry<String, HashMap<String, Object>> entry : data.entrySet()) {
            String projectName = entry.getKey();
            HashMap<String, Object> projectData = entry.getValue();

            // will always have libs jars (minus runtime jars)
            Paths classPaths = BuildParser.getPathsFromMap(projectData, "classpath");

            // BY DEFAULT, will use build/**/*.java path
            Paths sourcePaths = BuildParser.getPathsFromMap(projectData, "source");

            ProjectJava project = ProjectJava.create(projectName)
                                             .classPath(classPaths)
                                             .compilerClassloader(bytesClassloader)
                                             .sourcePath(sourcePaths);


            List<String> dependencies = BuildParser.getStringsFromMap(projectData, "depends");
            for (String dep : dependencies) {
                project.depends(dep);
            }
        }

        try {
            BuildLog.stop();
            BuildOptions buildOptions = new BuildOptions();
            buildOptions.compiler.forceRebuild = true;

            // this automatically takes care of build dependency ordering
            ProjectBasics.buildAll(buildOptions);
            ProjectBasics.reset();
            BuildLog.start();
        } catch (Exception e) {
            BuildLog.start();
            throw e;
        }

        this.classloader = bytesClassloader;
    }


    private void start(SimpleArgs args, BuildOptions buildOptions) throws IOException, IllegalAccessException, IllegalArgumentException,
                                               InvocationTargetException, InstantiationException {

        dorkbox.util.annotation.Builder detector;

        if (Build.isJar) {
            detector = AnnotationDetector.scan(this.classloader, new ClassByteIterator(this.classloader, null));
        } else {
            detector = AnnotationDetector.scanClassPath();
        }

        List<Class<?>> controllers = detector.forAnnotations(Build.Configure.class)
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

        log().title("Java Version").message(buildOptions.compiler.targetJavaVersion);
        log().title("Debug info").message(buildOptions.compiler.debugEnabled ? "Enabled" : "Disabled");
        log().title("Release status").message(buildOptions.compiler.release ? "Enabled" : "Disabled");
        log().message();

        // now we want to update/search for all project builders.
        if (args.getMode().equals(Build.BUILD_MODE)) {
            boolean found = false;
            if (Build.isJar) {
                detector = AnnotationDetector.scan(this.classloader, new ClassByteIterator(this.classloader, null));
            } else {
                detector = AnnotationDetector.scanClassPath();
            }
            List<Class<?>> builders = detector.forAnnotations(Build.Builder.class)
                                              .collect(AnnotationDefaults.getType);

            if (builders != null) {
                String projectToBuild = args.get(1);
                for (Class<?> c : builders) {
                    String simpleName = c.getSimpleName().toLowerCase();

                    if (projectToBuild.equals(simpleName)) {
                        Method[] methods = c.getMethods();

                        for (Method m : methods) {
                            if (m.getName().equals(Build.BUILD_MODE)) {
                                Class<?>[] p = m.getParameterTypes();

                                switch (p.length) {
                                    case 0: {
                                        // build()
                                        m.invoke(c);
                                        found = true;
                                        break;
                                    }
                                    case 1: {
                                        if (p[0].equals(SimpleArgs.class)) {
                                            // build(Args)
                                            m.invoke(c, args);
                                            found = true;
                                            break;
                                        } if (p[0].equals(BuildOptions.class)) {
                                            // build(BuildOptions)
                                            m.invoke(c, buildOptions);
                                            found = true;
                                            break;
                                        }
                                        break;
                                    }
                                    case 2: {
                                        // build(BuildOptions, Args)
                                        m.invoke(c, buildOptions, args);
                                        found = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!found) {
                log().message("Unable to find a build for " + args);
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

    public static File moveFile(String source, String target) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);


        log().title("Moving file").message("  ╭─ " + source,
                                             "╰─> " + target);

        return FileUtil.moveFile(source, target);
    }

    public static File copyFile(File source, File target) throws IOException {
        source = FileUtil.normalize(source);
        target = FileUtil.normalize(target);


        log().title("Copying file").message("  ╭─ " + source.getAbsolutePath(),
                                              "╰─> " + target.getAbsolutePath());

        return FileUtil.copyFile(source, target);
    }

    public static void copyFile(String source, String target) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);

        log().title("Copying file").message("  ╭─ " + source,
                                              "╰─> " + target);

        FileUtil.copyFile(source, target);
    }

    public static void copyDirectory(String source, String target, String... dirNamesToIgnore) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);

        log().title("Copying dir").message("  ╭─ " + source,
                                             "╰─> " + target);
        FileUtil.copyDirectory(source, target, dirNamesToIgnore);
    }
}
