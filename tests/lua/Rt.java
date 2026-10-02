import com.workshopbridge.Json;
import java.util.*;

public class Rt {
    public static void main(String[] a) {
        // mimic JobManager.jobStatus() output shapes
        Map<String, Object> running = new LinkedHashMap<>();
        running.put("id", "job-42"); running.put("state", "running");
        running.put("done", 2); running.put("total", 5);
        running.put("current", "Downloading \"Cool Mod\" (id 12345) \\ path");
        running.put("error", "");
        System.out.println(Json.stringify(running));
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("id", "job-43"); done.put("state", "done");
        done.put("done", 3); done.put("total", 3);
        done.put("updates", Arrays.asList("ModA", "ModB"));
        done.put("installed", Arrays.asList("ModA"));
        done.put("failed", new ArrayList<>());
        done.put("error", "");
        System.out.println(Json.stringify(done));
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("id", "job-44"); failed.put("state", "failed");
        failed.put("error", "Couldn't reach Steam's servers - check your internet connection.");
        System.out.println(Json.stringify(failed));
    }
}
