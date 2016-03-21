import dorkbox.Instructions;
import dorkbox.Version;
import dorkbox.build.Project;
import dorkbox.build.ProjectJar;
import dorkbox.license.License;

import java.util.List;

// @formatter:off
@Instructions
public class Annotations {

    public static final String name = "Annotations";

    public static Version version = Version.get("v2.5");

    public static List<License> license = License.list(Licenses.Annotations.Annotations,
                                                       Licenses.Annotations.AnnotationDetector,
                                                       Licenses.DorkboxUtil.DorkboxUtil,
                                                       Licenses.DorkboxUtil.FilenameUtils,
                                                       LicenseLibs.SLF4J);

    public static class Dist {
        public static final String jar = BuildStrings.path("libs", name + "_" + version + Project.JAR_EXTENSION);
    }

    /** contains all of the information necessary for projects that might depend on this one */
    public static ProjectJar project = ProjectJar.create(name)
                                                 .license(license)
                                                 .version(version)
                                                 .outputFile(Dist.jar);
}

