package dorkbox;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
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

/**
 * Utility class to deal with getting version strings, and incrementing version numbers.
 *
 * CRITICAL: If the "version" modified the build file, the variable used in the build file CANNOT be `static final`! This is because the
 * compiler will inline the value during compiletime, and we cannot modify it during runtime
 */
public
class Version {

    private final File file;
    private final Version originalVersion;
    private Version anchorOriginalVersion;

    private String prefix;
    private String[] subVersion;
    private File readme;
    private List<File> sourceFiles = new ArrayList<File>();
    private boolean ignoreSaves = false;
    private final String originalText;

    public
    Version() {
        this(null, "0");
    }

    public
    Version(String version) {
        this(null, version);
    }

    @SuppressWarnings("IncompleteCopyConstructor")
    public
    Version(final Version other) {
        file = other.file;
        originalVersion = other.originalVersion;

        prefix = other.prefix;
        readme = other.readme;
        sourceFiles.addAll(other.sourceFiles);
        ignoreSaves = other.ignoreSaves;
        originalText = other.originalText;

        final int length = other.subVersion.length;
        subVersion = new String[length];
        System.arraycopy(other.subVersion, 0, subVersion, 0, length);
    }

    public
    Version(final Class<?> clazz, final String version) {
        originalText = version;
        if (clazz != null) {
            final Paths javaFile = Builder.getJavaFile(clazz);
            file = javaFile.getFiles()
                           .get(0);
        }
        else {
            file = null;
        }

        final int length = version.length();

        // if an empty or null string is passed it, it's version is "0"
        if (length == 0) {
            prefix = "";
            subVersion = new String[1];
            subVersion[0] = "0";
            originalVersion = new Version(this);
            return;
        }


        int startIndex = -1;
        for (int i = 0; i < length; i++) {
            char c =  version.charAt(i);
            if (Character.isDigit(c)) {
                startIndex = i;
                break;
            }
        }

        // maybe it starts with a 'v' or something (so we add that back in)
        if (startIndex > 0) {
            char c =  version.charAt(startIndex - 1);
            if (c == '.') {
                prefix = version.substring(0, startIndex - 1);
            }
            else {
                prefix = version.substring(0, startIndex);
            }
        }
        else {
            prefix = "";
        }


        int dotCount = 0;
        for (int i = 0; i < length; i++) {
            char c = version.charAt(i);
            if (c == '.') {
                dotCount++;
            }
        }

        int groupCount = dotCount + 1;
        subVersion = new String[groupCount];

        String dotString;

        // maybe we have a dot, maybe not.
        int dotIndex = version.indexOf('.');
        if (dotIndex == -1) {
            // no dots
            if (startIndex == 0) {
                dotString = version;
            }
            else {
                dotString = version.substring(startIndex);
            }

            subVersion[0] = dotString;

        }
        else {
            int i = 0;
            if (startIndex > dotIndex) {
                subVersion[0] = "0";
                i++;
                dotIndex = length;
            }

            for (; i < groupCount; i++) {
                dotString = version.substring(startIndex, dotIndex);

                dotIndex++;
                startIndex = dotIndex;
                dotIndex = version.indexOf('.', dotIndex);
                if (dotIndex == -1) {
                    dotIndex = length;
                }

                subVersion[i] = dotString;
            }
        }

        originalVersion = new Version(this);
    }


