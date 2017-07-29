package dorkbox;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.build.util.BuildLog;
import dorkbox.util.FileUtil;
import dorkbox.util.IO;
import dorkbox.util.OS;
import dorkbox.util.Version;

/**
 * Utility class to deal with getting version strings, and incrementing version numbers.
 *
 * CRITICAL: If the "version" modified the build file, the variable used in the build file CANNOT be `static final`! This is because the
 * compiler will inline the value during compile-time, and we cannot modify it during runtime
 */
@SuppressWarnings("Convert2Diamond")
public
class BuildVersion {

    private static
    class IncrementingVersion extends Version {
        IncrementingVersion(final Version version) {
            super(version);
        }

        IncrementingVersion(final String version) {
            super(version);
        }

        /**
         * Increments the MAJOR version and resets the MINOR/PATCH version to 0.
         *
         * @return the Version object, for chaining instructions
         */
        Version incrementMajor() {
            return increment(0);
        }


        /**
         * Increments the MINOR version and resets the PATCH version to 0.
         *
         * @return the Version object, for chaining instructions
         */
        Version incrementMinor() {
            return increment(1);
        }

        /**
         * Increments the specified version (as an array) and resets everything > index to 0.
         *
         * @return the Version object, for chaining instructions
         */
        Version increment(int index) {
            if (internalVersion.length < index) {
                internalVersion = Arrays.copyOf(internalVersion, index);
            }

            internalVersion[index] = internalVersion[index]+1;

            // if there are any indices "smaller" than the specified, we zero them out.
            final int length = internalVersion.length;
            if (length > index) {
                for (int i = index+1; i < length; i++) {
                    internalVersion[i] = 0; // reset minor/patch/etc to 0
                }
            }

            // have to re-calculate the String
            StringBuilder s = new StringBuilder(this.version.length());
            for (int i : internalVersion) {
                s.append(Integer.toString(i)).append('.');
            }
            // remove the last .
            s.deleteCharAt(s.length()-1);

            version = s.toString();

            return this;
        }
    }


    private final File file;

    private IncrementingVersion original;
    private IncrementingVersion current;
    private IncrementingVersion anchored;

    private File readme;
    private List<File> sourceFiles = new ArrayList<File>();
    private boolean ignoreSaves = false;

    /**
     * Gets the [MAJOR][MINOR] version from a string. The passed in string can start with letters/words, as the first digit is used. If an
     * empty string is passed in, [0, 0] will be returned. If a string with a major, but no minor is passed in, [major, 0] will be
     * returned.
     *
     * @return an new Version object, which has major/minor version info
     */
    public static
    BuildVersion get(final Class<?> clazz, final String version) {
        return new BuildVersion(clazz, version);
    }



    @SuppressWarnings("IncompleteCopyConstructor")
    public
    BuildVersion(final BuildVersion other) {
        this.current = new IncrementingVersion(other.original);

        file = other.file;

        readme = other.readme;
        sourceFiles.addAll(other.sourceFiles);
        ignoreSaves = other.ignoreSaves;

        original = new IncrementingVersion(other.original); // make a copy
    }

    public
    BuildVersion(final String version) {
        this.current = new IncrementingVersion(version);

        original = new IncrementingVersion(version);
        file = null;
    }

    public
    BuildVersion(final Class<?> clazz, final String version) {
        this.current = new IncrementingVersion(version);

        original = new IncrementingVersion(version);

        if (clazz != null) {
            final Paths javaFile = Builder.getJavaFile(clazz);
            file = javaFile.getFiles()
                           .get(0);
        }
        else {
            file = null;
        }
    }


    /**
     * Specifies that a readme file is ALSO a part of the versioning information
     *
     * @param readme the README.md file that also has version info in it (xml/maven format)
     */
    public
    BuildVersion readme(final File readme) {
        this.readme = readme;
        return this;
    }

