package spike.fixture;

/** [Arcane Academy fork] Loaded-class identity for the game-free hot-swap tests. */
public class First {
    public static Class<?> hiddenType() { return ((Runnable) () -> { }).getClass(); }
}
