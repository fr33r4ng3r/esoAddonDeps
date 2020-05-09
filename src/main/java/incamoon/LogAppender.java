package incamoon;

@FunctionalInterface
public interface LogAppender {
    void log(String message);
}
