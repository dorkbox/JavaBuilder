/*
 * Copyright (c) 2009, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *     * Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Modified by dorkbox, llc
 */
package dorkbox.build.util.wildcard;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Collects filesystem paths using wildcards, preserving the directory structure. Copies, deletes, and zips paths.
 */
@SuppressWarnings({"Duplicates", "WeakerAccess"})
public
class Paths implements Iterable<Path> {
    static private final Comparator<Path> LONGEST_TO_SHORTEST = new Comparator<Path>() {
        @Override
        public
        int compare(Path s1, Path s2) {
            return s2.absolute()
                     .length() - s1.absolute()
                                   .length();
        }
    };

    static private List<String> defaultGlobExcludes;


    /**
     * Sets the exclude patterns that will be used in addition to the excludes specified for all glob searches.
     */
    static public
    void setDefaultGlobExcludes(String... defaultGlobExcludes) {
        Paths.defaultGlobExcludes = Arrays.asList(defaultGlobExcludes);
    }

    /**
     * Deletes a directory and all files and directories it contains.
     */
    static private
    boolean deleteDirectory(File file) {
        if (file.exists()) {
            File[] files = file.listFiles();
            for (int i = 0, n = files.length; i < n; i++) {
                if (files[i].isDirectory()) {
                    deleteDirectory(files[i]);
                }
                else {
                    files[i].delete();
                }
            }
        }
        return file.delete();
    }

    final HashSet<Path> paths = new HashSet<Path>(32);

    /**
     * Creates an empty Paths object.
     */
    public
    Paths() {
    }

    /**
     * Creates a Paths object and calls {@link #glob(String, String[])} with the specified arguments.
     */
    public
    Paths(File dir, String... patterns) {
        glob(dir.getAbsolutePath(), patterns);
    }

    /**
     * Creates a Paths object and calls {@link #glob(String, String[])} with the specified arguments.
     */
    public
    Paths(String dir, String... patterns) {
        glob(dir, patterns);
    }

    /**
     * Collects all files and directories in the specified directory matching the wildcard patterns.
     *
     * @param dir The directory containing the paths to collect. If it does not exist, no paths are collected. If null, "." is
     *         assumed.
     * @param patterns The wildcard patterns of the paths to collect or exclude. Patterns may optionally contain wildcards
     *         represented by asterisks and question marks. If empty or omitted then the dir parameter is split on the "|"
     *         character, the first element is used as the directory and remaining are used as the patterns. If null, ** is
     *         assumed (collects all paths).<br>
     *         <br>
     *         A single question mark (?) matches any single character. Eg, something? collects any path that is named
     *         "something" plus any character.<br>
     *         <br>
     *         A single asterisk (*) matches any characters up to the next slash (/). Eg, *\*\something* collects any path that
     *         has two directories of any name, then a file or directory that starts with the name "something".<br>
     *         <br>
     *         A double asterisk (**) matches any characters. Eg, **\something\** collects any path that contains a directory
     *         named "something".<br>
     *         <br>
     *         A pattern starting with an exclamation point (!) causes paths matched by the pattern to be excluded, even if other
     *         patterns would select the paths.
     */
    public
    Paths glob(File dir, String... patterns) {
        return glob(dir.getAbsolutePath(), patterns);
    }

    /**
     * Collects all files and directories in the specified directory matching the wildcard patterns.
     *
     * @param dir The directory containing the paths to collect. If it does not exist, no paths are collected. If null, "." is
     *         assumed.
     * @param patterns The wildcard patterns of the paths to collect or exclude. Patterns may optionally contain wildcards
     *         represented by asterisks and question marks. If empty or omitted then the dir parameter is split on the "|"
     *         character, the first element is used as the directory and remaining are used as the patterns. If null, ** is
     *         assumed (collects all paths).<br>
     *         <br>
     *         A single question mark (?) matches any single character. Eg, something? collects any path that is named
     *         "something" plus any character.<br>
     *         <br>
     *         A single asterisk (*) matches any characters up to the next slash (/). Eg, *\*\something* collects any path that
     *         has two directories of any name, then a file or directory that starts with the name "something".<br>
     *         <br>
     *         A double asterisk (**) matches any characters. Eg, **\something\** collects any path that contains a directory
     *         named "something".<br>
     *         <br>
     *         A pattern starting with an exclamation point (!) causes paths matched by the pattern to be excluded, even if other
     *         patterns would select the paths.
     */
    public
    Paths glob(String dir, String... patterns) {
        if (dir == null) {
            dir = ".";
        }
        if (patterns != null && patterns.length == 0) {
            String[] split = dir.split("\\|");
            if (split.length > 1) {
                dir = split[0];
                patterns = new String[split.length - 1];
                for (int i = 1, n = split.length; i < n; i++) {
                    patterns[i - 1] = split[i];
                }
            }
        }
        File dirFile = new File(dir);
        if (!dirFile.exists()) {
            return this;
        }

        List<String> includes = new ArrayList<String>();
        List<String> excludes = new ArrayList<String>();
        if (patterns != null) {
            for (String pattern : patterns) {
                if (pattern.charAt(0) == '!') {
                    excludes.add(pattern.substring(1));
                }
                else {
                    includes.add(pattern);
                }
            }
        }
        if (includes.isEmpty()) {
            includes.add("**");
        }

        if (defaultGlobExcludes != null) {
            excludes.addAll(defaultGlobExcludes);
        }

        GlobScanner scanner = new GlobScanner(dirFile, includes, excludes);
        String rootDir = scanner.rootDir()
                                .getPath()
                                .replace('\\', '/');
        if (!rootDir.endsWith("/")) {
            rootDir += '/';
        }
        for (String filePath : scanner.matches()) {
            paths.add(new Path(rootDir, filePath));
        }
        return this;
    }

