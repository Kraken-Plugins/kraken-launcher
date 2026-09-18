package com.kraken.launcher;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.function.UnaryOperator;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class LauncherMainAdviceTest {

    private UnaryOperator<String[]> originalHandler;
    private Class<?> advised;

    /**
     * Applies the advice to a renamed copy of FakeRuneLiteLauncher, the same way KrakenAgent applies it to RuneLite's
     * launcher. ByteBuddy remaps the copy's self-references, so it records into its own static field.
     */
    @Before
    public void adviseFakeLauncher() {
        originalHandler = LauncherMainAdvice.handler;
        advised = new ByteBuddy()
                .redefine(FakeRuneLiteLauncher.class)
                .name(FakeRuneLiteLauncher.class.getName() + "Advised")
                .visit(Advice.to(LauncherMainAdvice.class).on(named("main")))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
    }

    @After
    public void restoreHandler() {
        LauncherMainAdvice.handler = originalHandler;
    }

    @Test
    public void runsMainWithTheArgumentsTheStartupReturns() throws Exception {
        LauncherMainAdvice.handler = args -> new String[]{"--launch-mode", "FORK"};

        advised.getMethod("main", String[].class).invoke(null, (Object) new String[]{"--qa"});

        assertArrayEquals(new String[]{"--launch-mode", "FORK"}, received());
    }

    @Test
    public void skipsMainWhenTheStartupReturnsNull() throws Exception {
        LauncherMainAdvice.handler = args -> null;

        advised.getMethod("main", String[].class).invoke(null, (Object) new String[]{"--qa"});

        assertNull(received());
    }

    private String[] received() throws Exception {
        return (String[]) advised.getField("received").get(null);
    }
}
