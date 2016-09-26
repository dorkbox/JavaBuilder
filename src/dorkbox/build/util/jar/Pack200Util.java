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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.jar.Pack200.Packer;
import java.util.jar.Pack200.Unpacker;

import dorkbox.util.IO;
import dorkbox.util.OS;
import dorkbox.util.Sys;

// OBSERVATION for JAR normalization
// java-pack/unpack and command-line pack/unpack produce DIFFERENT results.
// java-pack != CL-pack
// java-unpack != CL-unpack
// java-pack + java-unpack != java-pack + CL-unpack
//
// java-pack + CL-unpack = java-pack + CL-unpack
// java-pack + java+unpack + CL-pack + CL-unpack = java-pack + CL-unpack


@SuppressWarnings({"unused", "WeakerAccess"})
public class Pack200Util {
    private static String javaBinLocation;

    public static byte[] PACK200_HEADER = {(byte) -54, (byte) -2, (byte) -48, (byte) 13};

    public static final Map<String, String> packOptions = new HashMap<String, String>(3);
    static {
        // have to make sure to use the "unpack200" associated with the java binary that is running.
        Map<String, String> systemProperties = java.lang.management.ManagementFactory.getRuntimeMXBean().getSystemProperties();
        javaBinLocation = systemProperties.get("java.home") + File.separatorChar + "bin";

        // from http://stackoverflow.com/questions/3312401/how-to-make-an-ant-task-to-sign-and-pack200-all-my-jar-files
        // these are the REQUIRED OPTIONS in order to make BOUNCYCASTLE jar verification PASS.
        //   The HASH on the file will be different than the ORIGINAL hash. The "unpacked" hash will remain constant between pack/unpack cycles

        packOptions.put(Packer.MODIFICATION_TIME, Packer.KEEP);

        // keep whatever sort of jar compression was originally used
        packOptions.put(Packer.DEFLATE_HINT, Packer.KEEP);

        // use largest-possible archive segments (>10% better compression).
        packOptions.put(Packer.SEGMENT_LIMIT, "-1");
    }


