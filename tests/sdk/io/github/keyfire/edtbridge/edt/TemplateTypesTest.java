package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.htmldocument.model.HtmlDocument;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

class TemplateTypesTest {
    @Test void htmlAndTextTemplatesAreNamedInEnglishAndInRussian() {
        for (String name : new String[] {"HTMLDocument", "html", "HTMLДокумент", "HTML_DOCUMENT"}) {
            assertSame(TemplateType.HTML_DOCUMENT, TemplateWriteGateway.templateType(name), name);
        }
        for (String name : new String[] {"TextDocument", "text", "txt", "ТекстовыйДокумент", "TEXT_DOCUMENT"}) {
            assertSame(TemplateType.TEXT_DOCUMENT, TemplateWriteGateway.templateType(name), name);
        }
    }

    @Test void theEarlierTypesKeepTheirNamesAndTheDefault() {
        assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, TemplateWriteGateway.templateType(null));
        assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, TemplateWriteGateway.templateType("СхемаКомпоновкиДанных"));
        assertSame(TemplateType.SPREADSHEET_DOCUMENT, TemplateWriteGateway.templateType("ТабличныйДокумент"));
        assertNull(TemplateWriteGateway.templateType("BinaryData"));
        assertNull(TemplateWriteGateway.templateType("ДвоичныеДанные"));
    }

    @Test void aNewHtmlDocumentHasOnePageInTheGivenLanguage() {
        HtmlDocument document = TemplateWriteGateway.htmlDocument("ru");
        assertEquals(1, document.getPages().size());
        assertEquals("ru", document.getPages().get(0).getLang());
    }
}
