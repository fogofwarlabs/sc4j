package org.fogofwarlabs.sc4j.tools.windows;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets the active code page of Windows programs to UTF-8 in their application manifest: {@code WindowsManifestTool <program>...}.
 * <p>
 * A program with this setting, and the Java runtime it runs, reads file paths and arguments in UTF-8 instead of the system's ANSI code
 * page, so it also runs from a folder whose name has characters outside that code page. The manifest is the program's resource of type
 * {@code RT_MANIFEST} and id {@code 1}; a manifest that already names an active code page is left as it is. The resources are read and
 * written with the functions of {@code kernel32}, so the tool runs on Windows only, with native access enabled.
 */
public final class WindowsManifestTool {
    private static final @NotNull String UTF8_CODE_PAGE_SETTING = "<activeCodePage xmlns=\"http://schemas.microsoft.com/SMI/2019/WindowsSettings\">" +
                                                                  "UTF-8</activeCodePage>";
    private static final @NotNull String ACTIVE_CODE_PAGE_ELEMENT = "<activeCodePage";
    private static final @NotNull Pattern WINDOWS_SETTINGS_END = Pattern.compile("</(?:[A-Za-z0-9_]+:)?windowsSettings>");
    private static final @NotNull String ASSEMBLY_END = "</assembly>";
    private static final @NotNull String APPLICATION_START = "<application xmlns=\"urn:schemas-microsoft-com:asm.v3\"><windowsSettings>";
    private static final @NotNull String APPLICATION_END = "</windowsSettings></application>";
    private static final @NotNull String KERNEL_LIBRARY = "kernel32.dll";
    private static final long MANIFEST_TYPE = 24;
    private static final long MANIFEST_ID = 1;
    private static final int LOAD_LIBRARY_AS_DATAFILE = 0x2;
    private static final int KEEP_OTHER_RESOURCES = 0;
    private static final int WRITE_CHANGES = 0;
    private static final int DISCARD_CHANGES = 1;
    private static final int CONTINUE_ENUMERATION = 1;
    private static final int FAILED = 0;
    private static final int SUCCESS_EXIT = 0;
    private static final int FAILURE_EXIT = 1;
    private static final int USAGE_EXIT = 2;

    /**
     * Binds the resource functions of {@code kernel32} on first use, once.
     */
    private static final class ResourceFunctions {
        private static final @NotNull AddressLayout POINTER = ValueLayout.ADDRESS;
        private static final @NotNull ValueLayout.OfInt INTEGER = ValueLayout.JAVA_INT;
        private static final @NotNull ValueLayout.OfShort WORD = ValueLayout.JAVA_SHORT;
        private static final @NotNull ValueLayout.OfLong POINTER_VALUE = ValueLayout.JAVA_LONG;
        private static final @NotNull Linker LINKER = Linker.nativeLinker();
        @SuppressWarnings("restricted")
        private static final @NotNull SymbolLookup LIBRARY = SymbolLookup.libraryLookup(KERNEL_LIBRARY, Arena.global());

        private static final @NotNull MethodHandle LOAD_LIBRARY = bind("LoadLibraryExW", POINTER, POINTER, POINTER, INTEGER);
        private static final @NotNull MethodHandle FREE_LIBRARY = bind("FreeLibrary", INTEGER, POINTER);
        private static final @NotNull MethodHandle ENUMERATE_LANGUAGES = bind("EnumResourceLanguagesW", INTEGER, POINTER, POINTER, POINTER, POINTER,
                                                                              POINTER_VALUE);
        private static final @NotNull MethodHandle FIND_RESOURCE = bind("FindResourceExW", POINTER, POINTER, POINTER, POINTER, WORD);
        private static final @NotNull MethodHandle SIZE_OF_RESOURCE = bind("SizeofResource", INTEGER, POINTER, POINTER);
        private static final @NotNull MethodHandle LOAD_RESOURCE = bind("LoadResource", POINTER, POINTER, POINTER);
        private static final @NotNull MethodHandle LOCK_RESOURCE = bind("LockResource", POINTER, POINTER);
        private static final @NotNull MethodHandle BEGIN_UPDATE = bind("BeginUpdateResourceW", POINTER, POINTER, INTEGER);
        private static final @NotNull MethodHandle UPDATE_RESOURCE = bind("UpdateResourceW", INTEGER, POINTER, POINTER, POINTER, WORD, POINTER, INTEGER);
        private static final @NotNull MethodHandle END_UPDATE = bind("EndUpdateResourceW", INTEGER, POINTER, INTEGER);
        private static final @NotNull FunctionDescriptor LANGUAGE_CALLBACK = FunctionDescriptor.of(INTEGER, POINTER, POINTER, POINTER, WORD,
                                                                                                   POINTER_VALUE);

