

import dorkbox.license.License;
import dorkbox.license.LicenseType;

// these are licenese's that are have code embedded into the various projects
public class Licenses {
    public static class OAK {
        public static final License
        OAK = new License("Dorkbox OAK", LicenseType.APACHE)
                       .u("https://github.com/dorkbox")
                       .c("Copyright 2012, dorkbox, llc")
                       .n("Java project management and build tool, using the Java language");

        public static final License
        Scar = new License("Scar", LicenseType.BSD)
                        .u("https://github.com/EsotericSoftware/scar")
                        .c("Copyright 2011, Nathan Sweet");
    }

    public static class DorkboxUtil {
        public static final License
        AnnotationDetector = new License("AnnotationDetector", LicenseType.APACHE)
                                      .u("https://github.com/rmuller/infomas-asl")
                                      .c("Copyright 2011 - 2014, XIAM Solutions B.V. (http://www.xiam.nl)");

        public static final License
        DorkboxUtil = new License("Dorkbox Utils", LicenseType.APACHE)
                               .u("https://github.com/dorkbox")
                               .c("Copyright 2010, dorkbox, llc");

        public static final License
        FilenameUtils = new License("FilenameUtils.java (normalize + dependencies)", LicenseType.APACHE)
                                 .u("http://commons.apache.org/proper/commons-io/")
                                 .c("Copyright 2013, ASF")
                                 .a("Kevin A. Burton").a("Scott Sanders").a("Daniel Rall").a("Christoph.Reck")
                                 .a("Peter Donald").a("Jeff Turner").a("Matthew Hawthorne").a("Martin Cooper")
                                 .a("Jeremias Maerki").a("Stephen Colebourne");

        public static final License
        MigBase64 = new License("MiG Base64", LicenseType.BSD)
                             .u("http://migbase64.sourceforge.net/")
                             .c("Copyright 2004, Mikael Grev, MiG InfoCom AB. (base64@miginfocom.com)")
                             .n("High performance base64 encoder & decoder");
    }
}