    /**
     * Creates a Paths object and calls {@link #glob(String, List)} with the specified arguments.
     */
    public
    Paths(String dir, List<String> patterns) {
        glob(dir, patterns);
    }

    /**
     * Calls {@link #glob(String, String...)}.
     */
    public
    Paths glob(String dir, List<String> patterns) {
        if (patterns == null) {
            throw new IllegalArgumentException("patterns cannot be null.");
        }
        glob(dir, patterns.toArray(new String[patterns.size()]));
        return this;
    }

    /**
     * Collects all files and directories in the specified directory matching the regular expression patterns. This method is much
     * slower than {@link #glob(String, String...)} because every file and directory under the specified directory must be
     * inspected.
     *
     * @param dir The directory containing the paths to collect. If it does not exist, no paths are collected.
     * @param patterns The regular expression patterns of the paths to collect or exclude. If empty or omitted then the dir
     *         parameter is split on the "|" character, the first element is used as the directory and remaining are used as the
     *         patterns. If null, ** is assumed (collects all paths).<br>
     *         <br>
     *         A pattern starting with an exclamation point (!) causes paths matched by the pattern to be excluded, even if other
     *         patterns would select the paths.
     */
    public
    Paths regex(String dir, String... patterns) {
        if (dir == null) {
            dir = ".";
        }
        if (patterns != null && patterns.length == 0) {
            String[] split = dir.split("\\|");
            if (split.length > 1) {
                dir = split[0];
                patterns = new String[split.length - 1];
                for (int i = 1, n = split.length; i < n; i++) {
                    patterns[i - 1] = split[i];
                }
            }
        }
        File dirFile = new File(dir);
        if (!dirFile.exists()) {
            return this;
        }

        List<String> includes = new ArrayList<String>();
        List<String> excludes = new ArrayList<String>();
        if (patterns != null) {
            for (String pattern : patterns) {
                if (pattern.charAt(0) == '!') {
                    excludes.add(pattern.substring(1));
                }
                else {
                    includes.add(pattern);
                }
            }
        }
        if (includes.isEmpty()) {
            includes.add(".*");
        }

        RegexScanner scanner = new RegexScanner(dirFile, includes, excludes);
        String rootDir = scanner.rootDir()
                                .getPath()
                                .replace('\\', '/');
        if (!rootDir.endsWith("/")) {
            rootDir += '/';
        }
        for (String filePath : scanner.matches()) {
            paths.add(new Path(rootDir, filePath));
        }
        return this;
    }

    /**
     * Copies the files and directories to the specified directory.
     *
     * @return A paths object containing the paths of the new files.
     */
    public
    Paths copyTo(String destDir) throws IOException {
        Paths newPaths = new Paths();
        for (Path path : paths) {
            File destFile = new File(destDir, path.name);
            File srcFile = path.file();
            if (srcFile.isDirectory()) {
                destFile.mkdirs();
            }
            else {
                destFile.getParentFile()
                        .mkdirs();
                copyFile(srcFile, destFile);
            }
            newPaths.paths.add(new Path(destDir, path.name));
        }
        return newPaths;
    }

    /**
     * Copies one file to another.
     */
    static private
    void copyFile(File in, File out) throws IOException {
        FileInputStream sourceStream = new FileInputStream(in);
        FileOutputStream destinationStream = new FileOutputStream(out);
        FileChannel sourceChannel = sourceStream.getChannel();
        FileChannel destinationChannel = destinationStream.getChannel();
        sourceChannel.transferTo(0, sourceChannel.size(), destinationChannel);
        sourceChannel.close();
        sourceStream.close();
        destinationChannel.close();
        destinationStream.close();
    }

    /**
     * Deletes all the files, directories, and any files in the directories.
     *
     * @return False if any file could not be deleted.
     */
    public
    boolean delete() {
        boolean success = true;
        List<Path> pathsCopy = new ArrayList<Path>(paths);
        Collections.sort(pathsCopy, LONGEST_TO_SHORTEST);
        for (File file : getFiles(pathsCopy)) {
            if (file.isDirectory()) {
                if (!deleteDirectory(file)) {
                    success = false;
                }
            }
            else {
                if (!file.delete()) {
                    success = false;
                }
            }
        }
        return success;
    }

    /**
     * Returns the absolute paths delimited by commas.
     */
    @Override
    public
    String toString() {
        return toString(", ");
    }

