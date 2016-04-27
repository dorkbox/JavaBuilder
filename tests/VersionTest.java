import dorkbox.Version;

import static org.junit.Assert.assertTrue;

/**
 *
 */
public
class VersionTest {
    @org.junit.Test
    public
    void testName() throws Exception {
        assertTrue(0 == new Version("").getMajor());
        assertTrue(0 == new Version("").getMinor());

        assertTrue(1 == new Version("1").getMajor());
        assertTrue(0 == new Version("v.1").getMajor());
        assertTrue(1 == new Version("v1").getMajor());
        assertTrue(1 == new Version("v1.2").getMajor());
        assertTrue(1 == new Version("1.2").getMajor());

        assertTrue(0 == new Version("1").getMinor());
        assertTrue(1 == new Version("v.1").getMinor());
        assertTrue(0 == new Version("v1").getMinor());
        assertTrue(2 == new Version("v1.2").getMinor());
        assertTrue(2 == new Version("1.2").getMinor());

        assertTrue(11 == new Version("11.2").getMajor());
        assertTrue(2 == new Version("11.2").getMinor());


        assertTrue(2 == new Version("1.2.3.6.45").getMinor());
        assertTrue("1.2.3.6.45".equals(new Version("1.2.3.6.45").toString()));


        assertTrue(new Version("v1.2").toString().equals("v1.2"));
        assertTrue(new Version("v1.2").toStringWithoutPrefix().equals("1.2"));
    }
}
