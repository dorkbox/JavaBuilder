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

    private File outputFile;
    private File outputFOrg;

    private File outputSourceFile;
    private File outputSourceFOrg;


    // for serialization
    @SuppressWarnings("unused")
    private
    OutputFile() {
    }

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
                outputFileString = FileUtil.normalize(newName).getAbsolutePath();
                outputFOrgString = FileUtil.normalize(cleanedName).getAbsolutePath();
            }
            else if (cleanedName.endsWith(version.toString())){
                // have to replace current with ORIG
                String newName = cleanedName.replace(version.toString(), original);

                // version string is ALREADY appended to the fileName
                outputFileString = FileUtil.normalize(cleanedName).getAbsolutePath();
                outputFOrgString = FileUtil.normalize(newName).getAbsolutePath();
            } else {
                // version string is appended to the fileName
                outputFileString = FileUtil.normalize(cleanedName + "_" + version).getAbsolutePath();
                outputFOrgString = FileUtil.normalize(cleanedName + "_" + original).getAbsolutePath();
            }
        }
        else {
            outputFileString = FileUtil.normalize(cleanedName).getAbsolutePath();
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
