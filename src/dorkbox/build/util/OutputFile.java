package dorkbox.build.util;

import dorkbox.Version;
import dorkbox.build.Project;
import dorkbox.util.FileUtil;

import java.io.File;

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
     * @return the outputfile. If version info is specified, then the CURRENT version info is used to generate the filename
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
                if (extension == null) {
                    return FileUtil.normalize(new File(newName + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(newName + "." + extension));
                }
            }
            else {
                // version string is appended to the fileName
                if (extension == null) {
                    return FileUtil.normalize(new File(cleanedName + "_" + this.version + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(cleanedName + "_" + this.version + extension));
                }
            }
        }
        else {
            if (extension == null) {
                return FileUtil.normalize(new File(outputFileName + Project.JAR_EXTENSION));
            }
            else {
                return FileUtil.normalize(new File(outputFileName));
            }
        }
    }

    /**
     * @return the SOURCE outputfile. If version info is specified, then the CURRENT version info is used to generate the filename
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
            return FileUtil.normalize(new File(outputFileName + Project.SRC_EXTENSION));
        }
    }

    /**
     * @return the outputfile. If version info is specified, then the ORIGINAL version info is used to generate the filename
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
                if (extension == null) {
                    return FileUtil.normalize(new File(cleanedName + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(cleanedName + "." + extension));
                }
            }
            else {
                // version string is appended to the fileName
                if (extension == null) {
                    return FileUtil.normalize(new File(cleanedName + "_" + this.version.getOriginal() + Project.JAR_EXTENSION));
                }
                else {
                    return FileUtil.normalize(new File(cleanedName + "_" + this.version.getOriginal() + extension));
                }
            }
        }
        else {
            if (extension == null) {
                return FileUtil.normalize(new File(outputFileName + Project.JAR_EXTENSION));
            }
            else {
                return FileUtil.normalize(new File(outputFileName));
            }
        }
    }

    /**
     * @return the SOURCE outputfile. If version info is specified, then the ORIGINAL version info is used to generate the filename
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
            return FileUtil.normalize(new File(outputFileName + Project.SRC_EXTENSION));
        }
    }
}