    /**
     * Specifies that a source-code file is ALSO a part of the versioning information
     *
     * @param name project name for getting module/project location information via the source file
     * @param sourceRootPath the location on disk of the /src (root location of all source code) where the source file is located
     * @param sourceFile the .java file (specified as a class file) that also has version info in it
     */
    public
    BuildVersion sourceFile(final String name, final String sourceRootPath, final Class<?> sourceFile) {
        // register this module. This could be refactored out -- however it is best to do it this way so we don't duplicate code
        Builder.registerModule(name, sourceRootPath);

        try {
            // now we get the location (based on the module info above) for this class file
            final Paths source = Builder.getJavaFile(sourceFile);
            this.sourceFiles.add(source.getFiles()
                                       .get(0));
        } catch (Exception e) {
            BuildLog.println("Error getting sourceFile for class " + sourceFile.getClass());
            e.printStackTrace();
        }
        return this;
    }



    /**
     * Verifies that all of the version information on save() will be valid. Throws IOException if it was not OK
     */
    public
    void verify() throws IOException {
        if (ignoreSaves) {
            return;
        }

        // only saves the readme if it was included.
        if (readme != null) {
            final String readmeOrigText = "<version>" + original.toString() + "</version>";

            validate(readme, null, readmeOrigText, original.toString());
        }

        // only saves the sourcefile if it was included.
        if (!sourceFiles.isEmpty()) {
            for (File sourceFile : sourceFiles) {
                final String precedingText = "Version getVersion() {";
                final String readmeOrigText = "return new Version(\"" + original.toString() + "\");";

                validate(sourceFile, precedingText, readmeOrigText, original.toString());
            }
        }

        // now check the build file
        final String origText = "BuildVersion version = BuildVersion.get(\"" + original.toString() + "\")";
        validate(file, null, origText, original.toString());
    }


    /**
     * Saves this file (if specified) and the README.md file (if specified)
     */
    public
    BuildVersion save() {
        if (original.toString()
                    .equals(toString())) {
            // don't save anything if nothing has changed.
            return this;
        }

        if (!ignoreSaves) {
            // only saves the readme if it was included.
            if (readme != null) {
                final String readmeOrigText = "<version>" + original.toString() + "</version>";
                final String readmeNewText = "<version>" + toString() + "</version>";

                save(readme, null, readmeOrigText, readmeNewText);
            }

            // only saves the sourcefile if it was included.
            if (!sourceFiles.isEmpty()) {
                for (File sourceFile : sourceFiles) {
                    final String precedingText = "Version getVersion() {";
                    final String readmeOrigText = "return new Version(\"" + original.toString() + "\");";
                    final String readmeNewText = "return new Version(\"" + toString() + "\");";

                    save(sourceFile, precedingText, readmeOrigText, readmeNewText);
                }
            }

            final String origText = "BuildVersion version = BuildVersion.get(\"" + original.toString() + "\")";
            final String newText = "BuildVersion version = BuildVersion.get(\"" + toString() + "\")";

            save(file, null, origText, newText);
        }

        return this;
    }


