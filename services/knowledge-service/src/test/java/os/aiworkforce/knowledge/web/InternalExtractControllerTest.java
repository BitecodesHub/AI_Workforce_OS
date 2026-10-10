// @find: tests for attachment text extraction, chat attachments, PDF docx xlsx pptx csv extract, POST /internal/knowledge/extract, text extractor
// @what: Checks chat attachment text is read correctly for each file format.
package os.aiworkforce.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import os.aiworkforce.knowledge.service.TextExtractor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Reading a chat attachment's text, one small generated file per format. Each fixture carries a
 * phrase found nowhere else, so a test passes only when that file's own text came back.
 */
class InternalExtractControllerTest {

    private final TextExtractor extractor = new TextExtractor(TextExtractor.DEFAULT_MAX_CHARS);

    private InternalExtractController.Extracted read(byte[] bytes, String name) {
        return InternalExtractController.extract(bytes, name, null, extractor);
    }

    @Test
    @DisplayName("a PDF is detected from its bytes and read, with its page count")
    void pdf() throws IOException {
        InternalExtractController.Extracted found = read(pdf("Quarterly revenue rose by eleven percent across the region this year."), "q3.pdf");
        assertThat(found.mediaType()).isEqualTo("application/pdf");
        assertThat(found.text()).contains("eleven percent");
        assertThat(found.pageCount()).isEqualTo(1);
        assertThat(found.problem()).isNull();
    }

    @Test
    @DisplayName("a Word document is read")
    void docx() throws IOException {
        XWPFDocument document = new XWPFDocument();
        document.createParagraph().createRun().setText("The tenancy notice period is twenty one days.");
        InternalExtractController.Extracted found = read(bytes(document::write), "lease.docx");
        assertThat(found.mediaType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(found.text()).contains("twenty one days");
    }

    @Test
    @DisplayName("a PowerPoint deck is read slide by slide")
    void pptx() throws IOException {
        XMLSlideShow show = new XMLSlideShow();
        XSLFSlide slide = show.createSlide();
        XSLFTextBox box = slide.createTextBox();
        box.setText("Launch window opens in the second week of March.");
        InternalExtractController.Extracted found = read(bytes(show::write), "plan.pptx");
        assertThat(found.mediaType()).isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(found.text()).contains("second week of March");
    }

    @Test
    @DisplayName("an Excel workbook, new and old format, is read cell by cell")
    void spreadsheets() throws IOException {
        InternalExtractController.Extracted xlsx = read(workbook(new XSSFWorkbook(), "Blue widgets"), "stock.xlsx");
        assertThat(xlsx.mediaType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(xlsx.text()).contains("Blue widgets").contains("42");

        InternalExtractController.Extracted xls = read(workbook(new HSSFWorkbook(), "Green gadgets"), "old.xls");
        assertThat(xls.mediaType()).isEqualTo("application/vnd.ms-excel");
        assertThat(xls.text()).contains("Green gadgets");
    }

    @Test
    @DisplayName("CSV, plain text, Markdown, JSON and HTML are read as text")
    void textFormats() {
        assertThat(read(utf8("item,qty\nred pencils,12\n"), "orders.csv").text()).contains("red pencils");
        assertThat(read(utf8("The gate code changes every Monday."), "notes.txt").text()).contains("gate code");
        assertThat(read(utf8("# Onboarding\n\nBring photo identification."), "readme.md").text())
                .contains("photo identification");
        assertThat(read(utf8("{\"customer\":\"Acme\",\"tier\":\"gold\"}"), "account.json").text()).contains("gold");
        InternalExtractController.Extracted html =
                read(utf8("<html><body><p>Office closes at five thirty.</p></body></html>"), "hours.html");
        assertThat(html.mediaType()).isEqualTo("text/html");
        assertThat(html.text()).contains("five thirty").doesNotContain("<p>");
    }

    @Test
    @DisplayName("a picture is detected as an image and says it has no text layer")
    void image() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        // Named as a PDF: the bytes decide what it is, not the name.
        InternalExtractController.Extracted found = read(out.toByteArray(), "not-really.pdf");
        assertThat(found.mediaType()).isEqualTo("image/png");
        assertThat(found.text()).isEmpty();
        assertThat(found.problem()).contains("image");
    }

    @Test
    @DisplayName("a program renamed as a document is still detected as what it is")
    void sniffsContentNotName() {
        byte[] program = new byte[256];
        program[0] = 'M';
        program[1] = 'Z';
        InternalExtractController.Extracted found = read(program, "invoice.pdf");
        assertThat(found.mediaType()).isNotEqualTo("application/pdf");
        assertThat(found.text()).isEmpty();
    }

    @Test
    @DisplayName("text is cut at the limit the caller asks for, and says so")
    void cutAtLimit() {
        InternalExtractController.Extracted found =
                InternalExtractController.extract(utf8("word ".repeat(2_000)), "long.txt", 100, extractor);
        assertThat(found.text()).hasSize(100);
        assertThat(found.truncated()).isTrue();
    }

    @Test
    @DisplayName("a person's own token is refused; a service token is served")
    void serviceOnly() {
        InternalExtractController controller = new InternalExtractController(extractor);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", utf8("Hello there, team."));
        Actor person = Actor.user(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, Set.of(), 1L);
        assertThatThrownBy(() -> RequestContext.as(person, () -> controller.extract(file, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(RequestContext.as(Actor.SYSTEM, () -> controller.extract(file, null)).text()).contains("Hello there");
    }

    // ---- Fixtures ----------------------------------------------------------------------------

    private interface Writer {
        void write(java.io.OutputStream out) throws IOException;
    }

    private static byte[] bytes(Writer writer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out);
        return out.toByteArray();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] workbook(Workbook workbook, String label) throws IOException {
        Sheet sheet = workbook.createSheet("Stock");
        Row row = sheet.createRow(0);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(42);
        try (workbook) {
            return bytes(workbook::write);
        }
    }

    private static byte[] pdf(String line) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(50, 700);
                content.showText(line);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
