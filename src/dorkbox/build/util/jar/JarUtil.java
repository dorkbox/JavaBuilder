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

import com.esotericsoftware.wildcard.Paths;
import com.ice.tar.TarEntry;
import com.ice.tar.TarInputStream;
import dorkbox.BuildOptions;
import dorkbox.Builder;
import dorkbox.build.util.BuildLog;
import dorkbox.license.License;
import dorkbox.util.*;
import org.bouncycastle.crypto.digests.SHA512Digest;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.Map.Entry;
import java.util.jar.*;
import java.util.zip.*;

@SuppressWarnings("unused")
public
class JarUtil {
    public static int JAR_COMPRESSION_LEVEL = 9;

    public static byte[] ZIP_HEADER = {(byte) 80, (byte) 75, (byte) 3, (byte) 4};  // PK34

    public static final String metaInfName = "META-INF/";
    public static final String configFile = "config.ini";

    /**
     * @return true if the file is a zip/jar file
     */
    @SuppressWarnings("Duplicates")
    public static
    boolean isZipFile(File file) {
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
    @SuppressWarnings({"ForLoopReplaceableByForEach", "Duplicates"})
    public static
    boolean isZipStream(ByteArrayInputStream input) {
        boolean isZip = true;
        int length = ZIP_HEADER.length;

        try {
            input.mark(length + 1);

            for (int i = 0; i < length; i++) {
                //noinspection NumericCastThatLosesPrecision
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
    public static
    Manifest getManifestFile(JarFile jarFile) throws IOException {
        JarEntry je = jarFile.getJarEntry(JarFile.MANIFEST_NAME);

        // verify that it really exists.
        if (je != null) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                je = jarEntries.nextElement();
                if (JarFile.MANIFEST_NAME.equals(je.getName())) {
                    break;
                }
                else {
                    je = null;
                }
            }

            if (je != null) {
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
        else {
            return null;
        }
    }

    /**
     * a helper function that can take entries from one jar file and write it to
     * another jar stream
     * <p/>
     * Will close the output stream automatically.
     */
    public static
    void writeZipEntry(ZipEntry entry, ZipFile zipInputFile, ZipOutputStream zipOutputStream) throws IOException {
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
     * <p/>
     * Does NOT close any streams!
     */
    public static
    void writeZipEntry(ZipEntry entry, ZipInputStream zipInputStream, ZipOutputStream zipOutputStream) throws IOException {
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


    public static
    String updateDigest(InputStream inputStream, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[2048];
        int read;
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

    public static
    ByteArrayOutputStream createNewJar(JarFile jar, String name, byte[] manifestBytes, byte[] signatureFileManifestBytes,
                                       byte[] signatureBlockBytes) throws IOException {

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

            if (entryName.startsWith(metaInfName) && !(JarFile.MANIFEST_NAME.equalsIgnoreCase(entryName) ||
                                                       signatureFileName.equalsIgnoreCase(entryName) || signatureBlockName.equalsIgnoreCase(
                            entryName))) {

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
    public static
    InputStream removeManifestCommentsAndFiles(String fileName, InputStream inputStream, String[] pathToRemove, String[] pathToKeep,
                                               Set<String> stripped) throws IOException {
        // shortcut out -- nothing to do
        if (pathToRemove == null || pathToRemove.length == 0) {
            return inputStream;
        }

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
        return new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
    }

    /**
     * This will also install the specified licenses, and for JARs, this will ALSO normalize (pack+unpack) the jar
     * <p/>
     * Note about JarOutputStream:
     * The JAR_MAGIC "0xCAFE" in the extra field data of the first JAR entry from our JarOutputStream implementation is
     * not required by JAR specification. It's an "internal implementation detail" to support "executable" jar on Solaris
     * platform. see#4138619. It would be incorrect to reject a JAR file that does not have this extra field data, from
     * specification point of view.
     * <p/>
     * (basically, if you use a JarOutputStream, it adds in extra crap we don't want)
     */
    public static
    void jar(JarOptions options) throws IOException {
        zip(options, true);
    }

    /**
     * Creates a zip file, similar to how jar() works, only minus jar-specific stuff (manifest, etc)
     */
    public static
    void zip(JarOptions options) throws IOException {
        zip(options, false);
    }


    /**
     * This will also install the specified licenses, and for JARs, this will ALSO normalize (pack+unpack) the jar
     * <p/>
     * Note about JarOutputStream:
     * The JAR_MAGIC "0xCAFE" in the extra field data of the first JAR entry from our JarOutputStream implementation is
     * not required by JAR specification. It's an "internal implementation detail" to support "executable" jar on Solaris
     * platform. see#4138619. It would be incorrect to reject a JAR file that does not have this extra field data, from
     * specification point of view.
     * <p/>
     * (basically, if you use a JarOutputStream, it adds in extra crap we don't want)
     */
    private static
    void zip(JarOptions options, boolean makeJar) throws IOException {
        if (options.outputFile == null) {
            throw new IllegalArgumentException("jarFile cannot be null.");
        }
        if (makeJar && options.inputPaths == null) {
            throw new IllegalArgumentException("inputPaths cannot be null.");
        }

        List<String> fullPaths = null;
        List<String> relativePaths = null;
        Manifest manifest = null;


        if (options.inputPaths != null) {
            fullPaths = options.inputPaths.getPaths();
            relativePaths = options.inputPaths.getRelativePaths();

            if (makeJar && fullPaths.isEmpty()) {
                System.err.println("No files to JAR!");
                return;
            }
        }

        if (makeJar && options.mainClass != null) {
            manifest = new Manifest();
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
        }



        int totalEntries = 0;
        if (options.inputPaths != null) {
            totalEntries += options.inputPaths.count();
        }

        if (options.extraPaths != null) {
            totalEntries += options.extraPaths.count();
        }

        if (options.sourcePaths != null) {
            totalEntries += options.sourcePaths.count();
        }

        BuildLog.println();
        //noinspection AccessStaticViaInstance
        BuildLog.title("Creating JAR").println(totalEntries + " total entries", options.outputFile.getAbsolutePath());


        // NOW WE ACTUALLY MAKE THE JAR
        FileUtil.mkdir(options.outputFile.getParentFile());
        ByteArrayOutputStream zipOutputStream = new ByteArrayOutputStream();
        ZipOutputStream output;

        if (makeJar) {
            output = new JarOutputStream(zipOutputStream);
        }
        else {
            output = new ZipOutputStream(zipOutputStream);
        }

        output.setLevel(JAR_COMPRESSION_LEVEL);

        try {
            // quirks & zip standards.
            // - Directory names must end with a slash '/'
            // - All paths must use '/' style slashes, not '\'
            // - JarEntry names should NOT begin with '/'


            ///////////////////////////////////////////////
            // ONLY IN JARS
            // MANIFEST FIRST! There is only the manifest, as we are creating the jar from scratch.
            ///////////////////////////////////////////////
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                attributes.putValue("Build-Date", new Date(Builder.buildDate).toString() + " (" + Long.toString(Builder.buildDate) + ")");

                JarEntry jarEntry = new JarEntry(JarFile.MANIFEST_NAME);
                jarEntry.setTime(Builder.buildDate);
                output.putNextEntry(jarEntry);

                manifest.write(output);
                output.closeEntry();
            }
            // there won't be any OTHER manifest files, since we haven't signed
            // the jar yet...



            ///////////////////////////////////////////////
            // NEXT all directories
            ///////////////////////////////////////////////
            {
                // CLEANUP DIRECTORIES
                Set<String> directories = new HashSet<String>();
                if (options.inputPaths != null) {
                    directories = figureOutDirectories(fullPaths, relativePaths);
                }

                Set<String> sortedDirectories = new HashSet<String>(directories.size() * 3);
                for (String dirName : directories) {
                    if (!dirName.endsWith("/")) {
                        dirName += "/";
                    }

                    sortedDirectories.add(dirName);
                }

                // ALSO handle "extra" directories
                if (options.extraPaths != null) {
                    List<SortedFiles> sortList = new ArrayList<SortedFiles>(options.extraPaths.count());

                    List<String> extraFullPaths = options.extraPaths.getPaths();
                    List<String> extraRelativePaths = options.extraPaths.getRelativePaths();

                    Set<String> extraDirectories = figureOutDirectories(extraFullPaths, extraRelativePaths);

                    for (String dirName : extraDirectories) {
                        if (!dirName.endsWith("/")) {
                            dirName += "/";
                        }

                        sortedDirectories.add(dirName);
                    }
                }

                // ALSO handle "source" directories
                if (options.sourcePaths != null && !options.sourcePaths.isEmpty()) {
                    List<SortedFiles> sortList = new ArrayList<SortedFiles>(options.sourcePaths.count());

                    List<String> sourceFullPaths = options.sourcePaths.getPaths();
                    List<String> sourceRelativePaths = options.sourcePaths.getRelativePaths();

                    Set<String> sourceDirectories = figureOutDirectories(sourceFullPaths, sourceRelativePaths);

                    for (String dirName : sourceDirectories) {
                        if (!dirName.endsWith("/")) {
                            dirName += "/";
                        }

                        sortedDirectories.add(dirName);
                    }
                }

                ArrayList<String> strings = new ArrayList<String>(sortedDirectories);

                // sort them
                Collections.sort(strings);

                for (String dirName : strings) {
                    JarEntry jarEntry = new JarEntry(dirName);
                    jarEntry.setTime(Builder.buildDate); // hidden when view a jar, but it's always there
                    output.putNextEntry(jarEntry);
                    output.closeEntry();
                }
            }


            if (options.inputPaths != null) {
                fullPaths = options.inputPaths.getPaths();
                relativePaths = options.inputPaths.getRelativePaths();

                List<SortedFiles> sortedClassFiles = new ArrayList<SortedFiles>(fullPaths.size());
                List<SortedFiles> sortedOtherFiles = new ArrayList<SortedFiles>(fullPaths.size());

                for (int i = 0, n = fullPaths.size(); i < n; i++) {
                    String fileName = relativePaths.get(i).replace('\\', '/');

                    SortedFiles file = new SortedFiles();
                    file.file = new File(fullPaths.get(i));
                    file.fileName = fileName;

                    ///////////////////////////////////////////////
                    // THEN all CLASS files.
                    ///////////////////////////////////////////////
                    if (fileName.endsWith(".class")) {
                        sortedClassFiles.add(file);
                    }

                    ///////////////////////////////////////////////
                    // files other than class files.
                    ///////////////////////////////////////////////
                    else {
                        sortedOtherFiles.add(file);
                    }
                }

                InputStream input;

                //sort them
                Collections.sort(sortedClassFiles);
                addFilesToJar(options, makeJar, output, sortedClassFiles);

                // sort them
                Collections.sort(sortedOtherFiles);
                for (SortedFiles cf : sortedOtherFiles) {
                    File file = cf.file;
                    if (file.isDirectory()) {
                        continue;
                    }

                    //System.err.println('\t' + fullPaths.get(i));
                    ZipEntry jarEntry;
                    if (makeJar) {
                        jarEntry = new JarEntry(cf.fileName);
                    }
                    else {
                        jarEntry = new ZipEntry(cf.fileName);
                    }

                    if (options.overrideDate > -1) {
                        jarEntry.setTime(options.overrideDate);
                    }
                    else {
                        jarEntry.setTime(file.lastModified());
                    }
                    output.putNextEntry(jarEntry);

                    // else just copy the file over
                    input = new BufferedInputStream(new FileInputStream(file));

                    if (JarUtil.isZipFile(cf.file)) {
                        //noinspection NumericCastThatLosesPrecision
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream((int) file.length());
                        Sys.copyStream(input, outputStream);
                        Sys.close(input);

                        // will not do anything if there was a manifest in the target jar
                        if (makeJar) {
                            input = new ByteArrayInputStream(outputStream.toByteArray());

                            JarInputStream jarInputStream = new JarInputStream(input, false);
                            Manifest targetManifest = jarInputStream.getManifest();

                            // DON'T touch if there is a manifest!
                            if (targetManifest == null) {
                                outputStream = JarUtil.removeLicenseInfo(jarInputStream);
                                input = new ByteArrayInputStream(outputStream.toByteArray());
                            }
                            else {
                                input.reset();
                            }
                        }
                    }

                    Sys.copyStream(input, output);
                    Sys.close(input);
                    output.closeEntry();
                }
            }



            ///////////////////////////////////////////////
            // NOW we do the EXTRA files.
            // These files will MATCH the path hierarchy in the jar
            ///////////////////////////////////////////////
            if (options.extraPaths != null) {
                List<SortedFiles> sortList = new ArrayList<SortedFiles>(options.extraPaths.count());

                if (!sortList.isEmpty()) {
                    BuildLog.println("\tAdding extras");

                    fullPaths = options.extraPaths.getPaths();
                    relativePaths = options.extraPaths.getRelativePaths();

                    for (int i = 0, n = fullPaths.size(); i < n; i++) {
                        String fileName;
                        fileName = relativePaths.get(i).replace('\\', '/');

                        BuildLog.println("\t  " + fileName);

                        SortedFiles file = new SortedFiles();
                        file.file = new File(fullPaths.get(i));
                        file.fileName = fileName;
                        sortList.add(file);
                    }

                    InputStream input;

                    // sort them
                    Collections.sort(sortList);
                    addFilesToJar(options, makeJar, output, sortList);
                }
            }

            ///////////////////////////////////////////////
            // include the source code if possible
            ///////////////////////////////////////////////
            if (options.sourcePaths != null && !options.sourcePaths.isEmpty()) {
                List<SortedFiles> sortList = new ArrayList<SortedFiles>(options.sourcePaths.count());

                BuildLog.println("\tAdding sources (" + options.sourcePaths.count() + " entries)...");

                fullPaths = options.sourcePaths.getPaths();
                relativePaths = options.sourcePaths.getRelativePaths();

                for (int i = 0, n = fullPaths.size(); i < n; i++) {
                    String fileName = relativePaths.get(i).replace('\\', '/');
//                    System.err.println("\t\t:     " + fileName);

                    SortedFiles file = new SortedFiles();
                    file.file = new File(fullPaths.get(i));
                    file.fileName = fileName;
                    sortList.add(file);
                }

                InputStream input;

                // sort them
                Collections.sort(sortList);
                addFilesToJar(options, makeJar, output, sortList);
            }


            ///////////////////////////////////////////////
            // now include the license, if possible
            ///////////////////////////////////////////////
            if (options.licenses != null) {
                BuildLog.println("\tAdding license");
                License.install(output, options.licenses, options.overrideDate);
            }
        } finally {
            output.finish();
            Sys.close(output);
        }
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(zipOutputStream.toByteArray());

        if (makeJar) {
            // now we normalize the JAR.
            ByteArrayOutputStream repacked = Pack200Util.Java.repackJar(byteArrayInputStream);
            byteArrayInputStream = new ByteArrayInputStream(repacked.toByteArray());
        }

        FileOutputStream fileOutputStream = new FileOutputStream(options.outputFile);

        Sys.copyStream(byteArrayInputStream, fileOutputStream);
        Sys.close(fileOutputStream);
    }

    private static
    void addFilesToJar(final JarOptions options,
                       final boolean makeJar,
                       final ZipOutputStream output,
                       final List<SortedFiles> sortList) throws IOException {
        InputStream input;
        for (SortedFiles cf : sortList) {
            File file = cf.file;
            if (file.isDirectory()) {
                continue;
            }

            ZipEntry jarEntry;
            if (makeJar) {
                jarEntry = new JarEntry(cf.fileName);
            }
            else {
                jarEntry = new ZipEntry(cf.fileName);
            }

            if (options.overrideDate > -1) {
                jarEntry.setTime(options.overrideDate);
            }
            else {
                jarEntry.setTime(file.lastModified());
            }

            output.putNextEntry(jarEntry);

            // else just copy the file over
            input = new BufferedInputStream(new FileInputStream(file));
            Sys.copyStream(input, output);
            Sys.close(input);
            output.closeEntry();
        }
    }

    /**
     * Removes the license information in a jar input stream. (IE: LICENSE, LICENSE.MIT, license.md, etc)
     * <p/>
     * Be CAREFUL if there is a manifest present, as THIS DOES NOT COPY IT OVER.
     */
    public static
    ByteArrayOutputStream removeLicenseInfo(JarInputStream jarInputStream) throws IOException {
        // by default, this will not have access to the manifest! (CHECK BEFORE CALLING THIS, if you want to remove the manifest!)
        // we will ALSO lose entry comments!

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(byteArrayOutputStream);
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        JarEntry entry;
        while ((entry = jarInputStream.getNextJarEntry()) != null) {
            String name = entry.getName();

            String lowerCase = name.toLowerCase(Locale.US);
            if (lowerCase.startsWith("license")) {
                continue;
            }

            // create a new entry to avoid ZipException: invalid entry compressed size
            // we want to COPY this over. hashes should remain the same between builds!
            writeZipEntry(entry, jarInputStream, jarOutputStream);
        }


        // finish the stream that we have been writing to
        jarOutputStream.finish();
        Sys.close(jarOutputStream);
        Sys.close(jarInputStream);

        return byteArrayOutputStream;
    }

    /**
     * Figures out what are going to be directories that should be created in the war.
     */
    private static
    Set<String> figureOutDirectories(List<String> fullPaths, List<String> relativePaths) {
        Set<String> directories = new HashSet<String>();

        for (int i = 0, n = fullPaths.size(); i < n; i++) {
            String fileName = relativePaths.get(i);
            String pathName = fullPaths.get(i);

            // determine if we have a directory or not.
            if (fileName.contains("/") || fileName.contains("\\")) {
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
    public static
    void war(String warFilePath, List<String> fullPaths, List<String> relativePaths) throws IOException {
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
            BufferedInputStream input;

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

    public static
    void removeArchiveCommentFromJar(String jarName) throws IOException {
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
            }
            else {
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

    /**
     * Also sets the time to the build time for all META-INF files!
     */
    public static
    long addTimeStampToJar(String jarName) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(new BufferedOutputStream(byteArrayOutputStream));
        jarOutputStream.setLevel(JAR_COMPRESSION_LEVEL);

        // cannot use the jarInputStream technique here, since i'm reordering
        // the contents of the jar.
        JarFile jarFile = new JarFile(jarName);

        // MANIFEST MUST BE FIRST
        Manifest manifest = jarFile.getManifest();
        JarEntry jarEntry = new JarEntry(JarFile.MANIFEST_NAME);
        jarEntry.setTime(Builder.buildDate);
        jarOutputStream.putNextEntry(jarEntry);
        manifest.write(jarOutputStream);
        jarOutputStream.closeEntry();


        // META-INF FILES ARE NEXT
        Enumeration<JarEntry> metaEntries = jarFile.entries();
        while (metaEntries.hasMoreElements()) {
            JarEntry metaEntry = metaEntries.nextElement();
            String name = metaEntry.getName();
            if (name.equals(JarFile.MANIFEST_NAME)) {
                continue;
            }

            if (name.startsWith(metaInfName) && !metaEntry.isDirectory()) {
                metaEntry.setTime(Builder.buildDate);
                JarUtil.writeZipEntry(metaEntry, jarFile, jarOutputStream);
            }
            else {
                // since this is already a valid jar, the META-INF data is
                // already first.
                break;
            }
        }

        // now add our TIMESTAMP.
        // It will ALWAYS calculate the timestamp from the BUILD SYSTEM, not the
        // LOCAL/REMOTE SYSTEM (which can exist with incorrect/different clocks)
        long timeStamp = Builder.buildDate;
        jarEntry = new JarEntry(metaInfName + "___" + Long.toString(timeStamp));
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
     *
     * @throws IOException
     */
    public static
    void addArgsToIniInJar(String jarName, String... args) throws IOException {
        BuildLog.println("Modifying config.ini file in jar...");

        for (String arg : args) {
            BuildLog.println("\t" + arg);
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
            }
            else {
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


    public static
    void addArgsToIniFile(String iniFile, String... args) throws IOException {
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
    private static
    void addArgsToIniContents(InputStream inputStream, OutputStream outputStream, String... args) throws IOException {
        addArgsToIniContents(null, inputStream, outputStream, args);
    }

    /**
     * Fixes up the ini file inside the jar.
     */
    private static
    void addArgsToIniContents(JarEntry entry, InputStream inputStream, OutputStream outputStream, String... args) throws IOException {

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
            String line;
            /*
             * returns the content of a line MINUS the newline.
             * returns null only for the END of the stream.
             * returns an empty String if two newlines appear in a row.
             */
            while ((line = input.readLine()) != null) {
                iniFileArgs.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
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

            // make sure there is a new line at the end of the argument (so it's easier to read)
            output.write(OS.LINE_SEPARATOR);
        } catch (IOException e) {
            e.printStackTrace();
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
        }
        else {
            Sys.copyStream(inputStreamCopy, outputStream);
            outputStream.flush();
            Sys.close(outputStream);
            Sys.close(inputStreamCopy);
        }
    }

    /**
     * Adds the specified files AS REGULAR FILES to the jar.
     *
     * @param filesToAdd a PAIR of strings. First in pair is SOURCE, second in pair is
     *                   DEST
     */
    public static
    void addFilesToJar(String jarName, BuildOptions options, ExtraDataInterface extraDataWriter, Pack... filesToAdd) throws IOException {
        addFilesToJar(jarName, options, null, extraDataWriter, filesToAdd);
    }


    /**
     * Adds the specified files AS REGULAR FILES to the jar. Will ALSO let us REPLACE files in the jar
     *
     * @param filesToAdd a PAIR of strings. First in pair is SOURCE, second in pair is DEST
     */
    public static
    void addFilesToJar(String jarName, BuildOptions properties, EncryptInterface encryption, ExtraDataInterface extraDataWriter,
                       Pack... filesToAdd) throws IOException {
        PackAction[] actionsToRemove;
        if (properties.compiler.enableDebugSpeedImprovement) {
            actionsToRemove = new PackAction[] {PackAction.Pack, PackAction.Lzma, PackAction.Encrypt};
        }
        else {
            actionsToRemove = new PackAction[] {PackAction.Encrypt};
        }

        boolean addDebug = properties.compiler.debugEnabled;
        boolean release = properties.compiler.release;

        BuildLog.println("Adding files to jar: '" + jarName + "'");

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
                    BuildLog.println("  Replacing '" + destPath + "'");
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

            String sourcePath = FileUtil.normalizeAsFile(pack.getSourcePath());
            String destPath = pack.getDestPath();


            BuildLog.println("  ╭─ " + sourcePath, "╰───> " + destPath);

            InputStream inputStream;
            int length = 0;
            long time = 0L;
            entry = new JarEntry(destPath);
            if (sourcePath != null) {
                File fileToAdd = new File(sourcePath);
                time = fileToAdd.lastModified();
                entry.setTime(time);
                inputStream = new FileInputStream(fileToAdd);
                //noinspection NumericCastThatLosesPrecision
                length = (int) fileToAdd.length(); // yea, yea, yea, the length truncates...
            }
            else {
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
            }
            else {
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
     * <p/>
     * Also makes sure to have our custom header (in 'extra data') written for each entry
     * <p/>
     * This is how we get the JAR file size down.
     */
    public static
    void packageJar(String jarName, BuildOptions properties, EncryptInterface encryption, ExtraDataInterface extraDataWriter,
                    List<String> fileExtensionToHandle, Repack... specialActions) throws IOException {

        PackAction[] actionsToRemove;
        if (properties.compiler.enableDebugSpeedImprovement) {
            actionsToRemove = new PackAction[] {PackAction.Pack, PackAction.Lzma, PackAction.Encrypt};
        }
        else {
            actionsToRemove = new PackAction[] {PackAction.Encrypt};
        }

        boolean release = properties.compiler.release;

        String tempJarName = jarName + ".tmp";
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
            }
            else {
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
                }
                else {
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

                    BuildLog.print(".");

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
                    }
                    else {
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

        BuildLog.println(".");

        Builder.moveFile(tempJarName, jarName);
    }

    @SuppressWarnings("Duplicates")
    private static
    void unpackEntry(PackTask task, ExtraDataInterface extraDataWriter, JarOutputStream jarOutputStream) {
        InputStream inputStream = task.inputStream;
        Repack repack = task.pack;

        // sometimes we want to extract the contents of a compressed file to the root of our 'box' file!
        // supports tar, tar.gz, gzip, zip
        String name = repack.getName();

        String extension = repack.getExtension();
        if (extension.endsWith("tar")) {
            TarInputStream newInputStream;
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
        }
        else {
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
                    }
                    else {
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
                        int read;
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
            }
            else {
                // input stream can be fileInputStream (if it was a file)
                // or a bytearrayinput stream if it was a stream from another file
                boolean isZip;
                if (inputStream instanceof FileInputStream) {
                    File file = new File(name);
                    isZip = isZipFile(file);
                }
                else {
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
                }
                else {
                    System.err.println("Unable to extract contents of compressed file!");
                }
            }
        }
    }

    private static
    void packEntry(PackTask task) throws IOException {
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
                // not used because it's the last statement. Keeping just for sanity in case more statements are added
                //noinspection UnusedAssignment
                length = inputStream.available();
            }
            else {
                throw new RuntimeException("** Unable to encrypt data when AES information is null!!");
            }
        }

        task.inputStream = inputStream;
    }

    /**
     * Merge the specified files into the primaryFile
     *
     * @param primaryFile This is the file that will contain all of the other files.
     * @param files       Array of files (zips/jars) to be added into the primary file
     */
    public static
    void merge(File primaryFile, File... files) throws IOException {
        String[] fileNames = new String[files.length];

        for (int i = 0; i < files.length; i++) {
            fileNames[i] = files[i].getAbsolutePath();
        }

        merge(primaryFile.getAbsoluteFile(), fileNames);
    }

    /**
     * Merge the specified files into the primaryFile
     *
     * @param primaryFile This is the file that will contain all of the other files.
     * @param files       Array of files (zips/jars) to be added into the primary file
     */
    public static
    void merge(File primaryFile, String... files) throws IOException {
        BuildLog.println("Merging files into single jar/zip: '" + primaryFile + "'");

        // write everything to staging dir, then jar it up.
        String tempDirectory = FileUtil.tempDirectory("mergeTemp");
        File mergeLocation = new File(tempDirectory);

        FileUtil.unzipJar(primaryFile, mergeLocation.getAbsoluteFile(), true);

        for (String fileName : files) {
            File file = new File(fileName);
            // is this a zip?
            if (FileUtil.isZipFile(file)) {
                FileUtil.unzipJar(file, mergeLocation, false);
            }
            else {
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
