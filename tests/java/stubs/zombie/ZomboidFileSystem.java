package zombie;
// Test stub: getCacheDir() honors wb.test.zomboid so tests use a temp dir.
public class ZomboidFileSystem {
    public static final ZomboidFileSystem instance = new ZomboidFileSystem();
    public String getCacheDir() {
        String d = System.getProperty("wb.test.zomboid");
        return d != null ? d : System.getProperty("user.home") + "/Zomboid";
    }
}
