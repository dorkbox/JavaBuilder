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

import com.esotericsoftware.wildcard.Paths;
import dorkbox.build.Project;
import dorkbox.build.ProjectJava;
import dorkbox.build.SimpleArgs;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.BuildParser;
import dorkbox.build.util.classloader.ByteClassloader;
import dorkbox.build.util.classloader.ClassByteIterator;
import dorkbox.build.util.jar.Pack200Util;
import dorkbox.util.FileUtil;
import dorkbox.util.LZMA;
import dorkbox.util.Sys;
import dorkbox.util.annotation.AnnotationDefaults;
import dorkbox.util.annotation.AnnotationDetector;
import dorkbox.util.properties.PropertiesProvider;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public
class Builder {
    public static final String BUILD_MODE = "build";

    private static final ConcurrentHashMap<String, File> moduleCache = new ConcurrentHashMap<String, File>();

    private static final long startDate = System.currentTimeMillis();
    public static long buildDate = startDate;

    private static final File tempDir;

    /**
     * Location where settings are stored
     */
    public static final PropertiesProvider settings = new PropertiesProvider(new File("settings.ini"));

    public static final boolean isJar;

    public static final TimeZone defaultTimeZone;
    public static int offset;

    static {
        // get the local time zone for use later
        defaultTimeZone = TimeZone.getDefault();
        offset = defaultTimeZone.getRawOffset();
        if (defaultTimeZone.inDaylightTime(new Date())) {
            offset = offset + defaultTimeZone.getDSTSavings();
        }

        // have to set our default timezone to UTC. EVERYTHING will be UTC, and if we want local, we must explicitly ask for it.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
//        System.out.println("UTC Time: " + new Date());

        try {
            tempDir = new File(FileUtil.tempDirectory("Builder"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Paths.setDefaultGlobExcludes("**/.svn/**, **/.git/**");
        // are we building from a jar, or a project (from an IDE?)
        String sourceName = Build.get().getName();
        isJar = sourceName.endsWith(Project.JAR_EXTENSION);
    }

    static
    void start(String... _args) throws Exception {
        for (int i = 0; i < _args.length; i++) {
            _args[i] = _args[i].toLowerCase();
        }

        if (_args.length < 2) {
            System.err.println("You must specify an action, followed by what you want done.");
            System.err.println("For example:  build myProject  , which will then find and build your project");
            System.err.println("           : see example for more specific details");
            return;
        }

        SimpleArgs args = new SimpleArgs(_args);
        make(new BuildOptions(), args);
    }

    public static
    void build(String projectName, String... arguments) throws Exception {
        build(new BuildOptions(), projectName, arguments);
    }

    public static
    void build(BuildOptions buildOptions, String projectName, String... arguments) throws Exception {
        String[] _args = new String[2 + arguments.length];
        _args[0] = "build";
        _args[1] = projectName;
        for (int i = 0; i < arguments.length; i++) {
            _args[i + 2] = arguments[i];
        }

        SimpleArgs args = new SimpleArgs(_args);
        Project.reset();
        make(buildOptions, args);
    }

    public static
    void make(BuildOptions buildOptions, SimpleArgs args) throws Exception {
        String title = "JavaBuilder";
        BuildLog log = BuildLog.start();
        log.title(title).println(args);

        Date buildDate = args.getBuildDate();
        if (buildDate != null) {
            Builder.buildDate = buildDate.getTime();

            Calendar c = Calendar.getInstance(defaultTimeZone);
            c.setTime(buildDate);
            c.add(Calendar.HOUR_OF_DAY, offset / 1000 / 60 / 60);
            c.add(Calendar.MINUTE, offset / 1000 / 60 % 60);

            log.title("Forced Date").println(buildDate, c.getTime().toString().replace("UTC", defaultTimeZone.getID()));
        }

        Builder builder = new Builder();
        Exception e = null;
        try {
            Builder.prepareXcompile();
            log.println();

            if (Builder.isJar) {
                // when from eclipse, we want to run it directly (in case it changes significantly)
                builder.compileBuildInstructions(args);
                log.println();
            }

            builder.start(buildOptions, args);

            log.println();

            Calendar c = Calendar.getInstance(defaultTimeZone);
            c.setTime(new Date());
            c.add(Calendar.HOUR_OF_DAY, offset / 1000 / 60 / 60);
            c.add(Calendar.MINUTE, offset / 1000 / 60 % 60);
            String localDateString = c.getTime().toString().replace("UTC", defaultTimeZone.getID());

            if (!BuildLog.wasNested || BuildLog.TITLE_WIDTH != BuildLog.STOCK_TITLE_WIDTH) {
                log.title(title).println(args, localDateString, "Completed in: " + getRuntime(builder.startTime) + " seconds.");
            }
            else {
                log.title(title).println(args, localDateString, "Completed in: " + getRuntime(Builder.startDate) + " seconds.",
                                         "Date code: " + Builder.buildDate);
            }
        } catch (Exception e1) {
            e = e1;

            log.title("ERROR").println(e.getMessage());
            StackTraceElement[] stackTrace = e.getStackTrace();
            if (stackTrace.length > 0) {
                e.printStackTrace();
            }
        }

        BuildLog.finish();
        if (e != null) {
            throw e;
        }
    }

    private static
    String getRuntime(long startTime) {
        String time = Double.toString((System.currentTimeMillis() - startTime) / 1000D);
        int index = time.indexOf('.');
        if (index > -1 && index < time.length() - 2) {
            return time.substring(0, index + 2);
        }

        return time;
    }

    public static
    BuildLog log() {
        return new BuildLog();
    }

    public static
    BuildLog log(PrintStream printer) {
        return new BuildLog(printer);
    }



    private final long startTime = System.currentTimeMillis();
    private ByteClassloader classloader = null;

    private
    Builder() {
        Project.reset();
    }

    /**
     * check to see if our jdk files have been decompressed (necessary for cross target builds)
     */
    private static
    void prepareXcompile() throws IOException {
        String jdkDist = FileUtil.normalizeAsFile(Builder.path("libs", "jdkRuntimes"));
        List<File> jdkFiles = FileUtil.parseDir(jdkDist);
        boolean first = true;
        BuildLog log = log();

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
                        log.println("******************************");
                        log.println("Preparing environment");
                        log.println("******************************");
                    }

                    log.println("  Decompressing: " + f.getAbsolutePath());
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
            log.println("Finished Preparing environment");
            log.println("******************************");
            log.println();
            log.println();
        }
    }

    // loads the build.oak file information
    private
    void compileBuildInstructions(SimpleArgs args) throws Exception {
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

            ProjectJava project = ProjectJava.create(projectName).classPath(classPaths).compilerClassloader(bytesClassloader).sourcePath(
                            sourcePaths);


            List<String> dependencies = BuildParser.getStringsFromMap(projectData, "depends");
            for (String dep : dependencies) {
                project.depends(dep);
            }
        }

        // only if we have data, should we build
        if (!data.isEmpty()) {
            try {
                BuildLog.disable();
                BuildOptions buildOptions = new BuildOptions();
                buildOptions.compiler.forceRebuild = true;

                // this automatically takes care of build dependency ordering
                Project.buildAll();
                Project.reset();
                BuildLog.enable();
            } catch (Exception e) {
                BuildLog.enable();
                throw e;
            }

            this.classloader = bytesClassloader;
        }
    }


