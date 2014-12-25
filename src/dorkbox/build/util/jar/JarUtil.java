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
package dorkbox.build.util.jar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.bouncycastle.crypto.digests.SHA512Digest;

import com.esotericsoftware.wildcard.Paths;
import com.ice.tar.TarEntry;
import com.ice.tar.TarInputStream;

import dorkbox.Build;
import dorkbox.BuildOptions;
import dorkbox.license.License;
import dorkbox.util.Base64Fast;
import dorkbox.util.FileUtil;
import dorkbox.util.LZMA;
import dorkbox.util.OS;
import dorkbox.util.OsType;
import dorkbox.util.Sys;

public class JarUtil {
    public static int JAR_COMPRESSION_LEVEL = 9;

    public static byte[] ZIP_HEADER = { 80, 75, 3, 4 };  // PK34

    public static final String metaInfName = "META-INF/";
    public static final String configFile  = "config.ini";

    /**
     * @return true if the file is a zip/jar file
     */
    public static boolean isZipFile(File file) {
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

    /**
     * @return true if the file is a zip/jar stream
     */
    public static boolean isZipStream(ByteArrayInputStream input) {
        boolean isZip = true;
        int length = ZIP_HEADER.length;

        try {
            input.mark(length+1);

            for (int i = 0; i < length; i++) {
                byte read = (byte) input.read();
                if (read != ZIP_HEADER[i]) {
                    isZip = false;
                    break;
                }
            }
            input.reset();
        } catch (Exception e) {
            isZip = false;
        }
        return isZip;
    }

    /**
     * retrieve the manifest from a jar file -- this will either load a
     * pre-existing META-INF/MANIFEST.MF, or return null if none
     */
    public static final Manifest getManifestFile(JarFile jarFile) throws IOException {
        JarEntry je = jarFile.getJarEntry(JarFile.MANIFEST_NAME);

        // verify that it really exists.
        if (je != null) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                je = jarEntries.nextElement();
                if (JarFile.MANIFEST_NAME.equals(je.getName())) {
                    break;
                } else {
                    je = null;
                }
            }

            // create the manifest object
            Manifest manifest = new Manifest();
            InputStream inputStream = jarFile.getInputStream(je);
            manifest.read(inputStream);
            Sys.close(inputStream);

            return manifest;
        } else {
            return null;
        }
    }

    /**
     * a helper function that can take entries from one jar file and write it to
     * another jar stream
     *
     * Will close the output stream automatically.
     */
    public static final void writeZipEntry(ZipEntry entry, ZipFile zipInputFile, ZipOutputStream zipOutputStream) throws IOException {
        // create a new entry to avoid ZipException: invalid entry compressed size
        ZipEntry newEntry = new ZipEntry(entry.getName());
        newEntry.setTime(entry.getTime());
        newEntry.setComment(entry.getComment());
        newEntry.setExtra(entry.getExtra());

        zipOutputStream.putNextEntry(newEntry);
        if (!entry.isDirectory()) {
            InputStream is = zipInputFile.getInputStream(entry);

            Sys.copyStream(is, zipOutputStream);
            Sys.close(is);
            zipOutputStream.flush();
        }

        zipOutputStream.closeEntry();
    }

    /**
     * a helper function that can take entries from one jar file and write it to
     * another jar stream
     *
     * Does NOT close any streams!
     */
    public static void writeZipEntry(ZipEntry entry, ZipInputStream zipInputStream, ZipOutputStream zipOutputStream) throws IOException {
        ZipEntry newEntry = new ZipEntry(entry.getName());
        newEntry.setTime(entry.getTime());
        newEntry.setComment(entry.getComment());
        newEntry.setExtra(entry.getExtra());

        zipOutputStream.putNextEntry(newEntry);

        if (!entry.isDirectory()) {
            Sys.copyStream(zipInputStream, zipOutputStream);
            zipOutputStream.flush();
        }

        zipInputStream.closeEntry();
        zipOutputStream.closeEntry();
    }


    public static final String updateDigest(InputStream inputStream, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[2048];
        int read = 0;
        digest.reset();

        while ((read = inputStream.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }
        Sys.close(inputStream);

        byte[] digestBytes = digest.digest();

        /*
         * Do not insert a default newline at the end of the output line, as
         * java.util.jar does its own line management (see
         * Manifest.make72Safe()). Inserting additional new lines will cause
         * line-wrapping problems.
         */
        return Base64Fast.encodeToString(digestBytes, false);
    }

    public static final ByteArrayOutputStream createNewJar(JarFile jar, String name, byte[] manifestBytes,
                    byte[] signatureFileManifestBytes, byte[] signatureBlockBytes) throws IOException {

        name = name.toUpperCase();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        JarOutputStream jarOutputStream = new JarOutputStream(new BufferedOutputStream(byteArrayOutputStream));
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        // cannot use the jarInputStream technique here, since i'm reordering
        // the contents of the jar.

        // MANIFEST ENTRIES MUST BE FIRST
        // write out the manifest to the output jar stream

        JarEntry manifestFile = new JarEntry(JarFile.MANIFEST_NAME);
        jarOutputStream.putNextEntry(manifestFile);
        jarOutputStream.write(manifestBytes, 0, manifestBytes.length);
        jarOutputStream.closeEntry();

        String signatureAlias = metaInfName + name;

        // write out the signature file
        String signatureFileName = signatureAlias + ".SF";
        JarEntry signatureFileEntry = new JarEntry(signatureFileName);
        jarOutputStream.putNextEntry(signatureFileEntry);
        jarOutputStream.write(signatureFileManifestBytes, 0, signatureFileManifestBytes.length);
        jarOutputStream.closeEntry();

        // write out the signature block file
        String signatureBlockName = signatureAlias + ".DSA"; // forced DSA
        JarEntry signatureBlockEntry = new JarEntry(signatureBlockName);
        jarOutputStream.putNextEntry(signatureBlockEntry);
        jarOutputStream.write(signatureBlockBytes, 0, signatureBlockBytes.length);
        jarOutputStream.closeEntry();

        // commit the rest of the original entries in the
        // META-INF directory. if any of their names conflict
        // with one that we created for the signed JAR file, then
        // we simply ignore it
        Enumeration<JarEntry> metaEntries = jar.entries();
        while (metaEntries.hasMoreElements()) {
            JarEntry metaEntry = metaEntries.nextElement();
            String entryName = metaEntry.getName();

            if (entryName.startsWith(metaInfName)
                && !(JarFile.MANIFEST_NAME.equalsIgnoreCase(entryName) || signatureFileName.equalsIgnoreCase(entryName) || signatureBlockName.equalsIgnoreCase(entryName))) {

                JarUtil.writeZipEntry(metaEntry, jar, jarOutputStream);
            }
        }

        // now write out the rest of the files to the stream
        Enumeration<JarEntry> allEntries = jar.entries();
        while (allEntries.hasMoreElements()) {
            JarEntry entry = allEntries.nextElement();
            if (!entry.getName().startsWith(metaInfName)) {
                JarUtil.writeZipEntry(entry, jar, jarOutputStream);
            }
        }

        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);

        jar.close();

        return byteArrayOutputStream;
    }

    /**
     * removes all of the (META-INF, OSGI-INF, etc) information (removes the entire directory), AND ALSO removes all comments from the files
     */
    public static InputStream removeManifestCommentsAndFiles(String fileName, InputStream inputStream,
                                                             String[] pathToRemove, String[] pathToKeep) throws IOException {
        // shortcut out -- nothing to do
        if (pathToRemove == null || pathToRemove.length == 0) {
            return inputStream;
        }

        Set<String> stripped = new HashSet<String>();

        // by default, this will not have access to the manifest! (not that we care...)
        // we will ALSO lose entry comments!
        JarInputStream jarInputStream = new JarInputStream(inputStream, false);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(byteArrayOutputStream);
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        JarEntry entry;
        JAR_PROCESSING:
        while ((entry = jarInputStream.getNextJarEntry()) != null) {
            String name = entry.getName();
            boolean preserveEntry = false;

            if (pathToKeep != null) {
                for (String dir : pathToKeep) {
                    if (name.startsWith(dir)) {
                        preserveEntry = true;
                        break;
                    }
                }
            }

            if (!preserveEntry) {
                for (String dir : pathToRemove) {
                    if (name.startsWith(dir)) {
                        if (!stripped.contains(fileName)) {
                            System.err.println("Removing " + dir + " directory & signatures... (" + fileName + ")");
                            stripped.add(fileName);
                        }
                        continue JAR_PROCESSING;
                    }
                }
            }

            // create a new entry to avoid ZipException: invalid entry compressed size
            // we want to COPY this over. hashes should remain the same between builds!
            writeZipEntry(entry, jarInputStream, jarOutputStream);
        }


        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);
        Sys.close(jarInputStream);
        Sys.close(inputStream);

        // return the regular stream if we didn't strip anything!
        // convert the output stream to an input stream
        int length = byteArrayOutputStream.size();
        return new ByteArrayInputStream(byteArrayOutputStream.toByteArray(), 0, length);
    }

    /**
     * removes all of the (META-INF, OSGI-INF, etc) information (removes the entire directory), AND ALSO removes all comments from the files
     */
    public static InputStream extractLibraries(File parentLocation, InputStream inputStream, String libraryNameOverride) throws IOException {

        // these are really the file extensions that we want to extract
        // from the JAR to the DIR this file is in
        String[] libraryExtensions = new String[] {".so", ".dll", ".dylib", ".jnilib"};

        List<String> pathsToRemove = new ArrayList<String>();

        // by default, this will not have access to the manifest! (not that we care...)
        // we will ALSO lose entry comments!
        JarInputStream jarInputStream = new JarInputStream(inputStream, false);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(byteArrayOutputStream);
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        JarEntry entry;
        JAR_PROCESSING:
        while ((entry = jarInputStream.getNextJarEntry()) != null) {
            String name = entry.getName();
            boolean moveEntry = !entry.isDirectory();

            if (moveEntry) {
                moveEntry = false;
                for (String extension : libraryExtensions) {
                    if (name.endsWith(extension)) {
                        moveEntry = true;
                        break;
                    }
                }
            }

            if (moveEntry) {
                // extract file+path to parent name location

                int index = name.lastIndexOf('/');
                String nameOnly = name;
                String directParent = "";
                String fullParentPath = "";
                if (index > 0) {
                    nameOnly = name.substring(index+1);
                    fullParentPath = name.substring(0, name.length()-nameOnly.length()-1);
                    directParent = fullParentPath;

                    index = fullParentPath.lastIndexOf('/');
                    if (index > 0) {
                        directParent = fullParentPath.substring(index+1);
                    }

                    // have to put the '/' back on the path
                    fullParentPath = fullParentPath + '/';
                    pathsToRemove.add(fullParentPath);

                    // now we have to override the library name
                    index = name.lastIndexOf('.');
                    String extension = name.substring(index);
                    nameOnly = libraryNameOverride + extension;
                }

                // now, we have a parent file that needs to be TRANSLATED to our OS specific (so we can nicely load it later)
                if (directParent == null) {
                    throw new RuntimeException("DirectParent");
                }


                if (directParent.equals("darwin")) {
                    // JNA includes a multi-arch binary for x86, x86-64, and ppc.
                    directParent = OsType.MacOsX32.getName();

                    System.err.println("\t  - Moving " + directParent + " library...");
                    // now we copy the file to disk
                    File parentFile = new File(parentLocation, directParent);
                    parentFile.mkdir();

                    File file = new File(parentFile, nameOnly);

                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    Sys.copyStream(jarInputStream, fileOutputStream);
                    fileOutputStream.flush();
                    jarInputStream.closeEntry();


                    // 64 bit is the same lib
                    directParent = OsType.MacOsX64.getName();

                    System.err.println("\t  - Moving " + directParent + " library...");
                    // now we copy the file to disk
                    parentFile = new File(parentLocation, directParent);
                    parentFile.mkdir();

                    file = new File(parentFile, nameOnly);

                    fileOutputStream = new FileOutputStream(file);
                    Sys.copyStream(jarInputStream, fileOutputStream);
                    fileOutputStream.flush();
                    jarInputStream.closeEntry();

                    continue JAR_PROCESSING;
                } else if (directParent.startsWith("freebsd")) {
                    directParent = null;
                } else if (directParent.equals("linux-arm")) {
                    directParent = OsType.LinuxArm.getName();
                } else if (directParent.equals("linux-x86")) {
                    directParent = OsType.Linux32.getName();
                } else if (directParent.equals("linux-x86-64")) {
                    directParent = OsType.Linux64.getName();
                } else if (directParent.startsWith("openbsd")) {
                    directParent = null;
                } else if (directParent.startsWith("freebsd")) {
                    directParent = null;
                } else if (directParent.startsWith("sunos")) {
                    directParent = null;
                } else if (directParent.startsWith("w32ce")) {
                    directParent = null;
                } else if (directParent.equals("win32-x86")) {
                    directParent = OsType.Windows32.getName();
                } else if (directParent.equals("win32-x86-64")) {
                    directParent = OsType.Windows64.getName();
                }


                // only do something if we have a target specified
                if (directParent != null) {
                    System.err.println("\t  - Moving " + directParent + " library...");

                    // now we copy the file to disk
                    File parentFile = new File(parentLocation, directParent);
                    parentFile.mkdir();

                    File file = new File(parentFile, nameOnly);

                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    Sys.copyStream(jarInputStream, fileOutputStream);
                    fileOutputStream.flush();
                    jarInputStream.closeEntry();
                }

            }
            else {
                // create a new entry to avoid ZipException: invalid entry compressed size
                // we want to COPY this over. hashes should remain the same between builds!
                writeZipEntry(entry, jarInputStream, jarOutputStream);
            }
        }

        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);
        Sys.close(jarInputStream);
        Sys.close(inputStream);




        // NOW -- we load it again, and REMOVE the empty dirs from the jar
        System.err.println("\t  - Cleaning library directories in jar...");
        int length = byteArrayOutputStream.size();
        inputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray(), 0, length);


        // by default, this will not have access to the manifest! (not that we care...)
        // we will ALSO lose entry comments!
        jarInputStream = new JarInputStream(inputStream, false);

        byteArrayOutputStream = new ByteArrayOutputStream();
        jarOutputStream = new JarOutputStream(byteArrayOutputStream);
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        JAR_PROCESSING:
        while ((entry = jarInputStream.getNextJarEntry()) != null) {
            String name = entry.getName();

            boolean moveEntry = entry.isDirectory();

            if (moveEntry) {
                moveEntry = false;
                for (String path : pathsToRemove) {
                    if (name.startsWith(path)) {
                        continue JAR_PROCESSING;
                    }
                }
            }

            // create a new entry to avoid ZipException: invalid entry compressed size
            // we want to COPY this over. hashes should remain the same between builds!
            writeZipEntry(entry, jarInputStream, jarOutputStream);
        }

        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);
        Sys.close(jarInputStream);
        Sys.close(inputStream);


        // return the regular stream if we didn't strip anything!
        // convert the output stream to an input stream
        length = byteArrayOutputStream.size();
        return new ByteArrayInputStream(byteArrayOutputStream.toByteArray(), 0, length);
    }

    /**
     * This will ALSO normalize (pack+unpack) the jar
     *
     * Note about JarOutputStream:
     *  The JAR_MAGIC "0xCAFE" in the extra field data of the first JAR entry from our JarOutputStream implementation is
     *  not required by JAR specification. It's an "internal implementation detail" to support "executable" jar on Solaris
     *  platform. see#4138619. It would be incorrect to reject a JAR file that does not have this extra field data, from
     *  specification point of view.
     *
     *  (basically, if you use a JarOutputStream, it adds in extra crap we don't want)
     */
    public static void jar(JarOptions options) throws IOException {

        if (options.outputFile == null) {
            throw new IllegalArgumentException("jarFile cannot be null.");
        }
        if (options.inputPaths == null) {
            throw new IllegalArgumentException("inputPaths cannot be null.");
        }

        options.inputPaths = options.inputPaths.filesOnly();
        if (options.inputPaths.isEmpty()) {
            System.err.println("No files to JAR.");
            return;
        }

        List<String> fullPaths = options.inputPaths.getPaths();
        List<String> relativePaths = options.inputPaths.getRelativePaths();
        String manifestFile = null;

        String manifestName = JarFile.MANIFEST_NAME;
        int manifestIndex = relativePaths.indexOf(manifestName);

        // manage the MANIFEST
        if (manifestIndex > 0) {
            // Ensure MANIFEST.MF is first.
            relativePaths.remove(manifestIndex);
            relativePaths.add(0, manifestName);

            String manifestFullPath = fullPaths.get(manifestIndex);
            fullPaths.remove(manifestIndex);
            fullPaths.add(0, manifestFullPath);
        } else

        if (options.mainClass != null) {
            manifestFile = FileUtil.tempFile("manifest").getAbsolutePath();
            relativePaths.add(0, manifestName);
            fullPaths.add(0, manifestFile);

            Manifest manifest = new Manifest();
            Attributes attributes = manifest.getMainAttributes();
            attributes.putValue(Attributes.Name.MANIFEST_VERSION.toString(), "1.0");

            attributes.putValue(Attributes.Name.MAIN_CLASS.toString(), options.mainClass);

            if (options.otherManifestAttributes != null) {
                for (Entry<String, String> entry : options.otherManifestAttributes.entrySet()) {
                    attributes.putValue(entry.getKey(), entry.getValue());
                }
            }


            StringBuilder buffer = new StringBuilder(512);
            buffer.append(".");
            if (options.classpath != null) {
                for (String name : options.classpath.getRelativePaths()) {
                    buffer.append(' ');
                    buffer.append(name);
                }
            }
            attributes.putValue(Attributes.Name.CLASS_PATH.toString(), buffer.toString());

            FileOutputStream output = new FileOutputStream(manifestFile);
            try {
                manifest.write(output);
            } finally {
                Sys.close(output);
            }
        }

        Build.log().title("  Creating JAR").message("(" + options.inputPaths.count() + " entries)",
                                                  FileUtil.normalizeAsFile(options.outputFile));


        // CLEANUP DIRECTORIES
        Set<String> directories = figureOutDirectories(fullPaths, relativePaths);

        // NOW WE ACTUALLY MAKE THE JAR
        FileUtil.mkdir(new File(options.outputFile).getParent());
        ByteArrayOutputStream jarOutputStream = new ByteArrayOutputStream();
        JarOutputStream output = new JarOutputStream(jarOutputStream);
        output.setLevel(JAR_COMPRESSION_LEVEL);

        try {
            // quirks & zip standards.
            // - Directory names must end with a slash '/'
            // - All paths must use '/' style slashes, not '\'
            // - JarEntry names should NOT begin with '/'
            InputStream input = null;
            boolean foundManifest = false;

            // MANIFEST FIRST! There is only the manifest, as we are creating
            // the jar from scratch.
            // this means that there won't be any other "META-INF" files.
            if (manifestIndex >= 0) {
                for (int i = 0, n = fullPaths.size(); i < n; i++) {
                    String fileName = relativePaths.get(i).replace('\\', '/');
                    if (fileName.equals(manifestName)) {
                        File file = new File(fullPaths.get(i));

                        JarEntry jarEntry = new JarEntry(fileName);
                        jarEntry.setTime(file.lastModified());
                        output.putNextEntry(jarEntry);

                        input = new BufferedInputStream(new FileInputStream(file));
                        Sys.copyStream(input, output);
                        Sys.close(input);
                        output.closeEntry();

                        FileUtil.delete(manifestFile);
                        foundManifest = true;
                    }
                    if (foundManifest) {
                        fullPaths.remove(i);
                        relativePaths.remove(i);

                        break;
                    }
                }
            }

            // there won't be any OTHER manifest files, since we haven't signed
            // the jar yet...

            // NEXT all directories
            List<String> sortList = new ArrayList<String>(directories.size());
            for (String dirName : directories) {
                if (!dirName.endsWith("/")) {
                    dirName += "/";
                }

                sortList.add(dirName);
            }

            // sort them
            Collections.sort(sortList);
            for (String dirName : sortList) {
                JarEntry jarEntry = new JarEntry(dirName);
                output.putNextEntry(jarEntry);
                output.closeEntry();
            }


            class SortedFiles implements Comparable<SortedFiles> {
                public String fileName;
                public File file;

                @Override
                public int hashCode() {
                    final int prime = 31;
                    int result = 1;
                    result = prime * result + (this.fileName == null ? 0 : this.fileName.hashCode());
                    return result;
                }

                @Override
                public boolean equals(Object obj) {
                    if (this == obj) {
                        return true;
                    }
                    if (obj == null) {
                        return false;
                    }
                    if (getClass() != obj.getClass()) {
                        return false;
                    }
                    SortedFiles other = (SortedFiles) obj;
                    if (this.fileName == null) {
                        if (other.fileName != null) {
                            return false;
                        }
                    } else if (!this.fileName.equals(other.fileName)) {
                        return false;
                    }
                    return true;
                }

                @Override
                public int compareTo(SortedFiles o) {
                    return this.fileName.compareTo(o.fileName);
                }
            }

            ///////////////////////////////////////////////
            // THEN all CLASS files. Skip the MANIFEST
            ///////////////////////////////////////////////
            List<SortedFiles> sortList2 = new ArrayList<SortedFiles>(fullPaths.size());
            for (int i = 0, n = fullPaths.size(); i < n; i++) {
                String fileName = relativePaths.get(i).replace('\\', '/');
                if (!fileName.equals(manifestName) && fileName.endsWith(".class")) {

                    SortedFiles file = new SortedFiles();
                    file.file = new File(fullPaths.get(i));
                    file.fileName = fileName;
                    sortList2.add(file);

                    // mucking with the backing array so our indexing still works
                    fullPaths.remove(i);
                    relativePaths.remove(i);
                    i--;
                    n--;
                }
            }

            //sort them
            Collections.sort(sortList2);
            for (SortedFiles cf : sortList2) {
                JarEntry jarEntry = new JarEntry(cf.fileName);
                jarEntry.setTime(cf.file.lastModified());
                output.putNextEntry(jarEntry);

                // else just copy the file over
                input = new BufferedInputStream(new FileInputStream(cf.file));
                Sys.copyStream(input, output);
                Sys.close(input);
                output.closeEntry();
            }


            ///////////////////////////////////////////////
            // files other than class files.
            ///////////////////////////////////////////////
            sortList2 = new ArrayList<SortedFiles>(fullPaths.size());
            for (int i = 0, n = fullPaths.size(); i < n; i++) {
                String fileName = relativePaths.get(i).replace('\\', '/');

                SortedFiles file = new SortedFiles();
                file.file = new File(fullPaths.get(i));
                file.fileName = fileName;
                sortList2.add(file);
            }

            // sort them
            Collections.sort(sortList2);
            for (SortedFiles cf : sortList2) {
                //System.err.println('\t' + fullPaths.get(i));

                JarEntry jarEntry = new JarEntry(cf.fileName);
                jarEntry.setTime(cf.file.lastModified());
                output.putNextEntry(jarEntry);

                // else just copy the file over
                input = new BufferedInputStream(new FileInputStream(cf.file));
                Sys.copyStream(input, output);
                Sys.close(input);
                output.closeEntry();
            }


            ///////////////////////////////////////////////
            // NOW we do the EXTRA files.
            // These files will MATCH the path hierarchy in the jar
            ///////////////////////////////////////////////
            if (options.extraPaths != null) {
                sortList2 = new ArrayList<SortedFiles>(options.extraPaths.count());

                Build.log().message("   Adding extras");

                fullPaths = options.extraPaths.getPaths();
                relativePaths = options.extraPaths.getRelativePaths();

                for (int i = 0, n = fullPaths.size(); i < n; i++) {
                    String fileName;
                    fileName = relativePaths.get(i).replace('\\', '/');

                    Build.log().message("\t" + fileName);

                    SortedFiles file = new SortedFiles();
                    file.file = new File(fullPaths.get(i));
                    file.fileName = fileName;
                    sortList2.add(file);
                }

                // sort them
                Collections.sort(sortList2);
                for (SortedFiles cf : sortList2) {

                    JarEntry jarEntry = new JarEntry(cf.fileName);
                    jarEntry.setTime(cf.file.lastModified());
                    output.putNextEntry(jarEntry);

                    // else just copy the file over
                    input = new BufferedInputStream(new FileInputStream(cf.file));
                    Sys.copyStream(input, output);
                    Sys.close(input);
                    output.closeEntry();
                }
            }

            ///////////////////////////////////////////////
            // include the source code if possible
            ///////////////////////////////////////////////
            if (options.sourcePaths != null && !options.sourcePaths.isEmpty()) {
                sortList2 = new ArrayList<SortedFiles>(options.sourcePaths.count());

                Build.log().message("   Adding sources (" + options.sourcePaths.count() + " entries)...");

                fullPaths = options.sourcePaths.getPaths();
                relativePaths = options.sourcePaths.getRelativePaths();

                for (int i = 0, n = fullPaths.size(); i < n; i++) {
                    String fileName = relativePaths.get(i).replace('\\', '/');
//                    System.err.println("\t\t:     " + fileName);

                    SortedFiles file = new SortedFiles();
                    file.file = new File(fullPaths.get(i));
                    file.fileName = fileName;
                    sortList2.add(file);
                }

                // sort them
                Collections.sort(sortList2);
                for (SortedFiles cf : sortList2) {

                    JarEntry jarEntry = new JarEntry(cf.fileName);
                    jarEntry.setTime(cf.file.lastModified());
                    output.putNextEntry(jarEntry);

                    // else just copy the file over
                    input = new BufferedInputStream(new FileInputStream(cf.file));
                    Sys.copyStream(input, output);
                    Sys.close(input);
                    output.closeEntry();
                }
            }

            ///////////////////////////////////////////////
            // now include the license, if possible
            ///////////////////////////////////////////////
            if (options.licenses != null) {
                Build.log().message("   Adding license");
                License.install(output, options.licenses);
            }
        } finally {
            output.finish();
            Sys.close(output);
        }
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(jarOutputStream.toByteArray());

        // now we normalize the JAR.
        ByteArrayOutputStream repacked = Pack200Util.Java.repackJar(byteArrayInputStream);

        byteArrayInputStream = new ByteArrayInputStream(repacked.toByteArray());
        FileOutputStream fileOutputStream = new FileOutputStream(options.outputFile);

        Sys.copyStream(byteArrayInputStream, fileOutputStream);
        Sys.close(fileOutputStream);
    }

    /**
     * Figures out what are going to be directories that should be created in the war.
     */
    private static Set<String> figureOutDirectories(List<String> fullPaths, List<String> relativePaths) {
        Set<String> directories = new HashSet<String>();

        for (int i = 0, n = fullPaths.size(); i < n; i++) {
            String fileName = relativePaths.get(i);
            String pathName = fullPaths.get(i);

            // determine if we have a directory or not.
            if (fileName.indexOf("/") > -1 || fileName.indexOf("\\") > -1) {
                int indexOf = pathName.indexOf(fileName);

                // if our filename is a part of the path (this is when loading classes)
                if (indexOf > -1) {
                    String dir = fileName.replace('\\', '/');

                    // keep the trailing slash! (needed later on)
                    int lastIndex = dir.lastIndexOf('/');
                    dir = dir.substring(0, lastIndex);

                    // now we add ourself, then recursively add our parent dirs
                    // always add back in the slash!
                    directories.add(dir + "/");
                    lastIndex = dir.lastIndexOf('/');


                    while (lastIndex > 0) {
                        dir = dir.substring(0, lastIndex);
                        lastIndex = dir.lastIndexOf('/');
                        // now we add ourself, then recursively add our parent dirs
                        directories.add(dir + "/");
                    }
                }
                // when loading up jars and other resources.
                else {
                    // have to fetch the directory.
                    String dirName = fileName.substring(0, fileName.lastIndexOf("/"));
                    directories.add(dirName + "/"); // set the same in this instance!
                }
            }
        }

        return directories;
    }

    /**
     * Similar to 'jar', however this is for war files instead.
     */
    public static void war(String warFilePath, List<String> fullPaths, List<String> relativePaths) throws FileNotFoundException, IOException {
        // CLEANUP DIRECTORIES
        Set<String> directories = figureOutDirectories(fullPaths, relativePaths);

        // NOW WE ACTUALLY MAKE THE JAR
        FileUtil.mkdir(new File(warFilePath).getParent());
        JarOutputStream output = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(warFilePath)));
        output.setLevel(JAR_COMPRESSION_LEVEL);

        try {
            // quirks & zip standards.
            // - Directory names must end with a slash '/'
            // - All paths must use '/' style slashes, not '\'
            // - JarEntry names should NOT begin with '/'
            BufferedInputStream input = null;

            // FIRST all directories
            for (String dirName : directories) {
                if (!dirName.endsWith("/")) {
                    dirName += "/";
                }

                JarEntry jarEntry = new JarEntry(dirName);
                output.putNextEntry(jarEntry);
                output.closeEntry();
            }

            // regular files
            for (int i = 0, n = fullPaths.size(); i < n; i++) {
                String fileName = relativePaths.get(i).replace('\\', '/');

                File file = new File(fullPaths.get(i));

                JarEntry jarEntry = new JarEntry(fileName);
                jarEntry.setTime(file.lastModified());
                output.putNextEntry(jarEntry);

                input = new BufferedInputStream(new FileInputStream(file));
                Sys.copyStream(input, output);
                Sys.close(input);
                output.closeEntry();
            }
        } finally {
            output.finish();
            Sys.close(output);
        }
    }

    public static void removeArchiveCommentFromJar(String jarName) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(new BufferedOutputStream(byteArrayOutputStream));
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        // cannot use the jarInputStream technique here, since i'm reordering
        // the contents of the jar.
        JarFile jarFile = new JarFile(jarName);

        // MANIFEST ENTRIES MUST BE FIRST
        Enumeration<JarEntry> metaEntries = jarFile.entries();

        while (metaEntries.hasMoreElements()) {
            JarEntry metaEntry = metaEntries.nextElement();
            String name = metaEntry.getName();
            if (name.startsWith(metaInfName) && !metaEntry.isDirectory()) {
                JarUtil.writeZipEntry(metaEntry, jarFile, jarOutputStream);
            } else {
                // since this is already a valid jar, the META-INF data is
                // already first.
                break;
            }
        }

        // now guarantee that directories are NEXT
        Enumeration<JarEntry> directoryEntries = jarFile.entries();
        while (directoryEntries.hasMoreElements()) {
            JarEntry entry = directoryEntries.nextElement();
            if (entry.isDirectory()) {
                JarUtil.writeZipEntry(entry, jarFile, jarOutputStream);
            }
        }

        // now write out the rest of the files to the stream
        Enumeration<JarEntry> allEntries = jarFile.entries();
        while (allEntries.hasMoreElements()) {
            JarEntry entry = allEntries.nextElement();
            if (!entry.isDirectory() && !entry.getName().startsWith(metaInfName)) {
                JarUtil.writeZipEntry(entry, jarFile, jarOutputStream);
            }
        }

        // don't add the archive comment

        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);

        jarFile.close();

        OutputStream outputStream = new FileOutputStream(jarName, false);
        byteArrayOutputStream.writeTo(outputStream);
        Sys.close(outputStream);
    }


    public static long addTimeStampToJar(String jarName) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(new BufferedOutputStream(byteArrayOutputStream));
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        // cannot use the jarInputStream technique here, since i'm reordering
        // the contents of the jar.
        JarFile jarFile = new JarFile(jarName);

        // MANIFEST ENTRIES MUST BE FIRST
        Enumeration<JarEntry> metaEntries = jarFile.entries();

        while (metaEntries.hasMoreElements()) {
            JarEntry metaEntry = metaEntries.nextElement();
            String name = metaEntry.getName();

            if (name.startsWith(metaInfName) && !metaEntry.isDirectory()) {
                JarUtil.writeZipEntry(metaEntry, jarFile, jarOutputStream);
            } else {
                // since this is already a valid jar, the META-INF data is
                // already first.
                break;
            }
        }

        // now add our TIMESTAMP.
        // It will ALWAYS calculate the timestamp from the BUILD SYSTEM, not the
        // LOCAL/REMOTE SYSTEM (which can exist with incorrect/different clocks)
        long timeStamp = System.currentTimeMillis();
        JarEntry jarEntry = new JarEntry(metaInfName + "___" + Long.toString(timeStamp));
        jarOutputStream.putNextEntry(jarEntry);
        jarOutputStream.closeEntry();

        // now guarantee that directories are NEXT
        Enumeration<JarEntry> directoryEntries = jarFile.entries();
        while (directoryEntries.hasMoreElements()) {
            JarEntry entry = directoryEntries.nextElement();
            if (entry.isDirectory()) {
                JarUtil.writeZipEntry(entry, jarFile, jarOutputStream);
            }
        }

        // now write out the rest of the files to the stream
        Enumeration<JarEntry> allEntries = jarFile.entries();
        while (allEntries.hasMoreElements()) {
            JarEntry entry = allEntries.nextElement();
            if (!entry.isDirectory() && !entry.getName().startsWith(metaInfName)) {
                JarUtil.writeZipEntry(entry, jarFile, jarOutputStream);
            }
        }

        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);

        jarFile.close();

        OutputStream outputStream = new FileOutputStream(jarName, false);
        byteArrayOutputStream.writeTo(outputStream);
        Sys.close(outputStream);

        return timeStamp;
    }

    /**
     * Adds args (launcher or VM args) to the ini file.
     * @throws IOException
     */
    public static void addArgsToIniInJar(String jarName, String... args) throws IOException {
        Build.log().message("Modifying config.ini file in jar...");

        for (String arg : args) {
            Build.log().message("\t" + arg);
        }

        // we have to use a JarFile, so we preserve the comments that might already be in the file.
        JarFile origJarFile = new JarFile(jarName);
        JarEntry entry;


        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(new BufferedOutputStream(byteArrayOutputStream));
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        boolean foundConfigFile = false;
        // now write out the rest of the files to the stream

        // THIS DOES NOT MESS WITH THE ORDER OF THE FILES IN THE JAR!
        Enumeration<JarEntry> metaEntries = origJarFile.entries();
        while (metaEntries.hasMoreElements()) {
            entry = metaEntries.nextElement();
            String name = entry.getName();

            if (!name.equals(configFile)) {
                JarUtil.writeZipEntry(entry, origJarFile, jarOutputStream);
            } else {
                foundConfigFile = true;
                addArgsToIniContents(entry, origJarFile.getInputStream(entry), jarOutputStream, args);
            }
        }


        if (!foundConfigFile) {
            addArgsToIniContents(null, jarOutputStream, args);
        }

        origJarFile.close();


        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);

        OutputStream outputStream = new FileOutputStream(jarName, false);
        byteArrayOutputStream.writeTo(outputStream);
        Sys.close(outputStream);
    }


    public static void addArgsToIniFile(String iniFile, String... args) throws IOException {
        InputStream inFile = new BufferedInputStream(new FileInputStream(iniFile));
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();

        addArgsToIniContents(inFile, outStream, args);
        FileOutputStream outFile = new FileOutputStream(iniFile);
        outStream.writeTo(outFile);
        outFile.flush();
        Sys.close(outFile);
    }

    /**
     * Fixes up the ini file inside the jar.
     */
    private static void addArgsToIniContents(InputStream inputStream, OutputStream outputStream, String... args) throws IOException {
        addArgsToIniContents(null, inputStream, outputStream, args);
    }
    /**
     * Fixes up the ini file inside the jar.
     */
    private static void addArgsToIniContents(JarEntry entry, InputStream inputStream, OutputStream outputStream,
                                             String... args) throws IOException {

        ByteArrayOutputStream outputStreamCopy = new ByteArrayOutputStream();
        if (inputStream != null) {
            Sys.copyStream(inputStream, outputStreamCopy);
        }
        ByteArrayInputStream inputStreamCopy = new ByteArrayInputStream(outputStreamCopy.toByteArray());


        List<String> iniFileArgs = new ArrayList<String>(16);

        BufferedReader input = null;
        try {
            input = new BufferedReader(new InputStreamReader(inputStreamCopy));
            //FileReader always assumes default encoding is OK!
            String line = null;
            /*
             * returns the content of a line MINUS the newline.
             * returns null only for the END of the stream.
             * returns an empty String if two newlines appear in a row.
             */
            while (( line = input.readLine()) != null) {
                iniFileArgs.add(line);
            }
        }
        catch (IOException e) { }
        finally {
            Sys.close(input);
        }

        // now we write the args.
        outputStreamCopy = new ByteArrayOutputStream();
        Writer output = null;
        try {
            output = new BufferedWriter(new OutputStreamWriter(outputStreamCopy));
            // FileWriter always assumes default encoding is OK

            // write all of the original args
            for (String arg : iniFileArgs) {
                output.write(arg);
                output.write(OS.LINE_SEPARATOR);
            }

            // write our new args
            for (String arg : args) {
                output.write(arg);
                output.write(OS.LINE_SEPARATOR);
            }
        } catch (IOException e) {
        } finally {
            Sys.close(output);
        }

        inputStreamCopy = new ByteArrayInputStream(outputStreamCopy.toByteArray());

        if (outputStream instanceof JarOutputStream) {
            JarOutputStream jarOutputStream = (JarOutputStream) outputStream;

            JarEntry entry2 = new JarEntry(configFile);
            entry2.setComment(entry.getComment());
            entry2.setExtra(entry.getExtra());
            jarOutputStream.putNextEntry(entry2);

            Sys.copyStream(inputStreamCopy, outputStream);
            jarOutputStream.closeEntry();
            Sys.close(inputStreamCopy);
        } else {
            Sys.copyStream(inputStreamCopy, outputStream);
            outputStream.flush();
            Sys.close(outputStream);
            Sys.close(inputStreamCopy);
        }
    }

    /**
     * Adds the specified files AS REGULAR FILES to the jar.
     *
     * @param filesToAdd
     *            a PAIR of strings. First in pair is SOURCE, second in pair is
     *            DEST
     */
    public static void addFilesToJar(String jarName, BuildOptions options, ExtraDataInterface extraDataWriter, Pack... filesToAdd) throws IOException {
        addFilesToJar(jarName, options, null, extraDataWriter, filesToAdd);
    }


    /**
     * Adds the specified files AS REGULAR FILES to the jar. Will ALSO let us REPLACE files in the jar
     *
     * @param filesToAdd
     *            a PAIR of strings. First in pair is SOURCE, second in pair is DEST
     */
    public static void addFilesToJar(String jarName, BuildOptions properties, EncryptInterface encryption, ExtraDataInterface extraDataWriter,
                                     Pack... filesToAdd) throws IOException {
        PackAction[] actionsToRemove;
        if (properties.compiler.enableDebugSpeedImprovement) {
            actionsToRemove = new PackAction[] {PackAction.Pack, PackAction.Lzma, PackAction.Encrypt};
        } else {
            actionsToRemove = new PackAction[] {PackAction.Encrypt};
        }

        boolean addDebug = properties.compiler.debugEnabled;
        boolean release = properties.compiler.release;

        Build.log().message("Adding files to jar: '" + jarName + "'");

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(new BufferedOutputStream(byteArrayOutputStream));
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        // we have to use a JarFile, so we preserve the comments that might already be in the file.
        JarFile jarFile = new JarFile(jarName);
        JarEntry entry;

        // THIS DOES NOT MESS WITH THE ORDER OF THE FILES IN THE JAR!
        // This will also let us replace a jar with a different pack-action (ie, change something to LGPL, LoadAction, etc)
        Enumeration<JarEntry> metaEntries = jarFile.entries();
        while (metaEntries.hasMoreElements()) {
            entry = metaEntries.nextElement();
            String name = entry.getName();

            boolean canAdd = true;
            for (Pack pack : filesToAdd) {
                String destPath = pack.getDestPath();
                if (name.equals(destPath)) {
                    Build.log().message("   Replacing '" + destPath + "'");
                    canAdd = false;
                    break;
                }
            }
            if (canAdd) {
                JarUtil.writeZipEntry(entry, jarFile, jarOutputStream);
            }
        }

        // now add the files that we want to add.
        for (Pack pack : filesToAdd) {
            if (!release) {
                pack.remove(actionsToRemove);
            }

            String sourcePath = pack.getSourcePath();
            String destPath = pack.getDestPath();

            Build.log().message("  '" + sourcePath + "' -> '" + destPath + "'");

            InputStream inputStream;
            int length = 0;
            long time = 0L;
            entry = new JarEntry(destPath);
            if (sourcePath != null) {
                File fileToAdd = new File(sourcePath);
                time = fileToAdd.lastModified();
                entry.setTime(time);
                inputStream = new FileInputStream(fileToAdd);
                length = (int) fileToAdd.length(); // yea, yea, yea, the length truncates...
            } else {
                inputStream = new ByteArrayInputStream(new byte[0]);
            }

            /////////////
            // now load the entry to the jar
            /////////////

            PackTask task = new PackTask(pack, inputStream);
            task.time = time;
            task.debug = addDebug;
            task.length = length; // have to do this, because of how FileInputStream works.
            task.encryption = encryption;


            if (pack.canDo(PackAction.Extract)) {
                // we do this here, so that the unpack will copy over/duplicate our custom extra data field
                if (extraDataWriter != null) {
                    extraDataWriter.write(entry, null);
                }

                unpackEntry(task, extraDataWriter, jarOutputStream);
            } else {
                packEntry(task);

                if (extraDataWriter != null) {
                    extraDataWriter.write(entry, task);
                }

                jarOutputStream.putNextEntry(entry);
                Sys.copyStream(task.inputStream, jarOutputStream);
                jarOutputStream.closeEntry();
                Sys.close(task.inputStream);
            }
        }

        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);

        OutputStream outputStream = new FileOutputStream(jarName, false);
        byteArrayOutputStream.writeTo(outputStream);
        Sys.close(outputStream);
        jarFile.close();
    }

    /**
     * Repackages the JAR, compressing/etc based on specific rules.
     *
     * Also makes sure to have our custom header (in 'extra data') written for each entry
     *
     * This is how we get the JAR file size down.
     * @return
     */
    public static void packageJar(String jarName, BuildOptions properties,
                                  EncryptInterface encryption, ExtraDataInterface extraDataWriter,
                                  List<String> fileExtensionToHandle,
                                  Repack... specialActions)  throws IOException {

        PackAction[] actionsToRemove;
        if (properties.compiler.enableDebugSpeedImprovement) {
            actionsToRemove = new PackAction[] {PackAction.Pack, PackAction.Lzma, PackAction.Encrypt};
        } else {
            actionsToRemove = new PackAction[] {PackAction.Encrypt};
        }

        boolean release = properties.compiler.release;

        String tempJarName = jarName+".tmp";
        JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(tempJarName, false));
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        JarFile jarFile = new JarFile(jarName);
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            long time = entry.getTime();

            // DO NOT handle manifest dir, subdirs or directories!
            if (JarFile.MANIFEST_NAME.equals(name) || entry.isDirectory() || name.indexOf('/') > -1) {
                // abusing the system -- but by doing this, we will have our extra data copied over
                if (extraDataWriter != null) {
                    extraDataWriter.write(entry, null);
                }
                JarUtil.writeZipEntry(entry, jarFile, jarOutputStream);
            } else {
                // only handle if we match one of our extensions!
                boolean handle = false;
                for (String fileExtension : fileExtensionToHandle) {
                    if (name.endsWith(fileExtension)) {
                        handle = true;
                        break;
                    }
                }

                if (!handle) {
                    // abusing the system -- but by doing this, we will have our extra data copied over
                    if (extraDataWriter != null) {
                        extraDataWriter.write(entry, null);
                    }
                    JarUtil.writeZipEntry(entry, jarFile, jarOutputStream);
                } else {
                    Repack repack = null;

                    for (Repack specialRepack : specialActions) {
                        if (name.equals(specialRepack.getName())) {
                            repack = specialRepack;
                            break;
                        }
                    }

                    // default is all actions.
                    if (repack == null) {
                        repack = new Repack(name, PackAction.Package);
                    }

                    // undo PACK, LZMA, GZIP, and encrypt so debug/testing is faster
                    if (!release) {
                        repack.remove(actionsToRemove);
                    }

                    System.err.print(".");

                    // load the entry into memory
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
                    InputStream is = jarFile.getInputStream(entry);

                    Sys.copyStream(is, baos);
                    Sys.close(is);

                    // read the bytes into a buffer.
                    byte[] entryAsBytes = baos.toByteArray();

                    PackTask task = new PackTask(repack, entryAsBytes);
                    task.time = time;
                    task.debug = properties.compiler.debugEnabled;
                    task.encryption = encryption;

                    if (repack.canDo(PackAction.Extract)) {
                        // this also writes out (and overrides) our custom extra data header
                        unpackEntry(task, extraDataWriter, jarOutputStream);
                    } else {
                        packEntry(task);

                        // now write (single entry) to the outputStream
                        // figure out the ACTION file name extension
                        JarEntry destEntry = new JarEntry(name);
                        destEntry.setTime(entry.getTime());
                        if (extraDataWriter != null) {
                            extraDataWriter.write(destEntry, task);
                        }

                        jarOutputStream.putNextEntry(destEntry);

                        Sys.copyStream(task.inputStream, jarOutputStream);
                        jarOutputStream.flush();
                        jarOutputStream.closeEntry();
                    }
                }
            }
        }

        jarOutputStream.finish();
        Sys.close(jarOutputStream);

        jarFile.close();

        System.err.println(".");

        FileUtil.moveFile(tempJarName, jarName);
    }

    private static void unpackEntry(PackTask task, ExtraDataInterface extraDataWriter, JarOutputStream jarOutputStream) {
        InputStream inputStream = task.inputStream;
        Repack repack = task.pack;

        // sometimes we want to extract the contents of a compressed file to the root of our 'box' file!
        // supports tar, tar.gz, gzip, zip
        String name = repack.getName();

        String extension = repack.getExtension();
        if (extension.endsWith("tar")) {
            TarInputStream newInputStream = null;
            newInputStream = new TarInputStream(inputStream);

            try {
                TarEntry entry;
                while ((entry = newInputStream.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String name2 = entry.getName();

                    // now write (the inside entry) to the outputStream
                    // figure out the ACTION file name extension
                    JarEntry destEntry = new JarEntry(name2);
                    destEntry.setTime(entry.getModTime().getTime());
                    if (extraDataWriter != null) {
                        extraDataWriter.write(destEntry, task);
                    }
                    jarOutputStream.putNextEntry(destEntry);

                    Sys.copyStream(newInputStream, jarOutputStream);
                    jarOutputStream.flush();
                    jarOutputStream.closeEntry();
                }
            } catch (Exception e) {
                System.err.println("Unable to extract contents of tar file!");
            }
        } else {
            if (extension.equals("gz") || extension.equals("gzip")) {
                TarInputStream newInputStream = null;
                GZIPInputStream gzipInputStream = null;
                try {
                    if (name.endsWith("tar.gz") || name.endsWith("tar.gzip")) {
                        // ungzip AND untar
                        gzipInputStream = new GZIPInputStream(inputStream);
                        newInputStream = new TarInputStream(gzipInputStream);

                        TarEntry entry;
                        while ((entry = newInputStream.getNextEntry()) != null) {
                            if (entry.isDirectory()) {
                                continue;
                            }

                            String name2 = entry.getName();

                            // now write (the inside entry) to the outputStream
                            // figure out the ACTION file name extension
                            JarEntry destEntry = new JarEntry(name2);
                            destEntry.setTime(entry.getModTime().getTime());
                            if (extraDataWriter != null) {
                                extraDataWriter.write(destEntry, task);
                            }
                            jarOutputStream.putNextEntry(destEntry);

                            Sys.copyStream(newInputStream, jarOutputStream);
                            jarOutputStream.flush();
                            jarOutputStream.closeEntry();
                        }
                    } else {
                        // ONLY ungzip (gzip only works on ONE file)

                        // this is a regular file (such as a txt file, etc)
                        // now write (the inside entry) to the outputStream
                        // figure out the ACTION file name extension
                        JarEntry destEntry = new JarEntry(name);
                        destEntry.setTime(task.time);  // set the time to whatever the compressed entry time was
                        if (extraDataWriter != null) {
                            extraDataWriter.write(destEntry, task);
                        }
                        jarOutputStream.putNextEntry(destEntry);

                        gzipInputStream = new GZIPInputStream(inputStream);
                        byte[] buffer = new byte[8192];
                        int read = 0;
                        while ((read = gzipInputStream.read(buffer)) > 0) {
                            jarOutputStream.write(buffer, 0, read);
                        }
                        jarOutputStream.flush();
                        jarOutputStream.closeEntry();
                    }
                } catch (Exception e) {
                    System.err.println("Unable to extract contents of compressed file!");
                }
                Sys.close(newInputStream);
                Sys.close(gzipInputStream);
            } else {
                // input stream can be fileInputStream (if it was a file)
                // or a bytearrayinput stream if it was a stream from another file
                boolean isZip = false;
                if (inputStream instanceof FileInputStream) {
                    File file = new File(name);
                    isZip = isZipFile(file);
                } else {
                    ByteArrayInputStream s = (ByteArrayInputStream) inputStream;
                    isZip = isZipStream(s);
                }

                // can be zip (ie: jar)
                if (isZip) {
                    ZipInputStream zipInputStream = null;
                    try {
                        zipInputStream = new ZipInputStream(inputStream);

                        ZipEntry entry;
                        while ((entry = zipInputStream.getNextEntry()) != null) {
                            // create a new entry to avoid ZipException: invalid entry compressed size
                            // we want to COPY this over. hashes should remain the same between builds!
                            if (extraDataWriter != null) {
                                extraDataWriter.write(entry, task);
                            }
                            writeZipEntry(entry, zipInputStream, jarOutputStream);
                        }
                    } catch (Exception e) {
                        System.err.println("Unable to extract contents of compressed file!");
                    }
                    Sys.close(zipInputStream);
                } else {
                    System.err.println("Unable to extract contents of compressed file!");
                }
            }
        }
    }

    private static void packEntry(PackTask task) throws IOException {
        InputStream inputStream = task.inputStream;
        int length = task.length;
        Repack repack = task.pack;

        // now handle pack/compress/encrypt
        if (Pack200Util.canPack200(repack, task.inputStream)) {
            // Create the Packer object
            ByteArrayOutputStream outputPackStream = Pack200Util.Java.pack200(inputStream, task.debug);

            // convert the output stream to an input stream
            inputStream = new ByteArrayInputStream(outputPackStream.toByteArray());
            length = inputStream.available();
        }

        // we RELY on the the jar ALREADY being NORMALIZED (PACK+UNPACK). pack200 -repack DOES NOT WORK! You must EXPLICITY
        // use the programmatic safePack200 and safeUnpack200 so the jar will be consistent between pack/unpack cycles.
        if (repack.canDo(PackAction.LoadLibray)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            Sys.copyStream(inputStream, baos);

            // we make it NOT pack, and since we ARE NOT modifying the input stream, it's safe to read it directly
            byte[] unpackBuffer = baos.toByteArray();
            int unpackLength = unpackBuffer.length;
            SHA512Digest digest = new SHA512Digest();

            // now run the hash on it!
            byte[] hashBytes = new byte[digest.getDigestSize()];

            digest.update(unpackBuffer, 0, unpackLength);
            digest.doFinal(hashBytes, 0);

            task.extraData = hashBytes;

            // since we can only read the input stream once, make sure to make it again.
            inputStream = new ByteArrayInputStream(unpackBuffer);
        }

        if (repack.canDo(PackAction.Lzma)) {
            ByteArrayOutputStream packedOutputStream = new ByteArrayOutputStream(length); // will be size or smaller.
            LZMA.encode(length, inputStream, packedOutputStream);
            Sys.close(inputStream);

            // convert the output stream to an input stream
            inputStream = new ByteArrayInputStream(packedOutputStream.toByteArray());
            length = inputStream.available();
        }

        // we cannot do BOTH encrypt + LGPL. They are mutually exclusive.
        // LGPL will also not be hashed in the signature generation
        if (repack.canDo(PackAction.Encrypt) && !repack.canDo(PackAction.LGPL)) {
            if (task.encryption != null) {
                ByteArrayOutputStream encryptedOutputStream = task.encryption.encrypt(inputStream, length);

                // convert the output stream to an input stream
                inputStream = new ByteArrayInputStream(encryptedOutputStream.toByteArray());
                length = inputStream.available();
            } else {
                throw new RuntimeException("** Unable to encrypt data when AES information is null!!");
            }
        }

        task.inputStream = inputStream;
    }

    /**
     * Merge the specified files into the primaryFile
     *
     * @param primaryFile This is the file that will contain all of the other files.
     * @param files Array of files (zips/jars) to be added into the primary file
     * @throws IOException
     * @throws FileNotFoundException
     */
    public static void merge(File primaryFile, File... files) throws FileNotFoundException, IOException {
        String[] fileNames = new String[files.length];

        for (int i=0; i<files.length; i++) {
            fileNames[i] = files[i].getAbsolutePath();
        }

        merge(primaryFile.getAbsolutePath(), fileNames);
    }

    /**
     * Merge the specified files into the primaryFile
     *
     * @param primaryFile This is the file that will contain all of the other files.
     * @param files Array of files (zips/jars) to be added into the primary file
     * @throws IOException
     * @throws FileNotFoundException
     */
    public static void merge(String primaryFile, String... files) throws FileNotFoundException, IOException {
        Build.log().message("Merging files into single jar/zip: '" + primaryFile + "'");

        // write everything to staging dir, then jar it up.
        String tempDirectory = FileUtil.tempDirectory("mergeTemp");
        File mergeLocation = new File(tempDirectory);

        FileUtil.unzipJar(primaryFile, mergeLocation.getAbsolutePath(), true);

        for (String fileName : files) {
            File file = new File(fileName);
            // is this a zip?
            if (FileUtil.isZipFile(file)) {
                FileUtil.unzipJar(file, mergeLocation, false);
            } else {
                // just copy it over
                String relativeToDir = FileUtil.getChildRelativeToDir(file, "src");
                FileUtil.copyFile(file, new File(mergeLocation, relativeToDir));
            }
        }

        JarOptions options = new JarOptions();
        options.outputFile = primaryFile;
        options.inputPaths = new Paths(tempDirectory);
        options.mainClass = null;
        options.otherManifestAttributes = null;
        options.classpath = null;

        JarUtil.jar(options);


        // cleanup
        FileUtil.delete(mergeLocation);
    }
}

