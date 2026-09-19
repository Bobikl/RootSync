package com.rootsync.android.engine

/** Read only app-private PID records; never enumerate the phone's entire process table. */
internal object StartupCleanup {
    fun command(runtimePath: String): String {
        require(runtimePath.startsWith("/data/") && '\u0000' !in runtimePath)
        return """
            runtime=${SafeInput.shellQuote(runtimePath)}
            for name in scan transfer rsyncd lifecycle-watchdog; do
                pid_file="${'$'}runtime/${'$'}name.pid"
                [ -s "${'$'}pid_file" ] || continue
                pid=${'$'}(cat "${'$'}pid_file")
                case "${'$'}pid" in *[!0-9]*|'') rm -f "${'$'}pid_file"; continue ;; esac
                [ "${'$'}pid" = "${'$'}${'$'}" ] && continue
                [ "${'$'}pid" = "${'$'}PPID" ] && continue
                if [ -r "/proc/${'$'}pid/cmdline" ]; then
                    cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline")
                    case "${'$'}cmd" in *"${'$'}runtime/"*)
                        kill -INT "${'$'}pid" 2>/dev/null || true
                        sleep 0.1
                        current=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null)
                        case "${'$'}current" in *"${'$'}runtime/"*) kill -KILL "${'$'}pid" 2>/dev/null || true ;; esac
                    ;; esac
                fi
                rm -f "${'$'}pid_file"
            done
            echo TRACKED_RUNTIME_CLEANUP_DONE
        """.trimIndent()
    }
}
