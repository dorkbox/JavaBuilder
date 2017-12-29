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
package dorkbox.build.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dorkbox.BuildOptions;
import dorkbox.Builder;
import dorkbox.build.Project;
import dorkbox.build.util.wildcard.Paths;
import dorkbox.util.Base64Fast;
import dorkbox.util.IO;

/**
 * CHECKSUM LOGIC
 */
@SuppressWarnings({"Convert2Diamond", "AnonymousHasLambdaAlternative"})
public
class Hash {

    private static final ThreadLocal<MessageDigest> digestThreadLocal = new ThreadLocal<MessageDigest>() {
        @Override
        protected
        MessageDigest initialValue() {
            try {
                return MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException ignored) {
                // will never happen, since SHA1 is part of java.
                return null;
            }
        }
    };


    // set by Project.java
    public static boolean forceRebuildAll = false;

    private transient Paths checksumPaths = new Paths();
    private final String projectName;
    private BuildOptions buildOptions;

    public
    Hash(final String projectName, BuildOptions buildOptions) {
        this.projectName = projectName;
        this.buildOptions = buildOptions;
    }

    /**
     * Add paths to be checksum'd.
     */
    public
    void add(final Paths paths) {
        this.checksumPaths.add(paths);
    }

    public
    void add(final String file) {
        this.checksumPaths.addFile(file);
    }

    /**
     * @return true if the checksums for path match the saved checksums and the jar file exists, false if the check failed and the
     *         project needs to rebuild
     */
    public
    boolean verifyChecksums() throws IOException {
        if (forceRebuildAll || this.buildOptions.compiler.forceRebuild) {
            return false;
        }

        // check to see if our SOURCES *and check-summed files* have changed.
        String hashedContents = generateChecksums(this.checksumPaths);
        String checkContents = Builder.settings.get(this.projectName, String.class);

        return hashedContents != null && hashedContents.equals(checkContents);
    }

    /**
     * Saves the checksums for a given path
     */
    public
    void saveChecksums() throws IOException {
        // hash/save the sources *and check-summed files* files
        String hashedContents = generateChecksums(this.checksumPaths);
        Builder.settings.save(this.projectName, hashedContents);
    }







    /**
     * Generates checksums for the given path
     */
    public static
    String generateChecksum(File file) throws IOException {
        synchronized (Project.class) {
            // calculate the hash of file
            boolean found = false;
            if (file.isFile() && file.canRead()) {
                found = true;
            }

            if (!found) {
                return null;
            }

            MessageDigest sha1 = digestThreadLocal.get();
            sha1.reset();

            FileInputStream inputStream = null;
            try {
                inputStream = new FileInputStream(file);
                FileChannel channel = inputStream.getChannel();

                long length = file.length();
                if (length > Integer.MAX_VALUE) {
                    // you could make this work with some care,
                    // but this code does not bother.
                    throw new IOException("File " + file.getAbsolutePath() + " is too large.");
                }

                ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, length);

                int bufsize = 1024 * 8;
                byte[] temp = new byte[bufsize];
                int bytesRead = 0;

                while (bytesRead < length) {
                    int numBytes = (int) length - bytesRead >= bufsize ? bufsize : (int) length - bytesRead;
                    buffer.get(temp, 0, numBytes);
                    sha1.update(temp, 0, numBytes);
                    bytesRead += numBytes;
                }

                byte[] hashBytes = sha1.digest();
                return Base64Fast.encodeToString(hashBytes, false);
            } finally {
                IO.closeQuietly(inputStream);
            }
        }
    }

    /**
     * Generates checksums for the given path
     */
    public static
    String generateChecksums(Paths... paths) throws IOException {
        synchronized (Project.class) {
            // calculate the hash of all the files in the source path
            Set<String> names = new HashSet<String>(64);

            for (Paths path : paths) {
                names.addAll(path.getPaths());
            }

            // have to make sure the list is sorted, so the iterators are consistent
            List<String> sortedNames = new ArrayList<String>(names.size());
            sortedNames.addAll(names);
            Collections.sort(sortedNames);

            // hash of all files. faster than using java to hash files
            MessageDigest sha1 = digestThreadLocal.get();
            sha1.reset();

            int bufsize = 1024 * 8;
            byte[] temp = new byte[bufsize];
            int bytesRead = 0;

            boolean found = false;
            for (String name : sortedNames) {
                File file = new File(name);
                if (file.isFile() && file.canRead()) {
                    found = true;
                    bytesRead = 0;

                    FileInputStream inputStream = null;
                    try {
                        inputStream = new FileInputStream(file);
                        FileChannel channel = inputStream.getChannel();

                        long length = file.length();
                        if (length > Integer.MAX_VALUE) {
                            // you could make this work with some care,
                            // but this code does not bother.
                            throw new IOException("File " + file.getAbsolutePath() + " is too large.");
                        }

                        ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, length);

                        while (bytesRead < length) {
                            int numBytes = (int) length - bytesRead >= bufsize ? bufsize : (int) length - bytesRead;
                            buffer.get(temp, 0, numBytes);
                            sha1.update(temp, 0, numBytes);
                            bytesRead += numBytes;
                        }
                    } finally {
                        IO.closeQuietly(inputStream);
                    }
                }
            }

            if (!found) {
                return null;
            }

            byte[] hashBytes = sha1.digest();
            return Base64Fast.encodeToString(hashBytes, false);
        }
    }
}