    private
    void start(BuildOptions buildOptions, SimpleArgs args) throws Exception {

        dorkbox.util.annotation.Builder detector;

        if (this.classloader != null) {
            detector = AnnotationDetector.scan(this.classloader, new ClassByteIterator(this.classloader, null));
        }
        else {
            detector = AnnotationDetector.scanClassPath();
        }

        List<Class<?>> controllers = detector.forAnnotations(Config.class).collect(AnnotationDefaults.getType);

        if (controllers != null) {
            // do we have something to control the build process??
            // now we want to update/search for all project builders if we didn't already run our specific builder
            for (Class<?> c : controllers) {
                Class<?>[] params = new Class<?>[] {SimpleArgs.class};
                Method buildTargeted = null;

                // setup(Args)
                try {
                    buildTargeted = c.getMethod("setup", params);
                } catch (Exception ignored) {
                }

                if (buildTargeted != null) {
                    Object newInstance = c.newInstance();
                    // see if we can build a targeted build
                    buildOptions = (BuildOptions) buildTargeted.invoke(newInstance, args);
                    break;
                }
                else {
                    params = new Class<?>[] {BuildOptions.class, SimpleArgs.class};

                    // setup(BuildOptions, Args)
                    try {
                        buildTargeted = c.getMethod("setup", params);
                    } catch (Exception ignored) {
                    }

                    if (buildTargeted != null) {
                        Object newInstance = c.newInstance();
                        // see if we can build a targeted build
                        buildTargeted.invoke(newInstance, buildOptions, args);
                        break;
                    }
                }
            }
        }

        BuildLog log = log();
        log.title("Debug info").println(buildOptions.compiler.debugEnabled ? "Enabled" : "Disabled");
        log.title("Release status").println(buildOptions.compiler.release ? "Enabled" : "Disabled");
        log.println();

        // now we want to update/search for all project builders.
        boolean found;
        if (this.classloader != null) {
            detector = AnnotationDetector.scan(this.classloader, new ClassByteIterator(this.classloader, null));
        }
        else {
            detector = AnnotationDetector.scanClassPath();
        }
        List<Class<?>> builders = detector.forAnnotations(Instructions.class).collect(AnnotationDefaults.getType);

        if (args.getMode().equals(Builder.BUILD_MODE)) {
            String projectToBuild = args.get(1);
            String methodNameToCall = args.get(2);
            if (methodNameToCall == null) {
                log.title("Warning").println("No build method specified. Using default: '" + Builder.BUILD_MODE + "'");
                methodNameToCall = Builder.BUILD_MODE;
            }

            log.title("Method").println(methodNameToCall);

            found = runBuild(buildOptions, args, builders, methodNameToCall, projectToBuild);

            if (controllers != null && !found) {
                final IOException ioException = new IOException("Unable to find a builder for: " + args.getParameters());
                ioException.setStackTrace(new StackTraceElement[0]);
                throw ioException;
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
                } catch (Exception ignored) {
                }

                if (buildTargeted != null) {
                    Object newInstance = c.newInstance();
                    // see if we can build a targeted build
                    buildTargeted.invoke(newInstance, buildOptions, args);
                    break;
                }
            }
        }
    }

    private static
    boolean runBuild(BuildOptions buildOptions, SimpleArgs args, List<Class<?>> builders, String methodNameToCall, String projectToBuild)
                    throws Exception {
        if (builders == null) {
            return false;
        }

        boolean found = false;
        for (Class<?> c : builders) {
            String simpleName = c.getSimpleName().toLowerCase();

            if (projectToBuild.equals(simpleName)) {
                Method[] methods = c.getMethods();

                for (Method m : methods) {
                    if (m.getName().equals(methodNameToCall)) {
                        Class<?>[] p = m.getParameterTypes();

                        switch (p.length) {
                            case 0: {
                                // build()
                                try {
                                    m.invoke(c);
                                } catch (InvocationTargetException e) {
                                    if (e.getCause() instanceof Exception) {
                                        throw (Exception) e.getCause();
                                    }

                                    throw new Exception(e);
                                }
                                found = true;
                                break;
                            }
                            case 1: {
                                if (p[0].equals(SimpleArgs.class)) {
                                    // build(Args)
                                    try {
                                        m.invoke(c, args);
                                    } catch (InvocationTargetException e) {
                                        if (e.getCause() instanceof Exception) {
                                            throw (Exception) e.getCause();
                                        }

                                        throw new Exception(e);
                                    }
                                    found = true;
                                    break;
                                }
                                if (p[0].equals(BuildOptions.class)) {
                                    // build(BuildOptions)
                                    try {
                                        m.invoke(c, buildOptions);
                                    } catch (InvocationTargetException e) {
                                        if (e.getCause() instanceof Exception) {
                                            throw (Exception) e.getCause();
                                        }

                                        throw new Exception(e);
                                    }
                                    found = true;
                                    break;
                                }
                                break;
                            }
                            case 2: {
                                // build(BuildOptions, Args)
                                try {
                                    m.invoke(c, buildOptions, args);
                                } catch (InvocationTargetException e) {
                                    if (e.getCause() instanceof Exception) {
                                        throw (Exception) e.getCause();
                                    }

                                    throw new Exception(e);
                                }
                                found = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    public static
    String path(String... paths) {
        StringBuilder buffer = new StringBuilder(128);

        for (String p : paths) {
            buffer.append(p).append(File.separator);
        }

        int length = buffer.length();
        buffer.delete(length - 1, length);

        return buffer.toString();
    }

    /**
     * Gets the java file (from class file) when running from an IDE.
     */
    public static
    File getJavaFileIDE(File rootFile) throws IOException {
        String rootPath = rootFile.getAbsolutePath();

        if (rootFile.isDirectory()) {
            final File eclipseSrc = new File(rootFile.getParentFile(), "src");

            if (eclipseSrc.exists()) {
                // eclipse (default)
                return eclipseSrc.getAbsoluteFile();
            }
            else {
                // intellij (default)
                String moduleName = rootPath.substring(rootPath.lastIndexOf(File.separatorChar) + 1);
                File parent = rootFile.getParentFile().getParentFile().getParentFile().getParentFile().getParentFile();
                // our src directory is always under the module dir
                final File dir = getModuleDir(parent, moduleName);

                return new File(dir, "src").getAbsoluteFile();
            }
        }

        return null;
    }

    /**
     * Converts a class to it's .java file.
     */
    public static
    Paths getJavaFile(Class<?> clazz) throws IOException {
        File rootFile = Build.get(clazz);
        assert rootFile != null;

        String rootPath = rootFile.getAbsolutePath();
        String fileName = clazz.getCanonicalName();

        final File javaFile = getJavaFileIDE(rootFile);

        if (javaFile != null) {
            String convertJava = fileName.replace('.', File.separatorChar) + ".java";
            return new Paths(javaFile.getAbsolutePath(), convertJava);
        }
        else if (rootPath.endsWith(Project.JAR_EXTENSION) && isZipFile(rootFile)) {
            // check to see if it's a zip file

            // have to go digging for it!
            // the sources can be IN THE FILE, or they are my sources, and are in the src file.
            String nameAsFile = fileName.replace('.', File.separatorChar) + ".java";

            boolean found = extractFilesFromZip(rootFile, nameAsFile);

            if (found) {
                return new Paths(tempDir.getAbsolutePath(), nameAsFile);
            }

            // try the source file
            rootPath = rootPath.replace(".jar", "_src.zip");
            rootFile = new File(rootPath);

            found = extractFilesFromZip(rootFile, nameAsFile);

            if (found) {
                return new Paths(tempDir.getAbsolutePath(), nameAsFile);
            }
        }
        // not found
        throw new IOException("Cannot find source file: '" + fileName + "'");
    }

    /**
     * Gets all of the .java files accessible which belong to the
     * package (but NOT subpackages) of the given class
     */
    public static
    Paths getJavaFilesInPackage(Class<?> clazz) throws IOException {
        File rootFile = Build.get(clazz);
        assert rootFile != null;

        String rootPath = rootFile.getAbsolutePath();
        String fileName = clazz.getCanonicalName();
        String directoryName = fileName.replace('.', File.separatorChar).substring(0, fileName.lastIndexOf('.'));

        final File javaFile = getJavaFileIDE(rootFile);

        if (javaFile != null) {
            return new Paths(javaFile.getAbsolutePath(), directoryName + "/*.java");
        }
        else if (rootPath.endsWith(Project.JAR_EXTENSION) && isZipFile(rootFile)) {
            // check to see if it's a zip file

            // have to go digging for it!
            // the sources can be IN THE FILE, or they are my sources, and are in the src file.

            Paths paths = extractPackageFilesFromZip(rootFile, directoryName);
            if (paths != null) {
                return paths;
            }


            // try the source file
            rootPath = rootPath.replace(".jar", "_src.zip");
            rootFile = new File(rootPath);

            paths = extractPackageFilesFromZip(rootFile, directoryName);
            if (paths != null) {
                return paths;
            }
        }


        // not found
        throw new IOException("Cannot find source file location: '" + fileName + "'");
    }

    private static
    boolean extractFilesFromZip(final File rootFile, final String nameAsFile) throws IOException {
        boolean found = false;
        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(rootFile));
        ZipEntry entry;
        try {
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.equals(nameAsFile)) {
                    // read out bytes!
                    final File file = new File(tempDir, nameAsFile);
                    if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                        throw new IOException("Unable to create temp dir: " + file.getParentFile());
                    }

                    final FileOutputStream fileOutputStream = new FileOutputStream(file);
                    try {
                        copyStream(zipInputStream, fileOutputStream);
                        found = true;
                    } finally {
                        fileOutputStream.close();
                    }

                    zipInputStream.closeEntry();
                    break;
                }
            }
        } finally {
            zipInputStream.close();
        }

        return found;
    }

    /**
     * Extracts all of the directoryName files found in the zip file to temp dir. Only extracts the SAME LEVEL, not subdirs.
     */
    private static
    Paths extractPackageFilesFromZip(final File rootFile, final String directoryName) throws IOException {
        final int length = directoryName.length();
        boolean found = false;
        Paths paths = new Paths();

        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(rootFile));
        ZipEntry entry;
        try {
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.startsWith(directoryName) && !entry.isDirectory() && name.lastIndexOf('/') <= length && name.endsWith(".java")) {
                    // read out bytes!
                    final File file = new File(tempDir, directoryName);
                    if (!file.exists() && !file.mkdirs()) {
                        throw new IOException("Unable to create temp dir: " + file);
                    }

                    final FileOutputStream fileOutputStream = new FileOutputStream(new File(tempDir, name));
                    try {
                        copyStream(zipInputStream, fileOutputStream);
                        paths.add(tempDir.getAbsolutePath(), name);
                        found = true;
                    } finally {
                        fileOutputStream.close();
                    }

                    zipInputStream.closeEntry();
                }
            }
        } finally {
            zipInputStream.close();
        }

        if (found) {
            return paths;
        }
        return null;
    }


    private static
    File getModuleDir(final File parent, final String moduleName) {
        final File file = moduleCache.get(moduleName);
        if (file != null) {
            return file;
        }

        ArrayList<File> candidates = new ArrayList<File>();

        getDirs(0, candidates, parent, moduleName);

        for (File candidate : candidates) {
            // our src directory is always under the module dir
            if (new File(candidate, "src").isDirectory()) {
                // we also want to CACHE the module dir, as the search can take a while.
                moduleCache.put(moduleName, candidate);
                return candidate;
            }
        }

        return null;
    }

    private static
    void getDirs(final int i, ArrayList<File> candidates, final File parent, final String moduleName) {
        if (i > 4) {
            return;
        }

        for (File file : parent.listFiles()) {
            if (file.isDirectory()) {
                if (file.getName().equals(moduleName)) {
                    candidates.add(file);
                }
                else {
                    getDirs(i + 1, candidates, file, moduleName);
                }
            }
        }
    }

    /**
     * Copy the contents of the input stream to the output stream.
     * <p/>
     * DOES NOT CLOSE THE STEAMS!
     */
    public static
    <T extends OutputStream> T copyStream(InputStream inputStream, T outputStream) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, read);
        }

        return outputStream;
    }

    /**
     * @return true if the file is a zip/jar file
     */
    public static
    boolean isZipFile(File file) {
        byte[] ZIP_HEADER = {'P', 'K', 0x3, 0x4};
        boolean isZip = true;
        byte[] buffer = new byte[ZIP_HEADER.length];

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            raf.readFully(buffer);
            for (int i = 0; i < ZIP_HEADER.length; i++) {
                if (buffer[i] != ZIP_HEADER[i]) {
                    isZip = false;
                    break;
                }
            }
        } catch (Exception e) {
            isZip = false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return isZip;
    }


    public static
    boolean deleteFile(String target) {
        return deleteFile(new File(FileUtil.normalizeAsFile(target)));
    }

    public static
    boolean deleteFile(File target) {
        target = FileUtil.normalize(target);

        log().title("Deleting file").println(target.getAbsolutePath());

        return target.delete();
    }

    public static
    File moveFile(String source, String target) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);


        log().title("Moving file").println("  ╭─ " + source, "╰─> " + target);

        return FileUtil.moveFile(source, target);
    }

    public static
    File copyFile(File source, File target) throws IOException {
        source = FileUtil.normalize(source);
        target = FileUtil.normalize(target);


        log().title("Copying file").println("  ╭─ " + source.getAbsolutePath(), "╰─> " + target.getAbsolutePath());

        return FileUtil.copyFile(source, target);
    }

    public static
    void copyFile(String source, String target) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);

        log().title("Copying file").println("  ╭─ " + source, "╰─> " + target);

        FileUtil.copyFile(source, target);
    }

    public static
    void copyDirectory(String source, String target, String... dirNamesToIgnore) throws IOException {
        source = FileUtil.normalizeAsFile(source);
        target = FileUtil.normalizeAsFile(target);

        log().title("Copying dir").println("  ╭─ " + source, "╰─> " + target);
        FileUtil.copyDirectory(source, target, dirNamesToIgnore);
    }
}