        @SuppressWarnings("restricted")
        private static @NotNull MethodHandle bind(@NotNull String name, @NotNull MemoryLayout result, @NotNull MemoryLayout @NotNull ... arguments) {
            return LINKER.downcallHandle(LIBRARY.find(name).orElseThrow(), FunctionDescriptor.of(result, arguments));
        }

        /**
         * Creates the callback of {@code EnumResourceLanguagesW} that adds each language to a list.
         *
         * @param languages receives the languages
         * @param arena     the lifetime of the callback
         * @return the callback
         * @throws ReflectiveOperationException if the collecting method cannot be found
         */
        @SuppressWarnings("restricted")
        private static @NotNull MemorySegment createLanguageCallback(@NotNull List<@NotNull Short> languages,
                                                                     @NotNull Arena arena) throws ReflectiveOperationException {
            MethodHandle collect = MethodHandles.lookup().findStatic(ResourceFunctions.class, "collectLanguage",
                                                                     MethodType.methodType(int.class, List.class, MemorySegment.class,
                                                                                           MemorySegment.class, MemorySegment.class, short.class,
                                                                                           long.class));

            return LINKER.upcallStub(MethodHandles.insertArguments(collect, 0, languages), LANGUAGE_CALLBACK, arena);
        }

        private static int collectLanguage(@NotNull List<@NotNull Short> languages, @NotNull MemorySegment module, @NotNull MemorySegment type,
                                           @NotNull MemorySegment name, short language, long parameter) {
            languages.add(language);

            return CONTINUE_ENUMERATION;
        }

        private ResourceFunctions() {
        }
    }

    /**
     * Holds the manifest of a program and the language of its resource.
     */
    private static final class ProgramManifest {
        private final @NotNull String text;
        private final short language;

        private ProgramManifest(@NotNull String text, short language) {
            this.text     = text;
            this.language = language;
        }

        private @NotNull String getText() {
            return this.text;
        }

        private short getLanguage() {
            return this.language;
        }
    }

    /**
     * Sets the UTF-8 active code page in the manifest of each program named on the command line and exits: with {@code 0} when every
     * program has the setting, {@code 1} when one failed, {@code 2} without a program.
     *
     * @param arguments the program files
     */
    public static void main(@NotNull String @NotNull [] arguments) {
        System.exit(run(arguments, System.out, System.err));
    }

    /**
     * Sets the UTF-8 active code page in the manifest of each program.
     *
     * @param arguments the program files
     * @param output    the stream of the programs changed
     * @param errors    the stream of the failure messages
     * @return {@code 0} if every program has the setting, {@code 1} if a program failed, {@code 2} if no program is named
     */
    public static int run(@NotNull String @NotNull [] arguments, @NotNull PrintStream output, @NotNull PrintStream errors) {
        if (arguments.length == 0) {
            errors.println("Usage: WindowsManifestTool <program>...");

            return USAGE_EXIT;
        }

        for (String argument : arguments) {
            Path program = Path.of(argument);

            try {
                if (setUtf8CodePage(program)) {
                    output.println("Set the UTF-8 code page of " + program + ".");
                }
            } catch (IOException exception) {
                errors.println("The manifest of " + program + " cannot be changed: " + exception.getMessage());

                return FAILURE_EXIT;
            }
        }

        return SUCCESS_EXIT;
    }

    /**
     * Adds the UTF-8 active code page setting to the {@code windowsSettings} element of a manifest, or a new {@code application} element with
     * that setting when the manifest has none.
     *
     * @param manifest the manifest
     * @return the manifest with the setting, or the manifest itself when it already names an active code page
     * @throws IllegalArgumentException if the manifest has no {@code assembly} end tag
     */
    public static @NotNull String addUtf8CodePage(@NotNull String manifest) {
        if (manifest.contains(ACTIVE_CODE_PAGE_ELEMENT)) {
            return manifest;
        }

        Matcher settingsEnd = WINDOWS_SETTINGS_END.matcher(manifest);

        if (settingsEnd.find()) {
            return manifest.substring(0, settingsEnd.start()) + UTF8_CODE_PAGE_SETTING + manifest.substring(settingsEnd.start());
        }

        int assemblyEnd = manifest.lastIndexOf(ASSEMBLY_END);

        if (assemblyEnd < 0) {
            throw Exceptions.newIllegalArgumentException("The manifest has no {0}.", ASSEMBLY_END);
        }

        return manifest.substring(0, assemblyEnd) + APPLICATION_START + UTF8_CODE_PAGE_SETTING + APPLICATION_END + manifest.substring(assemblyEnd);
    }