    /**
     * Saves this file, if there is a file specified.
     *
     * @param file this is the file we are rewriting
     * @param precedingText can be NULL, but is the PRECEDING text to the orig text (in the event we want a more exact match)
     * @param origText this is what the ORIGINAL text must be
     * @param newText this is what the text will become
     */
    private static
    void save(final File file, String precedingText, String origText, String newText) {
        if (file == null) {
            throw new RuntimeException("Unable to save the version information if the calling class is not detected.");
        }

        try {
            List<String> strings = FileUtil.read(file, true);


            boolean hasPrecedingText = precedingText != null && !precedingText.isEmpty();
            boolean foundPrecedingText = false;
            boolean found = false;

            if (hasPrecedingText) {
                for (int i = 0; i < strings.size(); i++) {
                    String string = strings.get(i);
                    if (string.contains(precedingText)) {
                        foundPrecedingText = true;
                    }

                    if (foundPrecedingText && string.contains(origText)) {
                        string = string.replace(origText, newText);

                        strings.set(i, string);
                        found = true;
                        break;
                    }
                }
            }
            else {
                for (int i = 0; i < strings.size(); i++) {
                    String string =  strings.get(i);

                    // it cannot be "final", because "final" (if static) is inlined by the compiler.
                    if (string.contains(origText)) {
                        string = string.replace(origText, newText);

                        strings.set(i, string);
                        found = true;
                        break;
                    }
                }
            }


            if (!found) {
                throw new RuntimeException("Expected version string/info NOT FOUND in '" + file +
                                           "'. Check spacing/formatting and try again.");
            }

            // now write the strings back to the file
            Writer output = null;
            try {
                output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
                // FileWriter always assumes default encoding is OK

                int lastIndex = strings.size();
                // remove all ending strings (except the one JUST BEFORE there is text)
                for (int i = lastIndex - 1; i >= 0; i--) {
                    final String string = strings.get(i);

                    if (string == null || string.isEmpty()) {
                        lastIndex = i;
                    } else {
                        break;
                    }
                }

                // write all of the original args
                for (int i = 0; i < lastIndex; i++) {
                    final String arg = strings.get(i);
                    output.write(arg);
                    output.write(OS.LINE_SEPARATOR);
                }

                // make sure there is a new line at the end of the file (so it's easier to read)
                output.write(OS.LINE_SEPARATOR);
            } finally {
                IO.close(output);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to write file.", e);
        }
    }

    private static
    void validate(final File file, final String precedingText, final String origText, final String expectedVersion) throws IOException {
        if (file == null) {
            throw new IOException("Unable to save the version information if the calling class is not detected.");
        }

        List<String> strings = FileUtil.read(file, true);


        boolean hasPrecedingText = precedingText != null && !precedingText.isEmpty();
        boolean foundPrecedingText = false;
        boolean found = false;

        if (hasPrecedingText) {
            for (String string : strings) {
                if (string.contains(precedingText)) {
                    foundPrecedingText = true;
                }

                if (foundPrecedingText && string.contains(origText)) {
                    found = true;
                    break;
                }
            }
        }
        else {
            for (String string : strings) {
                // the source string (in the file) cannot be "final", because "final" (if static) is inlined by the compiler.
                if (string.contains(origText)) {
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            throw new IOException("Expected version string/info '" + expectedVersion + "' NOT FOUND in '" + file +
                   "'. Check spacing/formatting and try again.");
        }
    }

    /**
     * Gets the [MAJOR][MINOR] version from a string. The passed in string can start with letters/words, as the first digit is used.
     *
     * @return an new Version object, which has major/minor/etc version info
     */
    public static
    BuildVersion get(String version) {
        return get(getCallingClass(), version);
    }

    /**
     * @return the original version. This is useful if version numbers were incremented during the build process
     */
    public
    Version getOriginal() {
        return original;
    }

    private static Class getCallingClass() {
        // java < 8, it is SIGNIFICANTLY faster to call sun.reflect.Reflection.getCallerClass
        // java >= 8, Thread.stackTrace was fixed, so it is the now preferred method
        if (OS.javaVersion < 8) {
            Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

            if (callerClass == null) {
                return null;

            }
            return callerClass;
        } else {
            StackTraceElement[] cause = Thread.currentThread().getStackTrace();
            if (cause == null || cause.length < 3) {
                return null;
            }

            StackTraceElement stackTraceElement = cause[3];
            if (stackTraceElement == null) {
                return null;
            }

            try {
                return Class.forName(stackTraceElement.getClassName());
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    /**
     * @return a copy of the current version. Any updates to the copy do not apply to the original.
     */
    public
    BuildVersion copy() {
        return new BuildVersion(this);
    }

    /**
     * @return true if this version number has been changed.
     */
    public
    boolean hasChanged() {
        return !this.current.equals(original);
    }

    public
    BuildVersion ignoreSaves() {
        this.ignoreSaves = true;
        return this;
    }

    /**
     * Increments the MAJOR version and resets the MINOR/PATCH version to 0.
     *
     * @return the Version object, for chaining instructions
     */
    public
    Version incrementMajor() {
        return this.current.incrementMajor();
    }


    /**
     * Increments the MINOR version and resets the PATCH version to 0.
     *
     * @return the Version object, for chaining instructions
     */
    public
    Version incrementMinor() {
        return this.current.incrementMinor();
    }

    @Override
    public
    String toString() {
        return this.current.toString();
    }

    /**
     * Anchored means that
     *  - anchored = original
     *  - original = copy of current, so reading files, etc, work AFTER the version was changed, but are not modified any further
     */
    public
    void anchor() {
        this.anchored = this.original;
        this.original = new IncrementingVersion(this.current);
    }

    public
    Version getAnchored() {
        return this.anchored;
    }
}
