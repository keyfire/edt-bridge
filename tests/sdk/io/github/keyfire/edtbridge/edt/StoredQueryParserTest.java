package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.antlr.runtime.ANTLRStringStream;
import org.eclipse.xtext.XtextStandaloneSetup;
import org.eclipse.xtext.nodemodel.impl.NodeModelBuilder;
import org.eclipse.xtext.parser.DefaultEcoreElementFactory;
import org.eclipse.xtext.parser.antlr.AbstractInternalAntlrParser;
import org.eclipse.xtext.parser.antlr.IUnorderedGroupHelper;
import org.eclipse.xtext.parser.antlr.SyntaxErrorMessageProvider;
import org.eclipse.xtext.parser.antlr.XtextTokenStream;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.eclipse.xtext.service.GrammarProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.google.inject.Injector;
import com._1c.g5.v8.dt.ql.model.QlPackage;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTable;
import com._1c.g5.v8.dt.ql.services.QlGrammarAccess;
import com._1c.g5.v8.dt.ql.parser.antlr.internal.InternalQlParser;
import com._1c.g5.v8.dt.ql.parser.antlr.lexer.InternalQlLexer;
import com._1c.g5.v8.dt.ql.dcs.model.QlDcsPackage;
import com._1c.g5.v8.dt.ql.dcs.services.QlDcsGrammarAccess;
import com._1c.g5.v8.dt.ql.dcs.parser.antlr.internal.InternalQlDcsParser;
import com._1c.g5.v8.dt.ql.dcs.parser.antlr.lexer.InternalQlDcsLexer;

/** Real EDT grammars and ASTs, with standalone Xtext infrastructure instead of OSGi services. */
class StoredQueryParserTest {
    private static Injector infrastructure;

    @BeforeAll
    static void setup() {
        infrastructure = new XtextStandaloneSetup().createInjectorAndDoEMFRegistration();
        QlPackage.eINSTANCE.getNsURI();
        QlDcsPackage.eINSTANCE.getNsURI();
    }

    @Test
    void ordinaryQueryStillFindsItsSource() throws Exception {
        assertEquals(List.of("Catalog.Items"), tables("SELECT T.Ref FROM Catalog.Items AS T"));
    }

    @Test
    void dynamicListWithCompositionWhereFindsItsSource() throws Exception {
        assertEquals(List.of("InformationRegister.Pending"), tables(
                "SELECT T.Ref FROM InformationRegister.Pending AS T {WHERE T.Ref = &Ref}"));
    }

    @Test
    void dynamicListWithCompositionJoinFindsBothSources() throws Exception {
        assertEquals(List.of("Catalog.Items", "InformationRegister.Pending"), tables(
                "SELECT T.Ref FROM Catalog.Items AS T "
                + "{LEFT JOIN InformationRegister.Pending AS P ON T.Ref = P.Ref}"));
    }

    @Test
    void russianCompositionSyntaxFindsItsSource() throws Exception {
        assertEquals(List.of("РегистрСведений.Ожидающие"), tables(
                "ВЫБРАТЬ Т.Ссылка ИЗ РегистрСведений.Ожидающие КАК Т {ГДЕ Т.Ссылка = &Ссылка}"));
    }

    @Test
    void stringsAndCommentsDoNotBecomeTableSources() throws Exception {
        assertEquals(List.of("Catalog.Items"), tables(
                "SELECT \"{WHERE InformationRegister.Pending}\" AS Note FROM Catalog.Items AS T "
                + "// InformationRegister.Pending"));
    }

    @Test
    void genuinelyInvalidQueryRemainsAFailure() {
        assertThrows(QueryUsageGateway.QuerySyntaxException.class, () -> tables("SELECT FROM"));
    }

    private static List<String> tables(String text) throws Exception {
        return QueryUsageGateway.parseQuery("dynamicList", dcs -> parse(text, dcs));
    }

    private static GrammarProvider grammar(String language) {
        GrammarProvider provider = new GrammarProvider(language,
                () -> infrastructure.getInstance(XtextResourceSet.class));
        provider.setClassLoader(StoredQueryParserTest.class.getClassLoader());
        return provider;
    }

    private static List<String> parse(String text, boolean dcs) throws Exception {
        GrammarProvider provider = grammar(dcs ? "com._1c.g5.v8.dt.ql.dcs.QlDcs" : "com._1c.g5.v8.dt.ql.Ql");
        QlGrammarAccess ql = new QlGrammarAccess(provider);
        QlDcsGrammarAccess composition = dcs ? new QlDcsGrammarAccess(provider, ql) : null;
        var names = new HashMap<Integer, String>();
        String[] tokenNames = dcs ? InternalQlDcsParser.tokenNames : InternalQlParser.tokenNames;
        for (int index = 0; index < tokenNames.length; index++) names.put(index, tokenNames[index]);
        var input = new ANTLRStringStream(text);
        var lexer = dcs ? new InternalQlDcsLexer(input) : new InternalQlLexer(input);
        XtextTokenStream tokens = new XtextTokenStream(lexer, () -> names);
        tokens.setInitialHiddenTokens(names.values().stream()
                .filter(name -> name.startsWith("RULE_") && (name.equals("RULE_WS") || name.contains("COMMENT")))
                .toArray(String[]::new));
        AbstractInternalAntlrParser parser = dcs
                ? new InternalQlDcsParser(tokens, composition)
                : new InternalQlParser(tokens, ql);
        parser.setSemanticModelBuilder(infrastructure.getInstance(DefaultEcoreElementFactory.class));
        parser.setNodeModelBuilder(new NodeModelBuilder());
        parser.setSyntaxErrorProvider(new SyntaxErrorMessageProvider());
        parser.setTokenTypeMap(names);
        IUnorderedGroupHelper groups = new org.eclipse.xtext.parser.antlr.UnorderedGroupHelper(
                new org.eclipse.xtext.parser.antlr.UnorderedGroupHelper.Collector(dcs ? composition : ql));
        parser.setUnorderedGroupHelper(groups);
        groups.initializeWith(parser);
        var result = parser.parse();
        if (result.hasSyntaxErrors()) throw new QueryUsageGateway.QuerySyntaxException("invalid stored query");
        List<String> tables = new ArrayList<>();
        for (var iterator = result.getRootASTElement().eAllContents(); iterator.hasNext();) {
            if (iterator.next() instanceof QuerySchemaTable source && source.getTable() != null) {
                tables.add(source.getTable().getFullTableName());
            }
        }
        return tables.stream().sorted().toList();
    }
}