    /**
     * Reads the manifest of a program.
     *
     * @param program the program file
     * @return the manifest
     * @throws IOException if the program cannot be read or has not exactly one manifest
     */
    public static @NotNull String readManifest(@NotNull Path program) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            return readManifest(program, arena).getText();
        }
    }

    /**
     * Sets the UTF-8 active code page in the manifest of a program ({@link #addUtf8CodePage}), in place.
     *
     * @param program the program file
     * @return {@code true} if the manifest was changed, {@code false} if it already named an active code page
     * @throws IOException if the program cannot be read or written or has not exactly one manifest
     */
    public static boolean setUtf8CodePage(@NotNull Path program) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            ProgramManifest manifest = readManifest(program, arena);
            String text = addUtf8CodePage(manifest.getText());

            if (text.equals(manifest.getText())) {
                return false;
            }

            byte[] contents = text.getBytes(StandardCharsets.UTF_8);
            MemorySegment update = (MemorySegment) ResourceFunctions.BEGIN_UPDATE.invokeExact(createWideString(program, arena), KEEP_OTHER_RESOURCES);

            if (update.equals(MemorySegment.NULL)) {
                throw Exceptions.newIOException("{0} cannot be opened for writing.", program);
            }

            int updated = (int) ResourceFunctions.UPDATE_RESOURCE.invokeExact(update, MemorySegment.ofAddress(MANIFEST_TYPE),
                                                                              MemorySegment.ofAddress(MANIFEST_ID), manifest.getLanguage(),
                                                                              arena.allocateFrom(ValueLayout.JAVA_BYTE, contents), contents.length);
            int ended = (int) ResourceFunctions.END_UPDATE.invokeExact(update, updated == FAILED ? DISCARD_CHANGES : WRITE_CHANGES);

            if (updated == FAILED || ended == FAILED) {
                throw Exceptions.newIOException("The manifest of {0} cannot be written.", program);
            }

            return true;
        } catch (IOException | RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw Exceptions.newIOException(throwable, "The resource functions failed on {0}.", program);
        }
    }

    @SuppressWarnings("restricted")
    private static @NotNull ProgramManifest readManifest(@NotNull Path program, @NotNull Arena arena) throws IOException {
        try {
            MemorySegment module = (MemorySegment) ResourceFunctions.LOAD_LIBRARY.invokeExact(createWideString(program, arena), MemorySegment.NULL,
                                                                                              LOAD_LIBRARY_AS_DATAFILE);

            if (module.equals(MemorySegment.NULL)) {
                throw Exceptions.newIOException("{0} cannot be read as a program.", program);
            }

            try {
                MemorySegment type = MemorySegment.ofAddress(MANIFEST_TYPE);
                MemorySegment name = MemorySegment.ofAddress(MANIFEST_ID);
                List<Short> languages = new ArrayList<>();
                MemorySegment callback = ResourceFunctions.createLanguageCallback(languages, arena);
                int enumerated = (int) ResourceFunctions.ENUMERATE_LANGUAGES.invokeExact(module, type, name, callback, 0L);

                if (enumerated == FAILED || languages.size() != 1) {
                    throw Exceptions.newIOException("{0} has {1} manifests instead of one.", program, languages.size());
                }

                short language = languages.getFirst();
                MemorySegment resource = (MemorySegment) ResourceFunctions.FIND_RESOURCE.invokeExact(module, type, name, language);
                int size = (int) ResourceFunctions.SIZE_OF_RESOURCE.invokeExact(module, resource);
                MemorySegment loaded = (MemorySegment) ResourceFunctions.LOAD_RESOURCE.invokeExact(module, resource);
                MemorySegment data = (MemorySegment) ResourceFunctions.LOCK_RESOURCE.invokeExact(loaded);

                if (data.equals(MemorySegment.NULL)) {
                    throw Exceptions.newIOException("The manifest of {0} cannot be read.", program);
                }

                return new ProgramManifest(new String(data.reinterpret(size).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8), language);
            } finally {
                // The module only maps the file; a failed release leaves nothing to undo.
                int released = (int) ResourceFunctions.FREE_LIBRARY.invokeExact(module);
            }
        } catch (IOException | RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw Exceptions.newIOException(throwable, "The resource functions failed on {0}.", program);
        }
    }

    private static @NotNull MemorySegment createWideString(@NotNull Path program, @NotNull Arena arena) {
        return arena.allocateFrom(program.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
    }


    private WindowsManifestTool() {
    }
}
