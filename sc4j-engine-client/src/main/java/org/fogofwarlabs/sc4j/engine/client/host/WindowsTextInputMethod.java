package org.fogofwarlabs.sc4j.engine.client.host;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;

/**
 * Binds a GLFW window to the Windows input method (IMM) through the foreign function API: the engine default {@link TextInputMethod} on
 * Windows. The window's input context is taken away at creation and kept; enabling text input associates it with the window again. A window
 * procedure installed in front of GLFW's own takes the composition messages while text input is enabled: it hides the system composition
 * window and reports the result and the composition to the {@link TextCompositionListener}.
 */
@ApiStatus.Internal
public final class WindowsTextInputMethod implements TextInputMethod {
    private static final int WM_IME_STARTCOMPOSITION = 0x010D;
    private static final int WM_IME_ENDCOMPOSITION = 0x010E;
    private static final int WM_IME_COMPOSITION = 0x010F;
    private static final int GCS_COMPSTR = 0x0008;
    private static final int GCS_COMPATTR = 0x0010;
    private static final int GCS_COMPCLAUSE = 0x0020;
    private static final int GCS_CURSORPOS = 0x0080;
    private static final int GCS_RESULTSTR = 0x0800;
    private static final int GWLP_WNDPROC = -4;
    /**
     * {@code IME_CMODE_NATIVE}: bit {@code 0} of the conversion status.
     */
    private static final int NATIVE_CONVERSION = 1;
    /**
     * {@code ATTR_INPUT}: the attribute of unconverted input.
     */
    private static final int ATTR_INPUT = 0;
    /**
     * The input-method notification selecting composition-string operations ({@code NI_COMPOSITIONSTR}) and its cancel action
     * ({@code CPS_CANCEL}).
     */
    private static final int NI_COMPOSITIONSTR = 0x15;
    private static final int CPS_CANCEL = 4;
    /**
     * The language identifier in the low word of a keyboard layout handle.
     */
    private static final long LANGUAGE_IDENTIFIER_MASK = 0xFFFF;
    /**
     * The longest locale name {@code LCIDToLocaleName} writes, in UTF-16 characters with the terminating zero
     * ({@code LOCALE_NAME_MAX_LENGTH}).
     */
    private static final int LOCALE_NAME_MAXIMUM_LENGTH = 85;
    private static final int NO_FLAGS = 0;
    /**
     * The thread identifier that selects the calling thread ({@code GetKeyboardLayout}).
     */
    private static final int CURRENT_THREAD = 0;

    private static final @NotNull String INPUT_METHOD_LIBRARY = "imm32.dll";
    private static final @NotNull String USER_INTERFACE_LIBRARY = "user32.dll";
    private static final @NotNull String KERNEL_LIBRARY = "kernel32.dll";

    /**
     * Binds {@code LCIDToLocaleName} on first use, once.
     */
    private static final class LocaleNameFunction {
        private static final @Nullable MethodHandle HANDLE = bind();

        @SuppressWarnings("restricted")
        private static @Nullable MethodHandle bind() {
            try {
                MemorySegment function = SymbolLookup.libraryLookup(KERNEL_LIBRARY, Arena.global()).find("LCIDToLocaleName").orElse(null);

                return function == null ? null : Linker.nativeLinker().downcallHandle(
                        function,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                              ValueLayout.JAVA_INT)
                );
            } catch (IllegalArgumentException | IllegalCallerException exception) {
                return null;
            }
        }

