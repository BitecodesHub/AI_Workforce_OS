// @find: tests for upload naming, keep both, Contract (2).pdf, replace mode, upload mode, re-upload same file name, knowledge base documents
// @what: Checks how a kept-beside upload is named and which upload modes are accepted.
package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.platform.error.ApiException;

/** How an upload names a file kept beside one of the same name, and which modes it accepts. */
class UploadNamingTest {

    @Test
    @DisplayName("the number goes before the extension, so the file still reads as what it is")
    void numberBeforeExtension() {
        assertThat(IngestionService.numbered("Leave policy.pdf", 2)).isEqualTo("Leave policy (2).pdf");
        assertThat(IngestionService.numbered("archive.tar.gz", 3)).isEqualTo("archive.tar (3).gz");
    }

    @Test
    @DisplayName("a name with no extension, or only a leading dot, takes the number at the end")
    void noExtension() {
        assertThat(IngestionService.numbered("README", 2)).isEqualTo("README (2)");
        assertThat(IngestionService.numbered(".env", 2)).isEqualTo(".env (2)");
        assertThat(IngestionService.numbered("notes.", 2)).isEqualTo("notes. (2)");
    }

    @Test
    @DisplayName("no mode means replace; keep_both is accepted in any case; anything else is refused")
    void modes() {
        assertThat(IngestionService.UploadMode.fromWire(null)).isEqualTo(IngestionService.UploadMode.REPLACE);
        assertThat(IngestionService.UploadMode.fromWire("")).isEqualTo(IngestionService.UploadMode.REPLACE);
        assertThat(IngestionService.UploadMode.fromWire("replace")).isEqualTo(IngestionService.UploadMode.REPLACE);
        assertThat(IngestionService.UploadMode.fromWire("KEEP_BOTH")).isEqualTo(IngestionService.UploadMode.KEEP_BOTH);
        assertThatThrownBy(() -> IngestionService.UploadMode.fromWire("merge")).isInstanceOf(ApiException.class);
    }
}
