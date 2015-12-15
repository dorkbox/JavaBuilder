package dorkbox;

import com.esotericsoftware.wildcard.Paths;
import dorkbox.util.FileUtil;
import dorkbox.util.OS;
import dorkbox.util.Sys;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;

/**
 * Utility class to deal with getting version strings, and incrementing version numbers
 */
public
class Version {

    private final File file;
    private final Version originalVersion;

    private String prefix;
    private int[] dots;
    private File readme;

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

        final int length = other.dots.length;
        dots = new int[length];
        System.arraycopy(other.dots, 0, dots, 0, length);
    }

    public
    Version(final Class<?> clazz, final String version) {
        if (clazz != null) {
            final Paths javaFile;
            try {
                javaFile = Builder.getJavaFile(clazz);
            } catch (IOException e) {
                throw new RuntimeException("Unable to open source file for class '" + clazz.getSimpleName() + "'. Please verify it and " +
                                           "try again.");
            }

            file = javaFile.getFiles()
                           .get(0);
        }
        else {
            file = null;
        }

        final int length = version.length();
        if (length == 0) {
            prefix = "";
            dots = new int[1];
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
        dots = new int[groupCount];

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

            try {
                dots[0] = Integer.parseInt(dotString);
            } catch (Exception e) {
                throw new NumberFormatException("Provided version '" + version + "' does not have a minor (X.x) assigned or is improperly " +
                                                "formatted: " + e.getMessage());
            }
        }
        else {
            int i = 0;
            if (startIndex > dotIndex) {
                dots[0] = 0;
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

                try {
                    dots[i] = Integer.parseInt(dotString);
                } catch (Exception e) {
                    throw new NumberFormatException("Provided version '" + version + "' does not have a minor (X.x) assigned or is improperly " +
                                                    "formatted: " + e.getMessage());
                }
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
     * Sets this version information to a COPY of what the specified version info is. The FILE info is not changed, and the
     * originalVersion is saved as the pre-modified version.
     */
    public
    Version set(final Version version) {
        if (originalVersion != null) {
            originalVersion.set(this);
        }

        this.prefix = version.prefix;
        this.readme = version.readme;

        final int length = version.dots.length;
        dots = new int[length];
        System.arraycopy(version.dots, 0, dots, 0, length);

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
        return dots[0];
    }

    /**
     * Gets the MINOR version from a string. The passed in string can start with letters/words, as the first digit is used. If an empty
     * string is passed in, 0 will be returned. If a string with a major, but no minor is passed in, 0 will be returned.
     *
     * @return an int that is the minor
     */
    public
    int getMinor() {
        if (dots.length > 1) {
            return dots[1];
        }
        else {
            return 0;
        }
    }

    /**
     * @return the version information as an ARRAY.
     */
    public
    int[] get() {
        return dots;
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
        dots[index] = dots[index] + 1;

        // if there are any indices "smaller" than the specified, we zero them out.
        final int length = dots.length;
        if (length > index) {
            for (int i = index+1; i < length; i++) {
                dots[i] = 0; // reset minor/patch/etc to 0
            }
        }

        return this;
    }


    /**
     * Saves this file (if specified) and the README.md file (if specified)
     */
    public
    Version save() {
        // only saves the readme if it was included.
        if (readme != null) {
            final String readmeOrigText = "<version>" + originalVersion.toStringOnlyNumbers() + "</version>";
            final String readmeNewText = "<version>" + toStringOnlyNumbers() + "</version>";

            save(readme, readmeOrigText, readmeNewText);
        }

        final String origText = "Version version = Version.get(\"" + originalVersion.toString() + "\")";
        final String newText = "Version version = Version.get(\"" + toString() + "\")";

        save(file, origText, newText);

        return this;
    }


    /**
     * Saves this file, if there is a file specified.
     */
    public
    Version save(final File file, String origText, String newText) {
        if (file == null) {
            throw new RuntimeException("Unable to save the version information if the calling class is not detected.");
        }

        try {
            List<String> strings = FileUtil.readLines(new FileReader(file));


            boolean found = false;

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

            if (!found) {
                throw new RuntimeException("Version string/info NOT FOUND. Check spacing/formatting and try again.");
            }

            // now write the strings back to the file
            Writer output = null;
            try {
                output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
                // FileWriter always assumes default encoding is OK

                // write all of the original args
                for (String arg : strings) {
                    output.write(arg);
                    output.write(OS.LINE_SEPARATOR);
                }

                // make sure there is a new line at the end of the argument (so it's easier to read)
                output.write(OS.LINE_SEPARATOR);
            } finally {
                Sys.close(output);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to write file.", e);
        }

        return this;
    }



    @Override
    public
    String toString() {
        return prefix + toStringOnlyNumbers();
    }

    public
    String toStringOnlyNumbers() {
        final int length = dots.length;
        final StringBuilder stringBuilder = new StringBuilder(length * 3);

        for (int dot : dots) {
            stringBuilder.append(dot);
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
            Class<?> callerClass = sun.reflect.Reflection.getCallerClass(2);

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
}