    /**
     * Returns the absolute paths delimited by the specified character.
     */
    public
    String toString(String delimiter) {
        StringBuilder buffer = new StringBuilder(256);
        for (String path : getPaths()) {
            if (buffer.length() > 0) {
                buffer.append(delimiter);
            }
            buffer.append(path);
        }
        return buffer.toString();
    }

    /**
     * Returns the full paths.
     */
    public
    List<String> getPaths() {
        ArrayList<String> stringPaths = new ArrayList<String>(paths.size());
        for (File file : getFiles()) {
            stringPaths.add(file.getPath());
        }
        return stringPaths;
    }

    /**
     * Retuns the backing HashSet for these paths.
     */
    public
    HashSet<Path> get() {
        return paths;
    }

    /**
     * Returns the paths as File objects.
     */
    public
    List<File> getFiles() {
        return getFiles(new ArrayList<Path>(paths));
    }

    private
    ArrayList<File> getFiles(List<Path> paths) {
        ArrayList<File> files = new ArrayList<File>(paths.size());
        int i = 0;
        for (Path path : paths) {
            files.add(path.file());
        }
        return files;
    }

    /**
     * Compresses the files and directories specified by the paths into a new zip file at the specified location. If there are no
     * paths or all the paths are directories, no zip file will be created.
     */
    public
    void zip(String destFile) throws IOException {
        Paths zipPaths = filesOnly();
        if (zipPaths.paths.isEmpty()) {
            return;
        }
        byte[] buf = new byte[1024];
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(destFile));
        try {
            for (Path path : zipPaths.paths) {
                File file = path.file();
                out.putNextEntry(new ZipEntry(path.name.replace('\\', '/')));
                FileInputStream in = new FileInputStream(file);
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }

    /**
     * Returns a Paths object containing the paths that are files.
     */
    public
    Paths filesOnly() {
        Paths newPaths = new Paths();
        for (Path path : paths) {
            if (path.file()
                    .isFile()) {
                newPaths.paths.add(path);
            }
        }
        return newPaths;
    }

    /**
     * Returns the number of paths.
     *
     * @return the number of paths
     */
    public
    int size() {
        return paths.size();
    }

    /**
     * Returns <tt>true</tt> if there are no paths
     *
     * @return <tt>true</tt> if there are no paths
     */
    public
    boolean isEmpty() {
        return paths.isEmpty();
    }

    /**
     * Returns a Paths object containing the paths that are files, as if each file were selected from its parent directory.
     */
    public
    Paths flatten() {
        Paths newPaths = new Paths();
        for (Path path : paths) {
            File file = path.file();
            if (file.isFile()) {
                newPaths.paths.add(new Path(file.getParent(), file.getName()));
            }
        }
        return newPaths;
    }

    /**
     * Returns a Paths object containing the paths that are directories.
     */
    public
    Paths dirsOnly() {
        Paths newPaths = new Paths();
        for (Path path : paths) {
            if (path.file()
                    .isDirectory()) {
                newPaths.paths.add(path);
            }
        }
        return newPaths;
    }

    /**
     * Returns the portion of the path after the root directory where the path was collected.
     */
    public
    List<String> getRelativePaths() {
        ArrayList<String> stringPaths = new ArrayList<String>(paths.size());
        for (Path path : paths) {
            stringPaths.add(path.name);
        }
        return stringPaths;
    }

    /**
     * Returns the paths' filenames.
     */
    public
    List<String> getNames() {
        ArrayList<String> stringPaths = new ArrayList<String>(paths.size());
        for (File file : getFiles()) {
            stringPaths.add(file.getName());
        }
        return stringPaths;
    }

    /**
     * Adds a single path to this Paths object.
     */
    public
    Paths addFile(String fullPath) {
        File file = new File(fullPath);
        paths.add(new Path(file.getParent(), file.getName()));
        return this;
    }

    /**
     * Adds a single path to this Paths object.
     */
    public
    Paths add(String dir, String name) {
        paths.add(new Path(dir, name));
        return this;
    }

    /**
     * Adds all paths from the specified Paths object to this Paths object.
     */
    public
    void add(Paths paths) {
        this.paths.addAll(paths.paths);
    }

    /**
     * Iterates over the absolute paths. The iterator supports the remove method.
     */
    @Override
    public
    Iterator<Path> iterator() {
        return new Iterator<Path>() {
            private Iterator<Path> iter = paths.iterator();

            @Override
            public
            boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public
            void remove() {
                iter.remove();
            }

            @Override
            public
            Path next() {
                return iter.next();
            }
        };
    }

    /**
     * Iterates over the paths as File objects. The iterator supports the remove method.
     */
    public
    Iterator<File> fileIterator() {
        return new Iterator<File>() {
            private Iterator<Path> iter = paths.iterator();

            @Override
            public
            void remove() {
                iter.remove();
            }

            @Override
            public
            File next() {
                return iter.next()
                           .file();
            }

            @Override
            public
            boolean hasNext() {
                return iter.hasNext();
            }
        };
    }
}