        private LocaleNameFunction() {
        }
    }

    /**
     * Returns the locale of a Windows language identifier ({@code LCIDToLocaleName}).
     *
     * @param languageIdentifier the language identifier, for example {@code 0x0412} for Korean
     * @return the locale, or {@code null} if Windows names no locale by the identifier or the function is unavailable
     */
    static @Nullable Locale findLocale(int languageIdentifier) {
        MethodHandle localeName = LocaleNameFunction.HANDLE;

        if (localeName == null) {
            return null;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(ValueLayout.JAVA_CHAR, LOCALE_NAME_MAXIMUM_LENGTH);
            int length = (int) localeName.invokeExact(languageIdentifier, name, LOCALE_NAME_MAXIMUM_LENGTH, NO_FLAGS);

            if (length <= 1) {
                return null;
            }

            char[] characters = new char[length - 1];

            for (int index = 0; index < characters.length; index++) {
                characters[index] = name.getAtIndex(ValueLayout.JAVA_CHAR, index);
            }

            return Locale.forLanguageTag(new String(characters));
        } catch (Throwable exception) {
            return null;
        }
    }

    private final @NotNull MethodHandle associateContext;
    private final @NotNull MethodHandle getConversionStatus;
    private final @NotNull MethodHandle getCompositionString;
    private final @NotNull MethodHandle notifyInputMethod;
    private final @NotNull MethodHandle getKeyboardLayout;
    private final @NotNull MethodHandle callWindowProcedure;

    private final @NotNull MemorySegment window;
    private final @NotNull MemorySegment context;
    private final @NotNull MemorySegment previousProcedure;

    private @Nullable TextCompositionListener listener;
    private boolean enabled;

    /**
     * Creates a {@link WindowsTextInputMethod} instance: binds the system functions, takes the window's input context away and installs the
     * window procedure. The client starts with native access enabled for the bindings.
     *
     * @param window the GLFW window
     * @throws Throwable if a system function cannot be bound or called
     */
    @SuppressWarnings("restricted")
    WindowsTextInputMethod(@NotNull GlfwHostWindow window) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup inputMethodLibrary = SymbolLookup.libraryLookup(INPUT_METHOD_LIBRARY, Arena.global());
        SymbolLookup userInterfaceLibrary = SymbolLookup.libraryLookup(USER_INTERFACE_LIBRARY, Arena.global());

        this.associateContext     = linker.downcallHandle(inputMethodLibrary.find("ImmAssociateContext").orElseThrow(),
                                                          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.getConversionStatus  = linker.downcallHandle(inputMethodLibrary.find("ImmGetConversionStatus").orElseThrow(),
                                                          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                                                                ValueLayout.ADDRESS));
        this.getCompositionString = linker.downcallHandle(inputMethodLibrary.find("ImmGetCompositionStringW").orElseThrow(),
                                                          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                                                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.notifyInputMethod    = linker.downcallHandle(inputMethodLibrary.find("ImmNotifyIME").orElseThrow(),
                                                          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                                                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.getKeyboardLayout    = linker.downcallHandle(userInterfaceLibrary.find("GetKeyboardLayout").orElseThrow(),
                                                          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.callWindowProcedure  = linker.downcallHandle(userInterfaceLibrary.find("CallWindowProcW").orElseThrow(),
                                                          FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                                                                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

        MethodHandle setWindowLong = linker.downcallHandle(userInterfaceLibrary.find("SetWindowLongPtrW").orElseThrow(),
                                                           FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                                                                 ValueLayout.JAVA_LONG));

        this.window = MemorySegment.ofAddress(GLFWNativeWin32.glfwGetWin32Window(window.getHandle()));
        // The window's own context is taken away and kept.
        this.context = (MemorySegment) this.associateContext.invokeExact(this.window, MemorySegment.NULL);

        MethodHandle procedure = MethodHandles.lookup().findVirtual(WindowsTextInputMethod.class, "windowProcedure",
                                                                    MethodType.methodType(long.class, MemorySegment.class, int.class, long.class,
                                                                                          long.class)).bindTo(this);
        MemorySegment stub = linker.upcallStub(procedure,
                                               FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                                                     ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                                               Arena.global());

        this.previousProcedure = MemorySegment.ofAddress((long) setWindowLong.invokeExact(this.window, GWLP_WNDPROC, stub.address()));

        this.listener = null;
        this.enabled  = false;
    }

    /**
     * @return {@code true} if text input is enabled (the input method is attached to the window), {@code false} otherwise
     */
    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Enables or disables text input: attaches the input method to the window or detaches it. When the platform refuses the change, the state
     * stays as it was ({@link #isEnabled}).
     *
     * @param enabled whether an edit control takes text
     */
    @Override
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }

        MemorySegment associated = enabled ? this.context : MemorySegment.NULL;

        try {
            MemorySegment ignored = (MemorySegment) this.associateContext.invokeExact(this.window, associated);
        } catch (Throwable exception) {
            return;
        }

        this.enabled = enabled;
    }

    /**
     * Sets the receiver of the composition events.
     *
     * @param listener the receiver, or {@code null} to drop the events
     */
    @Override
    public void setListener(@Nullable TextCompositionListener listener) {
        this.listener = listener;
    }

    /**
     * Returns the language the input method composes text in: the locale of the window's keyboard layout while text input is enabled and the
     * input method converts to the language's own characters.
     *
     * @return the locale, or {@code null} while text input is disabled, in alphanumeric mode or without input method
     */
    @Override
    public @Nullable Locale getCompositionLocale() {
        if (!this.enabled) {
            return null;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment conversion = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment sentence = arena.allocate(ValueLayout.JAVA_INT);
            int ignored = (int) this.getConversionStatus.invokeExact(this.context, conversion, sentence);

            if ((conversion.get(ValueLayout.JAVA_INT, 0) & NATIVE_CONVERSION) == 0) {
                return null;
            }

            long layout = ((MemorySegment) this.getKeyboardLayout.invokeExact(CURRENT_THREAD)).address();

            return findLocale((int) (layout & LANGUAGE_IDENTIFIER_MASK));
        } catch (Throwable exception) {
            return null;
        }
    }


    /**
     * Cancels the composition in progress, for example after the edit control refused one of its characters.
     */
    @Override
    public void cancelComposition() {
        try {
            int ignored = (int) this.notifyInputMethod.invokeExact(this.context, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
        } catch (Throwable exception) {
            return;
        }
    }

    @SuppressWarnings("unused")
    private long windowProcedure(@NotNull MemorySegment windowHandle, int message, long messageWordParameter, long messageLongParameter) {
        if (this.enabled) {
            // The client shows the composition in its edit control, not in the system composition window.
            if (message == WM_IME_STARTCOMPOSITION || message == WM_IME_ENDCOMPOSITION) {
                return 0;
            }

            if (message == WM_IME_COMPOSITION) {
                this.reportComposition(messageLongParameter);

                return 0;
            }
        }

        try {
            return (long) this.callWindowProcedure.invokeExact(this.previousProcedure, windowHandle, message, messageWordParameter,
                                                               messageLongParameter);
        } catch (Throwable exception) {
            return 0;
        }
    }

    /**
     * Reports one composition message: the result first, then the new composition. A failure reports a canceled composition.
     *
     * @param changes the composition parts the message carries ({@code GCS_*} bits)
     */
    private void reportComposition(long changes) {
        TextCompositionListener current = this.listener;

        if (current == null) {
            return;
        }

        try {
            if ((changes & GCS_RESULTSTR) != 0) {
                current.onCompositionCommitted(this.readString(GCS_RESULTSTR));
            }

            if ((changes & GCS_COMPSTR) != 0) {
                current.onCompositionChanged(this.readComposition());
            }
        } catch (Throwable exception) {
            try {
                current.onCompositionCanceled();
            } catch (RuntimeException ignored) {
                // An upcall must not throw into the system's window procedure.
            }
        }
    }

    /**
     * Reads the composition string, its cursor and its target clause: the first clause of unconverted input, or the clause holding the cursor.
     *
     * @return the composition
     * @throws Throwable if a system call fails
     */
    private @NotNull TextComposition readComposition() throws Throwable {
        String text = this.readString(GCS_COMPSTR);
        int length = text.length();

        if (length == 0) {
            return new TextComposition(text, 0, 0, 0);
        }

        int cursor = (int) this.getCompositionString.invokeExact(this.context, GCS_CURSORPOS, MemorySegment.NULL, 0);
        int clampedCursor = Math.clamp(cursor, 0, length);

        try (Arena arena = Arena.ofConfined()) {
            int clauseBytes = (int) this.getCompositionString.invokeExact(this.context, GCS_COMPCLAUSE, MemorySegment.NULL, 0);

            if (clauseBytes <= Integer.BYTES) {
                return new TextComposition(text, 0, 0, clampedCursor);
            }

            MemorySegment clauses = arena.allocate(clauseBytes);

            clauseBytes = (int) this.getCompositionString.invokeExact(this.context, GCS_COMPCLAUSE, clauses, clauseBytes);

            int count = clauseBytes / Integer.BYTES;
            int chosen = 0;

            for (int index = 0; index < count - 1; index++) {
                if (clauses.getAtIndex(ValueLayout.JAVA_INT, index) <= cursor && cursor < clauses.getAtIndex(ValueLayout.JAVA_INT, index + 1)) {
                    chosen = index;

                    break;
                }
            }

            MemorySegment attributes = arena.allocate(length);
            int attributeBytes = (int) this.getCompositionString.invokeExact(this.context, GCS_COMPATTR, attributes, (int) attributes.byteSize());

            if (attributeBytes > 0) {
                for (int index = 0; index < count - 1; index++) {
                    int offset = clauses.getAtIndex(ValueLayout.JAVA_INT, index);

                    if (offset < attributeBytes && attributes.get(ValueLayout.JAVA_BYTE, offset) == ATTR_INPUT) {
                        chosen = index;

                        break;
                    }
                }
            }

            int clauseStart = Math.min(length, clauses.getAtIndex(ValueLayout.JAVA_INT, chosen));
            int clauseEnd = Math.min(length, clauses.getAtIndex(ValueLayout.JAVA_INT, chosen + 1));

            return new TextComposition(text, clauseStart, Math.max(clauseStart, clauseEnd), clampedCursor);
        }
    }

    private @NotNull String readString(int index) throws Throwable {
        int bytes = (int) this.getCompositionString.invokeExact(this.context, index, MemorySegment.NULL, 0);

        if (bytes <= 0) {
            return "";
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(bytes);

            bytes = (int) this.getCompositionString.invokeExact(this.context, index, buffer, bytes);

            char[] characters = new char[Math.max(0, bytes) / Character.BYTES];

            for (int character = 0; character < characters.length; character++) {
                characters[character] = buffer.getAtIndex(ValueLayout.JAVA_CHAR, character);
            }

            return new String(characters);
        }
    }
}
