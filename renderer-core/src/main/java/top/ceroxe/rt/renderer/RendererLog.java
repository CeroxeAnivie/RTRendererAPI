package top.ceroxe.rt.renderer;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renderer-owned logging boundary.
 *
 * <p>The core must not inherit a host runtime's logger or lifecycle. A standalone
 * renderer uses the JDK system logger by default, while an host may
 * install a sink during bootstrap.  Formatting is deliberately deferred until
 * after the level check so disabled debug and telemetry paths allocate nothing.</p>
 */
public final class RendererLog {
    /** System property defining the minimum JDK system-logger level. */
    public static final String MINIMUM_LEVEL_PROPERTY = "top.ceroxe.rt.renderer.logLevel";
    private static final System.Logger SYSTEM_LOGGER = System.getLogger("top.ceroxe.rt.renderer");
    private static final Sink SYSTEM_SINK = new SystemSink();
    private static final AtomicReference<Sink> SINK = new AtomicReference<>(SYSTEM_SINK);

    private RendererLog() {
    }

    /**
     * Installs an application-owned logging sink.
     * @param sink non-null replacement sink
     */
    public static void installSink(Sink sink) {
        SINK.set(Objects.requireNonNull(sink, "sink"));
    }

    /** Restores the default JDK system-logger sink. */
    public static void restoreSystemSink() {
        SINK.set(SYSTEM_SINK);
    }

    /**
     * Tests whether informational logging is enabled.
     * @return whether the current sink accepts informational messages
     */
    public static boolean isInfoEnabled() {
        return SINK.get().enabled(Level.INFO);
    }

    /**
     * Tests whether debug logging is enabled.
     * @return whether the current sink accepts debug messages
     */
    public static boolean isDebugEnabled() {
        return SINK.get().enabled(Level.DEBUG);
    }

    /**
     * Emits a lazily formatted debug message.
     * @param template brace-placeholder message template
     * @param arguments template arguments, optionally ending with a failure
     */
    public static void debug(String template, Object... arguments) {
        emit(Level.DEBUG, template, arguments);
    }

    /**
     * Emits a lazily formatted informational message.
     * @param template brace-placeholder message template
     * @param arguments template arguments, optionally ending with a failure
     */
    public static void info(String template, Object... arguments) {
        emit(Level.INFO, template, arguments);
    }

    /**
     * Emits a lazily formatted warning.
     * @param template brace-placeholder message template
     * @param arguments template arguments, optionally ending with a failure
     */
    public static void warn(String template, Object... arguments) {
        emit(Level.WARNING, template, arguments);
    }

    /**
     * Emits a lazily formatted error.
     * @param template brace-placeholder message template
     * @param arguments template arguments, optionally ending with a failure
     */
    public static void error(String template, Object... arguments) {
        emit(Level.ERROR, template, arguments);
    }

    private static void emit(Level level, String template, Object[] arguments) {
        Sink sink = SINK.get();
        if (!sink.enabled(level)) {
            return;
        }
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        Throwable failure = trailingFailure(safeArguments);
        int printableArguments = failure == null ? safeArguments.length : safeArguments.length - 1;
        sink.log(level, format(Objects.requireNonNull(template, "template"), safeArguments, printableArguments), failure);
    }

    private static Throwable trailingFailure(Object[] arguments) {
        return arguments.length > 0 && arguments[arguments.length - 1] instanceof Throwable failure
                ? failure
                : null;
    }

    private static String format(String template, Object[] arguments, int argumentCount) {
        if (argumentCount == 0) {
            return template;
        }
        StringBuilder message = new StringBuilder(template.length() + argumentCount * 16);
        int cursor = 0;
        int argument = 0;
        while (argument < argumentCount) {
            int placeholder = template.indexOf("{}", cursor);
            if (placeholder < 0) {
                break;
            }
            message.append(template, cursor, placeholder);
            message.append(String.valueOf(arguments[argument++]));
            cursor = placeholder + 2;
        }
        message.append(template, cursor, template.length());
        while (argument < argumentCount) {
            message.append(" [").append(String.valueOf(arguments[argument++])).append(']');
        }
        return message.toString();
    }

    /** Application integration point for renderer-owned log events. */
    public interface Sink {
        /**
         * Tests whether a level is accepted.
         * @param level logging level
         * @return whether messages should be formatted
         */
        boolean enabled(Level level);

        /**
         * Emits a formatted message.
         * @param level logging level
         * @param message formatted message
         * @param failure optional trailing failure
         */
        void log(Level level, String message, Throwable failure);
    }

    private static final class SystemSink implements Sink {
        private final Level minimumLevel = configuredMinimumLevel();

        @Override
        public boolean enabled(Level level) {
            return level.getSeverity() >= minimumLevel.getSeverity() && SYSTEM_LOGGER.isLoggable(level);
        }

        @Override
        public void log(Level level, String message, Throwable failure) {
            if (failure == null) {
                SYSTEM_LOGGER.log(level, message);
            } else {
                SYSTEM_LOGGER.log(level, message, failure);
            }
        }

        private static Level configuredMinimumLevel() {
            String configured = System.getProperty(MINIMUM_LEVEL_PROPERTY, Level.INFO.getName()).trim();
            try {
                return Level.valueOf(configured.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return Level.INFO;
            }
        }
    }
}
