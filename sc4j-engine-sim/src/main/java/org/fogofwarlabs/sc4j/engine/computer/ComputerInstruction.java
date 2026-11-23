package org.fogofwarlabs.sc4j.engine.computer;

import org.jetbrains.annotations.NotNull;

/**
 * Executes one instruction of a computer script: the behavior of one opcode of a script format, which an interpreter finds by its opcode in
 * a {@link ComputerInstructionRegistry}. One stateless instance per opcode (an instance may serve several opcodes); the state of the running
 * script is in the context.
 * <p>
 * Determinism contract: an instruction draws random values only from the game's generator and allocates nothing.
 *
 * @param <C> the context type of the interpreter
 */
@FunctionalInterface
public interface ComputerInstruction<C extends ComputerScriptContext> {
    /**
     * Executes the instruction for the running script of the context.
     *
     * @param context the context of the running script
     * @return {@code true} if the script continues with its next instruction, {@code false} if it stops for this frame (a wait, an end or a retry later)
     */
    boolean execute(@NotNull C context);
}