    /**
     * Specifies that a readme file is ALSO a part of the versioning information
     *
     * @param readme the README.md file that also has version info in it (xml/maven format)
     */
    public
    Version readme(final File readme) {
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
    Version sourceFile(final String name, final String sourceRootPath, final Class<?> sourceFile) {
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
     * Sets this version NUMBER to a copy of what the specified version info is. NOTHING ELSE is changed.
     */
    public
    Version set(final Version other) {
        if (originalVersion != null) {
            originalVersion.set(this);
        }

        prefix = other.prefix;

        final int length = other.subVersion.length;
        subVersion = new String[length];
        System.arraycopy(other.subVersion, 0, subVersion, 0, length);

        return this;
    }

    /**
     * Gets the MAJOR version from a string. The passed in string can start with letters/words, as the first digit is used. If an empty
     * string is passed in, 0 will be returned.
     *
     * @return an int that is the MAJOR version
     */
    public
    int getMajor() {
        try {
            return Integer.parseInt(subVersion[0]);
        } catch (Exception e) {
                throw new NumberFormatException("Provided version '" + originalText + "' does not have a major (X.x.x.x) assigned or is " +
                                                "improperly formatted: " + e.getMessage());
        }
    }

    /**
     * Gets the MINOR version from a string. The passed in string can start with letters/words, as the first digit is used. If an empty
     * string is passed in, 0 will be returned. If a string with a major, but no minor is passed in, 0 will be returned.
     *
     * @return an int that is the minor
     */
    public
    int getMinor() {
        if (subVersion.length > 1) {
            try {
                return Integer.parseInt(subVersion[1]);
            } catch (Exception e) {
                throw new NumberFormatException("Provided version '" + originalText + "' does not have a minor (x.X.x.x) assigned or is " +
                                                "improperly formatted: " + e.getMessage());
            }
        }
        else {
            return 0;
        }
    }

    /**
     * @return the version information as an ARRAY.
     */
    public
    String[] get() {
        return subVersion;
    }


    /**
     * Increments the MAJOR version and resets the MINOR version to 0.
     *
     * @return the Version object, for chaining instructions
     */
    public
    Version incrementMajor() {
        return increment(0);
    }


    /**
     * Increments the MINOR version and resets the PATCH version to 0.
     *
     * @return the Version object, for chaining instructions
     */
    public
    Version incrementMinor() {
        return increment(1);
    }


    /**
     * Increments the specified version (as an array) and resets everything > index to 0.
     *
     * @return the Version object, for chaining instructions
     */
    public
    Version increment(int index) {
        try {
            subVersion[index] = Integer.toString(Integer.parseInt(subVersion[index]) + 1);
        } catch (Exception e) {
            throw new NumberFormatException("Provided version '" + originalText + "' does not have a major number assigned or is improperly " +
                                            "formatted (it must be a number): " + e.getMessage());
        }

        // if there are any indices "smaller" than the specified, we zero them out.
        final int length = subVersion.length;
        if (length > index) {
            for (int i = index+1; i < length; i++) {
                subVersion[i] = "0"; // reset minor/patch/etc to 0
            }
        }

        return this;
    }

    /**
     * Verifies that all of the version information on save() will be valid.
     *
     * @return null indicates everything is OK, false is the error message
     */
    public
    String verify() {
        if (ignoreSaves) {
            return null;
        }

        // only saves the readme if it was included.
        if (readme != null) {
            final String readmeOrigText = "<version>" + originalVersion.toStringWithoutPrefix() + "</version>";

            final String validate = validate(readme, null, readmeOrigText, originalVersion.toStringWithoutPrefix());
            if (validate != null) {
                // null means there was an error!
                return validate;
            }
        }

        // only saves the sourcefile if it was included.
        if (!sourceFiles.isEmpty()) {
            for (File sourceFile : sourceFiles) {
                final String precedingText = "String getVersion() {";
                final String readmeOrigText = "return \"" + originalVersion.toStringWithoutPrefix() + "\";";

                final String validate = validate(sourceFile, precedingText, readmeOrigText, originalVersion.toStringWithoutPrefix());
                if (validate != null) {
                    // null means there was an error!
                    return validate;
                }
            }
        }

        final String origText = "Version version = Version.get(\"" + originalVersion.toString() + "\")";

        return validate(file, null, origText, originalVersion.toString());
    }


    /**
     * Saves this file (if specified) and the README.md file (if specified)
     */
    public
    Version save() {
        if (!ignoreSaves) {
            // only saves the readme if it was included.
            if (readme != null) {
                final String readmeOrigText = "<version>" + originalVersion.toStringWithoutPrefix() + "</version>";
                final String readmeNewText = "<version>" + toStringWithoutPrefix() + "</version>";

                save(readme, null, readmeOrigText, readmeNewText);
            }

            // only saves the sourcefile if it was included.
            if (!sourceFiles.isEmpty()) {
                for (File sourceFile : sourceFiles) {
                    final String precedingText = "String getVersion() {";
                    final String readmeOrigText = "return \"" + originalVersion.toStringWithoutPrefix() + "\";";
                    final String readmeNewText = "return \"" + toStringWithoutPrefix() + "\";";

                    save(sourceFile, precedingText, readmeOrigText, readmeNewText);
                }
            }

            final String origText = "Version version = Version.get(\"" + originalVersion.toString() + "\")";
            final String newText = "Version version = Version.get(\"" + toString() + "\")";

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
            List<String> strings = FileUtil.readLines(new FileReader(file));


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
    String validate(final File file, final String precedingText, final String origText, final String expectedVersion) {
        if (file == null) {
            return "Unable to save the version information if the calling class is not detected.";
        }

        try {
            List<String> strings = FileUtil.readLines(new FileReader(file));


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
                        found = true;
                        break;
                    }
                }
            }
            else {
                for (int i = 0; i < strings.size(); i++) {
                    String string =  strings.get(i);

                    // the source string (in the file) cannot be "final", because "final" (if static) is inlined by the compiler.
                    if (string.contains(origText)) {
                        found = true;
                        break;
                    }
                }
            }

            if (!found) {
                return "Expected version string/info '" + expectedVersion + "' NOT FOUND in '" + file +
                       "'. Check spacing/formatting and try again.";
            }
        } catch (IOException e) {
            return "Unable to read file. " + e.getMessage();
        }

        return null;
    }


    @Override
    public
    String toString() {
        // this is DIFFERENT than toStringWithoutPrefix(), in that this DOES NOT do anything with length == 1.
        final int length = subVersion.length;
        final StringBuilder stringBuilder = new StringBuilder(length * 3);

        stringBuilder.append(prefix);

        for (String v : subVersion) {
            stringBuilder.append(v);
            stringBuilder.append('.');
        }

        final int length1 = stringBuilder.length();
        stringBuilder.delete(length1 - 1, length1);

        return stringBuilder.toString();
    }

    public
    String toStringWithoutPrefix() {
        final int length = subVersion.length;
        final StringBuilder stringBuilder = new StringBuilder(length * 3);

        for (String v : subVersion) {
            stringBuilder.append(v);
            stringBuilder.append('.');
        }

        if (length == 1) {
            stringBuilder.append('0');
        }
        else {
            final int length1 = stringBuilder.length();
            stringBuilder.delete(length1 - 1, length1);
        }

        return stringBuilder.toString();
    }

    /**
     * Sets the original version number to the current version number. Useful when building the same project more than once in a row.
     */
    public
    void anchor() {
        // this works, since the original version is identical EXCEPT for version numbers.
        anchorOriginalVersion = new Version(originalVersion); // makes a copy
        originalVersion.set(this);  // sets the original version to us (so that version info/numbers line up)
    }

    /**
     * Gets the [MAJOR][MINOR][etc] version from a string. The passed in string can start with letters/words, as the first digit is used.
     *
     * @return an new Version object, which has major/minor/etc version info
     */
    public static
    Version get(String version) {
        return get(getCallingClass(), version);
    }

    /**
     * @return the original version. This is useful if version numbers were incremented during the build process
     */
    public
    Version getOriginal() {
        return originalVersion;
    }

    /**
     * @return the anchored original version. This is useful if version numbers were incremented during the build process
     */
    public
    Version getAnchored() {
        return anchorOriginalVersion;
    }

    /**
     * Gets the [MAJOR][MINOR] version from a string. The passed in string can start with letters/words, as the first digit is used. If an
     * empty string is passed in, [0, 0] will be returned. If a string with a major, but no minor is passed in, [major, 0] will be
     * returned.
     *
     * @return an new Version object, which has major/minor version info
     */
    public static
    Version get(final Class<?> clazz, final String version) {
        return new Version(clazz, version);
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
    Version copy() {
        return new Version(this);
    }

    /**
     * @return true if this version number has been changed.
     */
    public
    boolean hasChanged() {
        return !this.versionEquals(originalVersion);
    }

    /**
     * @return true if this version number equals the specified version number
     */
    boolean versionEquals(final Version version) {
        return Arrays.equals(this.subVersion, version.subVersion);
    }

    public
    Version ignoreSaves() {
        this.ignoreSaves = true;
        return this;
    }
}
