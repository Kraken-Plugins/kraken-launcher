package com.kraken.launcher;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * The launcher jar's Premain-Class. The Installer adds the jar to RuneLite's config.json as -javaagent, so this runs in
 * RuneLite's launcher JVM before net.runelite.launcher.Launcher.main. premain does not receive the program arguments,
 * so it only registers the Instrumentation handle with ByteBuddy and hooks RuneLite's main with
 * {@link LauncherMainAdvice}; {@link KrakenStartup} does the rest once main is called.
 */
@Slf4j
public final class KrakenAgent {

    private static final String RUNELITE_LAUNCHER_CLASS = "net.runelite.launcher.Launcher";

    private static volatile Instrumentation instrumentation;
    private static volatile ResettableClassFileTransformer launcherHook;

    private KrakenAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        net.bytebuddy.agent.Installer.premain(agentArgs, inst);
        instrumentation = inst;
        try {
            launcherHook = new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                            log.error("Failed to hook {}. The Kraken launcher will not start: ", typeName, throwable);
                        }
                    })
                    .type(named(RUNELITE_LAUNCHER_CLASS))
                    .transform((builder, type, classLoader, module, domain) -> builder.visit(
                            Advice.to(LauncherMainAdvice.class)
                                    .on(named("main").and(isStatic()).and(takesArguments(String[].class)))))
                    .installOn(inst);
        } catch (Throwable t) {
            log.error("Failed to hook RuneLite's launcher. RuneLite will start without Kraken: ", t);
        }
    }

    /**
     * @return True when this JVM was started with the launcher jar as -javaagent
     */
    static boolean isLoadedAsAgent() {
        return instrumentation != null;
    }

    /**
     * Removes the hook so no Kraken transformer stays registered for the rest of the JVM's life. When KrakenStartup runs
     * from the hook, RuneLite's launcher class is already transformed; when it runs from Launcher.main, the class has
     * not loaded yet and is left untouched. Does nothing when the launcher was not started as an agent (IDE runs).
     */
    static void removeLauncherHook() {
        ResettableClassFileTransformer hook = launcherHook;
        if (hook != null) {
            hook.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED);
            launcherHook = null;
        }
    }
}
