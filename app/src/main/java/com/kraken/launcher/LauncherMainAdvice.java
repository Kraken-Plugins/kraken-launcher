package com.kraken.launcher;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.bytebuddy.asm.Advice;

import java.util.function.UnaryOperator;

/**
 * Inlined at the start of RuneLite's net.runelite.launcher.Launcher.main by {@link KrakenAgent}. Hands RuneLite's
 * arguments to Kraken's startup, then continues into RuneLite's main with the arguments it returns, or skips RuneLite's
 * main when it returns null.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LauncherMainAdvice {

    static volatile UnaryOperator<String[]> handler = KrakenStartup::beforeRuneLite;

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.Argument(value = 0, readOnly = false) String[] args) {
        String[] replacement = handle(args);
        if (replacement == null) {
            return true;
        }
        args = replacement;
        return false;
    }

    /**
     * Called from the advice once it is inlined into RuneLite's launcher class, so it has to stay public.
     * @param args The arguments RuneLite's launcher main was called with
     * @return The arguments to continue with, or null to skip RuneLite's main
     */
    public static String[] handle(String[] args) {
        return handler.apply(args);
    }
}