    /**
     * @return true if the file is a pack200 file
     */
    public static boolean isPack200File(File file) {
        boolean isZip = true;
        byte[] buffer = new byte[PACK200_HEADER.length];

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            raf.readFully(buffer);
            for (int i = 0; i < PACK200_HEADER.length; i++) {
                if (buffer[i] != PACK200_HEADER[i]) {
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
     * @return true if the file is a pack200 stream
     */
    @SuppressWarnings("Duplicates")
    public static boolean isPack200Stream(ByteArrayInputStream input) {
        boolean isZip = true;
        int length = PACK200_HEADER.length;

        try {
            input.mark(length+1);

            for (int i = 0; i < length; i++) {
                //noinspection NumericCastThatLosesPrecision
                byte read = (byte) input.read();
                if (read != PACK200_HEADER[i]) {
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
     * @return true if the file can (or rather should) be packed.
     */
    public static boolean canPack200(Repack repack, InputStream inputStream ) {
        if (repack.canDo(PackAction.Pack)) {
            String extension = repack.getExtension();

            if (inputStream instanceof ByteArrayInputStream) {
                // only pack200 certain files.
                if (JarUtil.isZipStream((ByteArrayInputStream)inputStream)) {
                    return true;
                }
            } else if (".jar".equals(extension) || ".war".equals(extension) || ".ear".equals(extension) || ".ejb".equals(extension)) {
                File file = new File(repack.getName());
                // only pack200 certain files.
                if (JarUtil.isZipFile(file)) {
                    return true;
                }
            }
        }
        return false;
    }


    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static class CommandLine {
        public static ByteArrayOutputStream pack200(InputStream inputStream) throws IOException {

            File source = File.createTempFile("temp", ".jar").getAbsoluteFile();
            File dest = File.createTempFile("temp", ".pack").getAbsoluteFile();

            source.delete();
            dest.delete();

            FileOutputStream fileOutputStream = new FileOutputStream(source);
            IO.copyStream(inputStream, fileOutputStream);
            fileOutputStream.close();

            String unpackName = "pack200";
            if (OS.isWindows()) {
                unpackName += ".exe";
            }

            commandLine(javaBinLocation + File.separatorChar + unpackName, "--no-gzip", "--segment-limit=-1", "--effort=1",
                        dest.getAbsolutePath(), source.getAbsolutePath());

            FileInputStream fileInputStream = new FileInputStream(dest);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(fileInputStream.available());
            IO.copyStream(fileInputStream, outputStream);

            source.delete();
            dest.delete();

            return outputStream;
        }

        public static ByteArrayOutputStream unpack200(InputStream inputStream) throws IOException {
            File source = File.createTempFile("temp", ".pack").getAbsoluteFile();
            File dest = File.createTempFile("temp", ".jar").getAbsoluteFile();

            source.delete();
            dest.delete();

            FileOutputStream fileOutputStream = new FileOutputStream(source);
            IO.copyStream(inputStream, fileOutputStream);
            fileOutputStream.close();

            String unpackName = "unpack200";
            if (OS.isWindows()) {
                unpackName += ".exe";
            }

            commandLine(javaBinLocation + File.separatorChar + unpackName, source.getAbsolutePath(), dest.getAbsolutePath());

            FileInputStream fileInputStream = new FileInputStream(dest);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(fileInputStream.available());
            IO.copyStream(fileInputStream, outputStream);

            source.delete();
            dest.delete();

            return outputStream;
        }


        // run a command line, and wait for it to finish. There is no parsing the OUTPUT, so one must be EXTREMELY careful
        // that the output isn't spammy.
        private static void commandLine(String... command) {
            try {
                Process start = new ProcessBuilder(command).start();
                while (true) {
                    try {
                        // throws an exception if the process hasn't exited yet.
                        start.exitValue();
                        // if we get here, we know the process finished.
                        break;
                    } catch (Exception e) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static class Java {
        /**
         * SAFELY pack200 an input stream. There is an un-reported bug (java 6 is discontinued, so blah) concerning JarInputStream and
         * reading the manifest during the Pack200 process. The problem is that the manifest, during a read/write cycle will CHANGE THE
         * ORDER of the entries of the manifest. This is HORRID, since with jars that are SIGNED, it is CRITICAL that the manifest
         * remains UNCHANGED.
         *
         * What this does, is read out the manifest, as a byte array, then when it's requested by the Pack200 process, it writes it
         * back as the byte array --- skipping the parse/write cycle which mangles the order of the entries.
         *
         * NOTE: This version WILL BREAK jar signatures!
         *
         * @return a new ByteArrayOutputStream that contains the packed data.
         */
        public static ByteArrayOutputStream pack200(InputStream inputStream, boolean addDebug) throws IOException {
            // the problem is that the manifest RE-WRITES the bytes. verification of the RE-PACKED jar fails
            // because the manifest bytes are different. Here, we want to DUPLICATE the bytes. By hook or by crook.

            if (!(inputStream instanceof ByteArrayInputStream)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(inputStream.available());
                IO.copyStream(inputStream, baos);
                IO.close(inputStream);
                inputStream = new ByteArrayInputStream(baos.toByteArray());
            }

            JarInputStream jarInputStream = SafeJarInputStream.create((ByteArrayInputStream)inputStream);

            // Create the Packer object
            Packer packer = Pack200.newPacker();

            // Initialize the state by setting the desired properties
            Map<String, String> p = packer.properties();
            p.putAll(packOptions);

            // reorder files for better compression.
            // THIS WILL BREAK JAR SIGNATURES!
            p.put(Packer.KEEP_FILE_ORDER, Packer.FALSE);

            // extra options that are not required
            p.put(Packer.EFFORT, "9"); // default is "5"

            if (!addDebug) {
                // discard debug attributes
                p.put(Packer.CODE_ATTRIBUTE_PFX + "LineNumberTable", Packer.STRIP);
                p.put(Packer.CODE_ATTRIBUTE_PFX + "SourceFile", Packer.STRIP);
                p.put(Packer.CODE_ATTRIBUTE_PFX + "LocalVariableTable", Packer.STRIP);
                p.put(Packer.CODE_ATTRIBUTE_PFX + "LocalVariableTypeTable", Packer.STRIP);
            }

            // we want to pack to a byte stream, which we will THEN compress, THEN encrypt!
            ByteArrayOutputStream outputPackStream = new ByteArrayOutputStream(8096); // approx guess.

            // stupid Pack200 warnings. This should do the trick...
            PrintStream error = System.err;
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    // do NOTHING.
                }
            }));

            // Call the packer
            packer.pack(jarInputStream, outputPackStream);
            System.setErr(error);

            outputPackStream.flush();
            IO.close(outputPackStream);
            IO.close(jarInputStream);

            return outputPackStream;
        }

        /**
         * SAFELY pack200 an input stream. There is an un-reported bug (java 6 is discontinued, so blah) concerning JarInputStream and
         * reading the manifest during the Pack200 process. The problem is that the manifest, during a read/write cycle will CHANGE THE
         * ORDER of the entries of the manifest. This is HORRID, since with jars that are SIGNED, it is CRITICAL that the manifest
         * remains UNCHANGED.
         *
         * What this does, is read out the manifest, as a byte array, then when it's requested by the Pack200 process, it writes it
         * back as the byte array --- skipping the parse/write cycle which mangles the order of the entries.
         *
         * @return a new ByteArrayOutputStream that contains the packed data.
         */
        public static ByteArrayOutputStream pack200_Default(InputStream inputStream) throws IOException {
            // the problem is that the manifest RE-WRITES the bytes. verification of the RE-PACKED jar fails
            // because the manifest bytes are different. Here, we want to DUPLICATE the bytes. By hook or by crook.

            if (!(inputStream instanceof ByteArrayInputStream)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(inputStream.available());
                IO.copyStream(inputStream, baos);
                IO.close(inputStream);
                inputStream = new ByteArrayInputStream(baos.toByteArray());
            }

            JarInputStream jarInputStream = SafeJarInputStream.create((ByteArrayInputStream)inputStream);

            // Create the Packer object
            Packer packer = Pack200.newPacker();

            // Initialize the state by setting the desired properties
            Map<String, String> p = packer.properties();
            // use largest-possible archive segments (>10% better compression).

            p.put(Packer.MODIFICATION_TIME, Packer.KEEP);

            // keep whatever sort of jar compression was originally used
            p.put(Packer.DEFLATE_HINT, Packer.KEEP);

            // use largest-possible archive segments (>10% better compression).
            p.put(Packer.SEGMENT_LIMIT, "-1");


            p.put(Packer.EFFORT, "1");


            // we want to pack to a byte stream, which we will THEN compress, THEN encrypt!
            ByteArrayOutputStream outputPackStream = new ByteArrayOutputStream(8096); // approx guess.

            // stupid Pack200 warnings. This should do the trick...
            PrintStream error = System.err;
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    // do NOTHING.
                }
            }));

            // Call the packer
            packer.pack(jarInputStream, outputPackStream);
            System.setErr(error);
            IO.close(jarInputStream);

            outputPackStream.flush();
            IO.close(outputPackStream);

            return outputPackStream;
        }

        /**
         * Safely and properly UnPack200 a jar file - the RESULTANT jar will be DEFAULT COMPRESSION LEVEL
         *
         *
         * Because we verify these files using the COMMAND LINE version of unpack200, which
         * creates DIFFERENT files than calling unpack200 in java. Because we are hashing these files
         * in their UNpacked state, we have to guarantee they are the same.
         *
         * In order to do so, we must ALSO use the commandline version of unpack200. This is
         * very unfortunate, but alas it is necessary. There is no other way around this... I've tried many.
         *
         * See the implementation in the Jar UltraSigner on how I did it.
         */
        public static ByteArrayOutputStream unpack200(ByteArrayInputStream inputStream) throws IOException {
            // check to make sure that WE ARE INDEED a PACK200 archive!
            if (!isPack200Stream(inputStream)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(inputStream.available());
                IO.copyStream(inputStream, baos);
                IO.close(inputStream);

                return baos;
            }

            ByteArrayOutputStream unpackOutputStream = new ByteArrayOutputStream(8192);
            JarOutputStream unpackJarOutputStream = new JarOutputStream(unpackOutputStream);

            // stupid Pack200 warnings. This should do the trick...
            PrintStream error = System.err;
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    // do NOTHING.
                }
            }));

            // Call the unpacker
            Unpacker unpacker = Pack200.newUnpacker();


            // Initialize the state by setting the desired properties
            Map<String, String> p = unpacker.properties();
            // use largest-possible archive segments (>10% better compression).
            // also required for repacking to work!
            p.put(Packer.SEGMENT_LIMIT, "-1");


            unpacker.unpack(inputStream, unpackJarOutputStream); // auto-closes the INPUT stream!

            System.setErr(error);
            IO.close(inputStream); // Must explicitly close the input.

            unpackJarOutputStream.flush();
            unpackJarOutputStream.finish();
            IO.close(unpackJarOutputStream); // closing the stream ALSO adds meta-data to the output!

            return unpackOutputStream;
        }


