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

    private final File outputFile;
    private final File outputFOrg;

    private final File outputSourceFile;
    private final File outputSourceFOrg;

    public
    OutputFile(final Version version, final String outputFileName, final String outputSourceFileName) {
        //  If version info is specified, then the CURRENT (new) version info is used to generate the filename
        String extension = FileUtil.getExtension(outputFileName);
        String cleanedName = FileUtil.getNameWithoutExtension(outputFileName);

        String outputFileString;
        String outputFOrgString;

        if (version != null) {
            // is the ORIG version part of the filename?
            final String original = version.getOriginal().toString();
            if (cleanedName.endsWith(original)) {
                // have to replace ORIG with current
                String newName = cleanedName.replace(original, version.toString());

                // version string is replaced in the fileName
                outputFileString = FileUtil.normalizeAsFile(newName);
                outputFOrgString = FileUtil.normalizeAsFile(cleanedName);
            }
            else if (cleanedName.endsWith(version.toString())){
                // have to replace current with ORIG
                String newName = cleanedName.replace(version.toString(), original);

                // version string is ALREADY appended to the fileName
                outputFileString = FileUtil.normalizeAsFile(cleanedName);
                outputFOrgString = FileUtil.normalizeAsFile(newName);
            } else {
                // version string is appended to the fileName
                outputFileString = FileUtil.normalizeAsFile(cleanedName + "_" + version);
                outputFOrgString = FileUtil.normalizeAsFile(cleanedName + "_" + original);
            }
        }
        else {
            outputFileString = FileUtil.normalizeAsFile(cleanedName);
            outputFOrgString = outputFileString;
        }

        String newExtension;
        if (extension.isEmpty()) {
            // no specific extension
            newExtension = Project.JAR_EXTENSION;
        }
        else {
            // WITH specific extension
            newExtension = "." + extension;
        }

        outputFile = new File(outputFileString + newExtension);
        outputFOrg = new File(outputFOrgString + newExtension);

        if (outputSourceFileName != null) {
            // they are the same here, because we SPECIFIED what we want the "source" file to be
            outputSourceFile = FileUtil.normalize(new File(outputSourceFileName));
            outputSourceFOrg = FileUtil.normalize(new File(outputSourceFileName));
        } else {
            newExtension = Project.SRC_EXTENSION;
            outputSourceFile =  new File(outputFileString + newExtension);;
            outputSourceFOrg = new File(outputFOrgString + newExtension);
        }
    }

    /**
     * Uses the new/current version info as part of the filename (if it's specified).  Ends in either .jar or it's specified extension
     *
     * @return the outputfile.
     */
    public
    File get() {
        return outputFile;
    }

    /**
     * Uses the old/original version info as part of the filename (if it's specified).  Ends in either .jar or it's specified extension
     *
     * @return the outputfile.
     */
    public
    File getOriginal() {
        return outputFOrg;
    }

    /**
     * Uses the new/current version info as part of the filename (if it's specified).  Ends in xxxx_src.zip
     *
     * @return the outputfile.
     */
    public
    File getSource() {
        return outputSourceFile;
    }

    /**
     * Uses the old/original version info as part of the filename (if it's specified).  Ends in xxxx_src.zip
     *
     * @return the outputfile.
     */
    public
    File getSourceOriginal() {
        return outputSourceFOrg;
    }
}
