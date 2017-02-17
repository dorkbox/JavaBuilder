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
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.annotation.AnnotationDefaults;
import dorkbox.annotation.AnnotationDetector;
import dorkbox.build.Project;
import dorkbox.build.ProjectJava;
import dorkbox.build.SimpleArgs;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.BuildParser;
import dorkbox.build.util.FileNotFoundRuntimeException;
import dorkbox.build.util.classloader.ByteClassloader;
import dorkbox.build.util.classloader.ClassByteIterator;
import dorkbox.build.util.jar.Pack200Util;
import dorkbox.util.FileUtil;
import dorkbox.util.IO;
import dorkbox.util.LZMA;
import dorkbox.util.OS;
import dorkbox.util.Sys;
import dorkbox.util.properties.PropertiesProvider;

@SuppressWarnings({"AccessStaticViaInstance", "WeakerAccess"})
public
class Builder {
    // UNICODE is from: https://en.wikipedia.org/wiki/List_of_Unicode_characters#Box_Drawing

    private static final boolean DEBUG_INSTRUCTIONS = false;

    public static final String BUILD_MODE = "build";

    /**
     * Location where settings are stored. Can be specified on CLI by settings=settings.ini. Filename must not have an '=' in it, and
     * must be a whole word (no spaces)
     */
    public static PropertiesProvider settings = new PropertiesProvider(new File(BuildOptions.settings));
    public static final boolean isJar;

    public static final TimeZone defaultTimeZone;
    private static final ConcurrentHashMap<String, File> moduleCache = new ConcurrentHashMap<String, File>();
    private static final File tempDir;

    // Used to specify when the "build" happens in UTC (the date to set the files to) and NOT to keep track of how long it takes to build!
    public static long buildDateUTC = System.currentTimeMillis();
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
        String sourceName = Build.get()
                                 .getName();
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
        System.arraycopy(arguments, 0, _args, 2, arguments.length);

        SimpleArgs args = new SimpleArgs(_args);

        BuildLog.disable();
        Project.reset();
        BuildLog.enable();

