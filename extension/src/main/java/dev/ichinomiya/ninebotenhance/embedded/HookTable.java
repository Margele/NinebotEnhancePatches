package dev.ichinomiya.ninebotenhance.embedded;

/**
 * Replaced by the patch: one line per wrapped method or constructor, written as the dex method descriptor
 * ({@code Lpkg/Type;->name(params)return}); the line number is the slot its trampoline reads.
 */
public final class HookTable {
    // Not final: javac would copy a constant into its readers, and the patch replaces this class after compilation.
    public static String TABLE = "";
    private HookTable() {}
}
