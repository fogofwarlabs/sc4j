package org.fogofwarlabs.sc4j.catalog.github;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the typed repository names, {@code owner/name} and the repository's web address, and repositories created from owner and name.
 */
final class ReleaseRepositoryTest {
    @ParameterizedTest
    @ValueSource(strings = {"owner/my-mod", " owner/my-mod ", "owner/my-mod/", "owner/my-mod.git", "https://github.com/owner/my-mod",
                            "http://www.github.com/owner/my-mod/", "github.com/owner/my-mod", "https://github.com/owner/my-mod/releases/latest",
                            "https://GitHub.com/owner/my-mod.git"})
    void parse_should_read_a_name_or_an_address(@NotNull String text) {
        ReleaseRepository repository = ReleaseRepository.parse(text);

        Assertions.assertNotNull(repository, text);
        Assertions.assertEquals("owner", repository.getOwner());
        Assertions.assertEquals("my-mod", repository.getName());
        Assertions.assertEquals("owner/my-mod", repository.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "owner", "owner/", "/my-mod", "owner/my mod", "owner/my-mod/extra", "-owner/my-mod", "owner/..", "owner/.",
                            "https://example.com/owner/my-mod", "https://github.com/owner", "../owner/my-mod"})
    void parse_should_refuse_text_that_names_no_repository(@NotNull String text) {
        Assertions.assertNull(ReleaseRepository.parse(text), text);
    }

    @Test
    void of_should_create_a_repository_and_refuse_an_invalid_one() {
        Assertions.assertEquals("fogofwarlabs/sc4j", ReleaseRepository.of("fogofwarlabs", "sc4j").toString());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReleaseRepository.of("owner", "my mod"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReleaseRepository.of("owner/extra", "my-mod"));
    }
}