        make(buildOptions, args);
    }

    public static
    void make(BuildOptions buildOptions, SimpleArgs args) throws Exception {
        String title = "JavaBuilder";
        BuildLog log = BuildLog.start();
        log.title(title)
           .println(args);

        String jvmName = System.getProperty("java.vm.name");
        String jvmVersion = System.getProperty("java.version");
        String jvmVendor = System.getProperty("java.vm.specification.vendor");
        log.title("Execution JVM")
           .println(jvmVendor + ": " + jvmName + " " + jvmVersion);


        Date buildDate = args.getBuildDate();
        if (buildDate != null) {
            Builder.buildDateUTC = buildDate.getTime();

            Calendar c = Calendar.getInstance(defaultTimeZone);
            c.setTime(buildDate);
            c.add(Calendar.HOUR_OF_DAY, offset / 1000 / 60 / 60);
            c.add(Calendar.MINUTE, offset / 1000 / 60 % 60);

            log.title("Forced Date")
               .println(buildDate,
                        c.getTime()
                         .toString()
                         .replace("UTC", defaultTimeZone.getID()));
        }

        Builder builder = new Builder();
        Exception e = null;
        try {
            Builder.prepareXcompile();
            log.println();

            if (Builder.isJar || DEBUG_INSTRUCTIONS) {
                // when from IDE, we want to run it directly (in case it changes significantly)
                builder.compileBuildInstructions(args);
                log.println();
            }

            builder.start(buildOptions, args);

            log.println();

            Calendar c = Calendar.getInstance(defaultTimeZone);
            c.setTime(new Date());
            c.add(Calendar.HOUR_OF_DAY, offset / 1000 / 60 / 60);
            c.add(Calendar.MINUTE, offset / 1000 / 60 % 60);
            String localDateString = c.getTime()
                                      .toString()
                                      .replace("UTC", defaultTimeZone.getID());

            if (BuildLog.getNestedCount() > 0) {
                log.title(title)
                   .println(args,
                            localDateString,
                            "Sub-Project completed in: " + Sys.getTimePrettyFull(System.nanoTime() - builder.startTime));
            }
            else {
                // this is when the ENTIRE thing is done building
                log.title(title)
                   .println(args,
                            localDateString,
                            "Completed in: " + Sys.getTimePrettyFull(System.nanoTime() - builder.startTime),
                            "Build Date code: " + Builder.buildDateUTC);

                BuildLog.finish();
            }
        } catch (Exception e1) {
            e = e1;

            log.title("ERROR")
               .println(e.getMessage());

            BuildLog.finish_force();
        }

        // make sure to rethrow the errors
        if (e != null) {
            System.err.println(""); // add a small space

            // remove the "save build checksums" hook, since there was a problem
            if (Project.shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(Project.shutdownHook);
                Project.shutdownHook = null;
            }

            if (e instanceof InvocationTargetException) {

                Throwable cause = e.getCause();
                if (cause instanceof java.lang.ExceptionInInitializerError) {
                    cause = cause.getCause();
                }

                if (cause instanceof RuntimeException) {
                    throw (RuntimeException)cause;
                }
            }

            throw e;
        }
    }

    /**
     * @return the location of the jdk runtimes
     */
    static
    File getJdkDir() {
        // this will ALWAYS be a dir
        final File runtLocation = Build.get();

        if (Builder.isJar) {
            File parent = runtLocation.getParentFile();
            if (!new File(parent, "libs").isDirectory()) {
                parent = parent.getParentFile();
            }

            File jdk = new File(parent, "libs");
            return FileUtil.normalize(new File(jdk, "jdkRuntimes"));
        }
        else {
            final File javaFileSourceDir = Builder.getJavaFileSourceDir(Builder.class, runtLocation);
            File parent = javaFileSourceDir.getParentFile();
            if (!new File(parent, "libs").isDirectory()) {
                parent = parent.getParentFile();
            }

            File jdk = new File(parent, "libs");
            return FileUtil.normalize(new File(jdk, "jdkRuntimes"));
        }
    }

    /**
     * check to see if our jdk files have been decompressed (necessary for cross target builds)
     */
    private static
    void prepareXcompile() throws IOException {
        final File jdk = getJdkDir();
        List<File> jdkFiles = FileUtil.parseDir(jdk);
        boolean first = true;

        for (File f : jdkFiles) {
            // unLZMA + unpack200
            String name = f.getName();
            String suffix = ".pack.lzma";

            if (name.endsWith(suffix)) {
                int nameLength = f.getAbsolutePath()
                                  .length();
                String fixedName = f.getAbsolutePath()
                                    .substring(0, nameLength - suffix.length());
                File file = new File(fixedName);

                // Don't always need to decompress the jdk files. This checks if the extracted version exists
                if (!file.canRead() || file.length() == 0) {
                    if (first) {
                        first = false;
                        BuildLog.println("******************************");
                        BuildLog.println("Preparing environment");
                        BuildLog.println("******************************");
                    }

                    BuildLog.println("  Decompressing: " + f.getAbsolutePath());
                    InputStream inputStream = new FileInputStream(f);
                    // now uncompress
                    ByteArrayOutputStream outputStream = LZMA.decode(inputStream);

                    // now unpack
                    inputStream = new ByteArrayInputStream(outputStream.toByteArray());
                    outputStream = Pack200Util.Java.unpack200((ByteArrayInputStream) inputStream);

                    // now write to disk
                    inputStream = new ByteArrayInputStream(outputStream.toByteArray());

                    FileOutputStream fileOutputStream = new FileOutputStream(new File(fixedName));
                    IO.copyStream(inputStream, fileOutputStream);
                    IO.close(fileOutputStream);
                }
            }
        }

        if (!first) {
            BuildLog.println("******************************");
            BuildLog.println("Finished Preparing environment");
            BuildLog.println("******************************");
            BuildLog.println();
        }
    }

    private static
    boolean runBuild(BuildOptions buildOptions, SimpleArgs args, List<Class<?>> builders, String methodNameToCall, String projectToBuild)
                    throws Exception {
        if (builders == null) {
            return false;
        }

        methodNameToCall = methodNameToCall.toLowerCase();
        projectToBuild = projectToBuild.toLowerCase();

        boolean found = false;
        for (Class<?> c : builders) {
            String simpleName = c.getSimpleName()
                                 .toLowerCase();

            if (projectToBuild.equals(simpleName)) {
                Method[] methods = c.getMethods();

                for (Method m : methods) {
                    if (m.getName()
                         .toLowerCase()
                         .equals(methodNameToCall)) {

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
            buffer.append(p)
                  .append(File.separator);
        }

        int length = buffer.length();
        buffer.delete(length - 1, length);

        return buffer.toString();
    }



    /**
     * Gets the java file (from class file) when running from an IDE.
     */
    public static
    File getJavaFileSourceDir(final Class<?> clazz, File rootFile) {
        return getJavaFileSourceDir(clazz.getCanonicalName(), rootFile);
    }

    /**
     * Gets the java file (from class file canonical name) when running from an IDE.
     */
    public static
    File getJavaFileSourceDir(final String classCanonicalName, File rootFile) {
        String rootPath = rootFile.getAbsolutePath();

        // our dorkbox util library is reused everywhere, and it is important to ALWAYS pull fresh. So we grab from the source
        final boolean isDir = rootFile.isDirectory();

        if (!isDir && rootPath.endsWith(".jar")) {
            String fileName = classCanonicalName;
            String convertJava = fileName.replace('.', File.separatorChar) + ".java";

            for (Entry<String, File> module : moduleCache.entrySet()) {
                // have to check to make sure that the class is actually in the specified module.
                final File sourceDir = module.getValue();
                if (new File(sourceDir, convertJava).exists()) {
                    return sourceDir.getAbsoluteFile();
                }
            }
        }

        if (isDir) {
            final File eclipseSrc = new File(rootFile.getParentFile(), "src");

            if (eclipseSrc.exists()) {
                // eclipse (default)
                return eclipseSrc.getAbsoluteFile();
            }
            else {
                // intellij (default)
                String moduleName = rootPath.substring(rootPath.lastIndexOf(File.separatorChar) + 1);
                File parent = rootFile.getParentFile()
                                      .getParentFile()
                                      .getParentFile()
                                      .getParentFile()
                                      .getParentFile();
                // our src directory is always under the module dir
                File dir = getModuleDir(parent, moduleName);
                if (dir != null) {
                    return dir.getAbsoluteFile();
                }

                // it COULD BE that it's in the "current" location
                dir = getModuleDir(FileUtil.normalize(".."), moduleName);
                if (dir != null) {
                    return dir.getAbsoluteFile();
                }
            }
        }

        if (rootPath.endsWith(".java")) {
            return rootFile.getParentFile();
        }

        return null;
    }

    /**
     * Converts a class to it's .java file, but returns the relative path of the .java file to a specific directory in it's hierarchy.
     * <p/>
     * For example: getChildRelativeToDir("/a/b/c/d/e.bah", "c") -> "d/e.bah"
     *
     * @return throws runtime exception if it doesn't exist
     */
    public static
    String getJavaFileRelativeToDir(Class<?> clazz, String dirInHeirarchy) {
        Paths javaFile = Builder.getJavaFile(clazz);
        String childRelativeToDir = FileUtil.getChildRelativeToDir(javaFile.toString(), dirInHeirarchy);
        if (childRelativeToDir == null) {
            throw new FileNotFoundRuntimeException("Cannot find child path in file: '" + dirInHeirarchy + "' in path '" + javaFile.toString() + "'");
        }
        return childRelativeToDir;
    }

    /**
     * Converts a class to it's .java file. Throws IOException if the file is not found
     */
    public static
    Paths getJavaFile(Class<?> clazz) {
        File rootFile = Build.get(clazz);
        assert rootFile != null;

        String rootPath = rootFile.getAbsolutePath();
        String fileName = clazz.getCanonicalName();

        final File sourceDir = getJavaFileSourceDir(fileName, rootFile);

        if (sourceDir != null) {
            String convertJava = fileName.replace('.', File.separatorChar) + ".java";
            return new Paths(sourceDir.getAbsolutePath(), convertJava);
        }
        else if (rootPath.endsWith(Project.JAR_EXTENSION) && isZipFile(rootFile)) {
            // check to see if it's a zip file

            // have to go digging for it!
            // the sources can be IN THE FILE, or they are my sources, and are in the src file.
            String nameAsFile = fileName.replace('.', File.separatorChar) + ".java";

            try {
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
            } catch (IOException e) {
                throw new FileNotFoundRuntimeException("Cannot find source file from zip: '" + fileName + "'");
            }
        }
        // not found
        throw new FileNotFoundRuntimeException("Cannot find source file: '" + fileName + "'");
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
        String directoryName = fileName.replace('.', File.separatorChar)
                                       .substring(0, fileName.lastIndexOf('.'));

        final File javaFile = getJavaFileSourceDir(clazz, rootFile);

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
                    if (!file.getParentFile()
                             .exists() && !file.getParentFile()
                                               .mkdirs()) {
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

    /**
     * Register the following source module locations for the compile stage.
     * </p>
     * This is to ensure that when we are looking for the java source file, we look in the project, not the jar. This is because
     * the source can exist in multiple locations (potentially), or in a non-specific location. This makes it available, and overrides
     * the default location (for the src file).
     */
    public static
    void registerModule(final String name, final String src) {
        moduleCache.put(name, FileUtil.normalize(src));
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
            final File src = new File(candidate, "src");
            if (src.isDirectory()) {
                // we also want to CACHE the module dir, as the search can take a while.
                moduleCache.put(moduleName, src);
                return src;
            }
        }

        return null;
    }

    private static
    void getDirs(final int i, ArrayList<File> candidates, final File parent, final String moduleName) {
        if (parent == null || i > 4) {
            return;
        }

        final File[] files = parent.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (file.getName()
                            .equals(moduleName)) {
                        candidates.add(file);
                    }
                    else {
                        getDirs(i + 1, candidates, file, moduleName);
                    }
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
        return FileUtil.isZipFile(file);
    }


    /**
     * Deletes a file or directory and all files and sub-directories under it.
     *
     * @return true iff the file/dir was deleted (or didn't exist)
     */
    public static
    boolean delete(String target) {
        return delete(FileUtil.normalize(target));
    }

    /**
     * Deletes a file or directory and all files and sub-directories under it.
     *
     * @return true iff the file/dir was deleted (or didn't exist)
     */
    public static
    boolean delete(File target) {
        target = FileUtil.normalize(target);

        if (target.exists()) {
            BuildLog.title("Deleting")
                    .println(target.getAbsolutePath());

            return FileUtil.delete(target);
        }
        else {
            return true;
        }
    }

    public static
    boolean delete(File target, String... filesToIgnore) {
        target = FileUtil.normalize(target);

        final List<String> strings = new ArrayList<String>(Arrays.asList(filesToIgnore));
        strings.add(0, target.getAbsolutePath());
        strings.add(1, "Ignoring:");

        BuildLog.title("Deleting")
                .println(strings.toArray());

        return FileUtil.delete(target, filesToIgnore);
    }

    // UNICODE is from: https://en.wikipedia.org/wiki/List_of_Unicode_characters#Box_Drawing

    public static
    File moveFile(String source, String target) throws IOException {
        source = FileUtil.normalize(source).getAbsolutePath();
        target = FileUtil.normalize(target).getAbsolutePath();


        BuildLog.title("Moving file")
                .println("  ╭─ " + source, "╰─> " + target);

        return FileUtil.moveFile(source, target);
    }

    public static
    File copyFile(File source, File target) throws IOException {
        source = FileUtil.normalize(source);
        target = FileUtil.normalize(target);


        BuildLog.title("Copying file")
                .println("  ╭─ " + source.getAbsolutePath(), "╰─> " + target.getAbsolutePath());

        return FileUtil.copyFile(source, target);
    }

    public static
    void copyFile(String source, String target) throws IOException {
        source = FileUtil.normalize(source).getAbsolutePath();
        target = FileUtil.normalize(target).getAbsolutePath();

        BuildLog.title("Copying file")
                .println("  ╭─ " + source, "╰─> " + target);

        FileUtil.copyFile(source, target);
    }

    public static
    File copyFileToDir(File source, File target) throws IOException {
        source = FileUtil.normalize(source);
        target = FileUtil.normalize(target);


        BuildLog.title("Copying file to dir")
                .println("  ╭─ " + source.getAbsolutePath(), "╰─> " + target.getAbsolutePath());

        return FileUtil.copyFileToDir(source, target);
    }

    public static
    void copyFileToDir(String source, String target) throws IOException {
        source = FileUtil.normalize(source).getAbsolutePath();
        target = FileUtil.normalize(target).getAbsolutePath();

        BuildLog.title("Copying file to dir")
                .println("  ╭─ " + source, "╰─> " + target);

        FileUtil.copyFileToDir(source, target);
    }

    public static
    void copyDirectory(String source, String target, String... dirNamesToIgnore) throws IOException {
        source = FileUtil.normalize(source).getAbsolutePath();
        target = FileUtil.normalize(target).getAbsolutePath();

        BuildLog.title("Copying dir")
                .println("  ╭─ " + source, "╰─> " + target);
        FileUtil.copyDirectory(source, target, dirNamesToIgnore);
    }

    // This is used to keep track of how long individual builds take. This can also be nested as many times as needed.
    private final long startTime = System.nanoTime();
    private ByteClassloader classloader = null;

    private
    Builder() {
        BuildLog.disable();
        Project.reset();
        BuildLog.enable();
    }

    // loads the build.oak file information
    private
    void compileBuildInstructions(SimpleArgs args) throws Exception {
        HashMap<String, ArrayList<String>> data = BuildParser.parse(args);

        if (data == null) {
            throw new Exception();
        }

        // data elements are always a list
        Paths classPaths = new Paths();
        Paths sources = new Paths();

        final ArrayList<String> classpaths_source = data.get("classpath");
        final ArrayList<String> sources_source = data.get("source");


        ArrayList<String> implicit = new ArrayList<String>();

        for (String classpath : classpaths_source) {
            File file = FileUtil.normalize(classpath);
            implicit.add(file.getAbsolutePath());

            if (file.canRead() || file.isDirectory()) {
                if (file.isFile()) {
                    classPaths.add(file.getParent(), file.getName());
                } else {
                    // it's a directory, so we should add everything in it.
                    classPaths.glob(file.getAbsolutePath(), "**/*.jar", "!jdkRuntimes", "!*source*");
                    classPaths.glob(file.getAbsolutePath(), "**/*.class");
                }
            }
        }

        // ALWAYS add ourself to the classpath path!
        classPaths.addFile(Build.get().getAbsolutePath());

        if (!implicit.isEmpty()) {
            implicit.add(0, "Classpaths");
            BuildLog.println(implicit.toArray(new String[0]));
        }
        else {
            BuildLog.title("WARNING").println("No classpath specified!");
            return;
        }


        implicit = new ArrayList<String>();
        for (String source : sources_source) {
            File file = FileUtil.normalize(source);
            implicit.add(file.getAbsolutePath());

            if (file.canRead() || file.isDirectory()) {
                if (file.isFile()) {
                    sources.add(file.getParent(), file.getName());
                }
                else {
                    // it's a directory, so we should add everything in it.
                    sources.glob(file.getAbsolutePath(), "**/*.java");
                }
            }
            else {
                // it's a pattern or something, so we should add everything in it.
                sources.glob(new File("blah").getParent(), source);
            }
        }

        if (!implicit.isEmpty()) {
            implicit.add(0, "Sources");
            BuildLog.println(implicit.toArray(new String[0]));
        }
        else {
            BuildLog.title("WARNING").println("No sources specified!");
            return;
        }

        ByteClassloader bytesClassloader = new ByteClassloader(sources.getFiles());

        ProjectJava project = ProjectJava.create("Builder")
                                         .classPath(classPaths)
                                         .compilerClassloader(bytesClassloader)
                                         .sourcePath(sources);

        // only if we have data, should we build
        if (!data.isEmpty()) {
            boolean isDebug = args.has("-debug");
            try {
                if (!isDebug) {
                    BuildLog.disable();
                }

                project.options().compiler.forceRebuild = true;
                project.build(OS.javaVersion);

                BuildLog.disable();
                Project.reset();
                BuildLog.enable();
            } catch (Exception e) {
                throw e;
            } finally {
                if (!isDebug) {
                    BuildLog.enable();
                }
            }

            this.classloader = bytesClassloader;
        }
    }

    private
    void start(BuildOptions buildOptions, SimpleArgs args) throws Exception {

        dorkbox.annotation.Builder detector;

        if (this.classloader != null) {
            detector = AnnotationDetector.scan(this.classloader, new ClassByteIterator(this.classloader, null));
        }
        else {
            detector = AnnotationDetector.scanClassPath();
        }

        List<Class<?>> controllers = detector.forAnnotations(Config.class)
                                             .collect(AnnotationDefaults.getType);

        if (controllers != null) {
            if (controllers.size() > 1) {
                List<String> newList = new ArrayList<String>();
                for (Class<?> controller : controllers) {
                    newList.add(controller.getSimpleName());
                }

                BuildLog.title("Warning")
                        .println("Multiple controllers defined with @" + Config.class.getSimpleName() + ". Only using '" + newList.get(0) + "'", newList);
            }

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

        BuildLog.title("Debug info")
                .println(buildOptions.compiler.debugEnabled ? "Enabled" : "Disabled");
        BuildLog.title("Release status")
                .println(buildOptions.compiler.release ? "Enabled" : "Disabled");
        BuildLog.println();

        // now we want to update/search for all project builders.
        boolean found;
        if (this.classloader != null) {
            detector = AnnotationDetector.scan(this.classloader, new ClassByteIterator(this.classloader, null));
        }
        else {
            detector = AnnotationDetector.scanClassPath();
        }
        List<Class<?>> builders = detector.forAnnotations(Instructions.class)
                                          .collect(AnnotationDefaults.getType);

        if (args.getMode()
                .equals(Builder.BUILD_MODE)) {
            String projectToBuild = args.get(1);
            String methodNameToCall = args.get(2);
            if (methodNameToCall == null) {
                BuildLog.title("Method")
                        .println("None specified, using default: '" + Builder.BUILD_MODE + "'");

                methodNameToCall = Builder.BUILD_MODE;
            }
            else {
                BuildLog.title("Method")
                        .println(methodNameToCall);
            }


            found = runBuild(buildOptions, args, builders, methodNameToCall, projectToBuild);

            if (controllers != null && !found) {
                final IOException ioException = new IOException("Unable to find a builder for: " + args.getParameters());
                ioException.setStackTrace(new StackTraceElement[0]);
                throw ioException;
            }

            BuildLog.finish();
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
}
