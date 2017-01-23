package dorkbox.build.util;

import java.io.File;

import dorkbox.Version;
import dorkbox.build.Project;
import dorkbox.util.FileUtil;

/**
 * Output file, conserves version information
 */
public
class OutputFile {
    private final Version version;
    private final String outputFileName;

    public
    OutputFile(final Version version, final String outputFileName) {
        this.version = version;
        this.outputFileName = outputFileName;
    }

    /**
     * If version info is specified, then the CURRENT (new) version info is used to generate the filename
     *
     * @return the outputfile.
     */
    public
    File get() {
        String extension = FileUtil.getExtension(outputFileName);
        String cleanedName = FileUtil.getNameWithoutExtension(outputFileName);

        if (this.version != null) {
            // is the ORIG version part of the filename?
            final String original = this.version.getOriginal().toString();
            if (cleanedName.endsWith(original)) {
                // have to replace ORIG with current
                String newName = cleanedName.replace(original, this.version.toString());

                // version string is appended to the fileName
                if (extension.isEmpty()) {
                    return FileUtil.normalize(new File(newName + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(newName + "." + extension));
                }
            }
            else if (cleanedName.endsWith(this.version.toString())){
                // version string is ALREADY appended to the fileName
                if (extension.isEmpty()) {
                    return FileUtil.normalize(new File(cleanedName + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(cleanedName + "." + extension));
                }
            } else {
                // version string is appended to the fileName
                if (extension.isEmpty()) {
                    return FileUtil.normalize(new File(cleanedName + "_" + this.version + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(cleanedName + "_" + this.version + "." + extension));
                }
            }
        }
        else {
            if (extension.isEmpty()) {
                return FileUtil.normalize(new File(cleanedName + Project.JAR_EXTENSION));
            }
            else {
                return FileUtil.normalize(new File(outputFileName));
            }
        }
    }

    /**
     * If version info is specified, then the CURRENT (new) version info is used to generate the filename
     *
     * @return the SOURCE outputfile.
     */
    public
    File getSource() {
        String cleanedName = FileUtil.getNameWithoutExtension(outputFileName);

        if (this.version != null) {
            // is the ORIG version part of the filename?
            final String original = this.version.getOriginal().toString();
            if (cleanedName.endsWith(original)) {
                // have to replace ORIG with current
                String newName = cleanedName.replace(original, this.version.toString());
                return FileUtil.normalize(new File(newName + Project.SRC_EXTENSION));
            }
            else {
                return FileUtil.normalize(new File(cleanedName + "_" + this.version + Project.SRC_EXTENSION));
            }
        }
        else {
            // always append _src.zip
            return FileUtil.normalize(new File(cleanedName + Project.SRC_EXTENSION));
        }
    }

    /**
     * If version info is specified, then the ORIGINAL (unmodified) version info is used to generate the filename
     *
     * @return the outputfile.
     */
    public
    File getOriginal() {
        String extension = FileUtil.getExtension(outputFileName);
        String cleanedName = FileUtil.getNameWithoutExtension(outputFileName);

        if (this.version != null) {
            // is the ORIG version part of the filename?
            final String original = this.version.getOriginal().toString();
            if (cleanedName.endsWith(original)) {
                // version string is appended to the fileName
                if (extension.isEmpty()) {
                    return FileUtil.normalize(new File(cleanedName + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(cleanedName + "." + extension));
                }
            }
            else {
                // version string is appended to the fileName
                if (extension.isEmpty()) {
                    return FileUtil.normalize(new File(cleanedName + "_" + this.version.getOriginal() + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(cleanedName + "_" + this.version.getOriginal() + "." + extension));
                }
            }
        }
        else {
            if (extension.isEmpty()) {
                return FileUtil.normalize(new File(cleanedName + Project.JAR_EXTENSION));
            }
            else {
                return FileUtil.normalize(new File(outputFileName));
            }
        }
    }

    /**
     * If version info is specified, then the ORIGINAL (unmnodified) version info is used to generate the filename
     *
     * @return the SOURCE outputfile.
     */
    public
    File getSourceOriginal() {
        String cleanedName = FileUtil.getNameWithoutExtension(outputFileName);

        if (this.version != null) {
            // is the ORIG version part of the filename?
            final String original = this.version.getOriginal().toString();
            if (cleanedName.endsWith(original)) {
                // have to replace ORIG with current
                return FileUtil.normalize(new File(cleanedName + Project.SRC_EXTENSION));
            }
            else {
                return FileUtil.normalize(new File(cleanedName + "_" + this.version.getOriginal() + Project.SRC_EXTENSION));
            }
        }
        else {
            // always append _src.zip
            return FileUtil.normalize(new File(cleanedName + Project.SRC_EXTENSION));
        }
    }
}
