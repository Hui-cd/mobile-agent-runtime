import java.lang.reflect.Method;

public final class MoveRootTask {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("task_id display_id required");
        int task = Integer.parseInt(args[0]);
        int display = Integer.parseInt(args[1]);
        if (task <= 0 || display < 0) throw new IllegalArgumentException("invalid identifiers");
        Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Method move = service.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
        move.setAccessible(true);
        move.invoke(service, task, display);
        Object uid = Class.forName("android.os.Process").getMethod("myUid").invoke(null);
        System.out.println("MOVED task=" + task + " display=" + display + " uid=" + uid);
    }
}
