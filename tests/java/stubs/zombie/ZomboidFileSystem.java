package zombie;
// Test stub: getCacheDir() honors wb.test.zomboid. There is deliberately NO
// fallback to the real ~/Zomboid: tests must never touch the user's actual
// game folder, even if someone runs the harness without the property set
// (WBTest refuses to run in that case).
public class ZomboidFileSystem {
    public static final ZomboidFileSystem instance = new ZomboidFileSystem();
    public String getCacheDir() {
        String d = System.getProperty("wb.test.zomboid");
        if (d == null || d.isEmpty()) {
            throw new IllegalStateException(
                    "wb.test.zomboid not set - run the tests via tests/java/run.sh");
        }
        return d;
    }
}