        /**
         * Repack (or NORMALIZE) a jar with pack200.  The file size will increase SLIGHTLY, however, it will be consistent with
         * future pack200 operations.
         * <p>
         * The file will be left in an UNPACKED state.
         */
        public static ByteArrayOutputStream repackJar(String filePath) throws IOException {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Sys.getBytesFromStream(new FileInputStream(new File(filePath))));
            return repackJar(inputStream);
        }

        /**
         * Repack (or NORMALIZE) a jar with pack200.  The filesize will increase SLIGHTLY, however, it will be consistent with
         * future pack200 operations.
         * <p>
         * The file will be left in an UNPACKED state.
         */
        public static ByteArrayOutputStream repackJar(File file) throws IOException {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Sys.getBytesFromStream(new FileInputStream(file)));
            return repackJar(inputStream);
        }

        /**
         * Repack (or NORMALIZE) a jar with pack200.  The filesize will increase SLIGHTLY, however, it will be consistent with
         * future pack200 operations.
         * <p>
         * The file will be left in an UNPACKED state.
         */
        public static ByteArrayOutputStream repackJar(InputStream inputStream) throws IOException {
            // always make a copy, because pack200 closes the input stream!
            ByteArrayOutputStream baos = new ByteArrayOutputStream(inputStream.available());
            IO.copyStream(inputStream, baos);
            inputStream = new ByteArrayInputStream(baos.toByteArray());

            ByteArrayOutputStream outputStream;

            // make sure we are unpacked to begin with.
            if (isPack200Stream((ByteArrayInputStream)inputStream)) {
                outputStream = unpack200((ByteArrayInputStream)inputStream);
                inputStream = new ByteArrayInputStream(outputStream.toByteArray());
            }

            outputStream = pack200_Default(inputStream);

            // we want to UNPACK the jar now so we can get a proper file "packed" state
            ByteArrayInputStream inputStream2 = new ByteArrayInputStream(outputStream.toByteArray());
            outputStream = unpack200(inputStream2);

            return outputStream;
        }
    }
}
