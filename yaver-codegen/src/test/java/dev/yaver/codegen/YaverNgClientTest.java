package dev.yaver.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;
import io.swagger.v3.parser.OpenAPIV3Parser;

/**
 * Tests for {@link YaverNgClient}: registration, option validation, operation
 * classification, vendor-extension validation, naming and deterministic output.
 */
public class YaverNgClientTest {

    private static final String FIXTURE = "src/test/resources/ng-client-fixture.yaml";

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    @Test
    public void registersUnderExactCliIdentifier() {
        assertEquals("yaver-ng-client", new YaverNgClient().getName());
    }

    @Test
    public void exposesCuratedOptions() {
        YaverNgClient codegen = new YaverNgClient();
        List<String> optionNames = codegen.cliOptionNames();
        assertTrue(optionNames.contains("npmName"));
        assertTrue(optionNames.contains("ngVersion"));
        assertTrue(optionNames.contains("clientPrefix"));
        // Angular 9-21 era options must not be advertised.
        assertFalse(optionNames.contains("providedIn"));
        assertFalse(optionNames.contains("queryParamObjectFormat"));
        assertFalse(optionNames.contains("zonejsVersion"));
    }

    // ------------------------------------------------------------------
    // Option validation
    // ------------------------------------------------------------------

    @Test
    public void rejectsNonAngular22Versions() {
        YaverNgClient codegen = new YaverNgClient();
        codegen.additionalProperties().put("ngVersion", "21.0.0");
        assertThrows(IllegalArgumentException.class, codegen::processOpts);

        YaverNgClient codegen2 = new YaverNgClient();
        codegen2.additionalProperties().put("ngVersion", "23.0.0");
        assertThrows(IllegalArgumentException.class, codegen2::processOpts);
    }

    @Test
    public void rejectsInvalidClientPrefix() {
        YaverNgClient codegen = new YaverNgClient();
        codegen.additionalProperties().put("clientPrefix", "1Bad-Prefix");
        assertThrows(IllegalArgumentException.class, codegen::processOpts);
    }

    @Test
    public void derivesConfigurationSymbolsFromClientPrefix() {
        YaverNgClient codegen = new YaverNgClient();
        codegen.additionalProperties().put("clientPrefix", "PairsAdmin");
        codegen.processOpts();
        assertEquals("PairsAdminClientConfig", codegen.additionalProperties().get("configurationClassName"));
        assertEquals("PAIRS_ADMIN_CLIENT_CONFIG", codegen.additionalProperties().get("configurationTokenName"));
        assertEquals("providePairsAdminClient", codegen.additionalProperties().get("providerFnName"));
    }

    // ------------------------------------------------------------------
    // Vendor extension validation (x-yaver-ng-mode)
    // ------------------------------------------------------------------

    @Test
    public void rejectsUnknownNgModeValue() {
        OpenAPISupport spec = new OpenAPISupport();
        spec.get("/things").getGet()
                .setExtensions(new HashMap<>(Map.of("x-yaver-ng-mode", "widget")));
        YaverNgClient codegen = new YaverNgClient();
        assertThrows(IllegalArgumentException.class, () -> codegen.preprocessOpenAPI(spec.openAPI()));
    }

    @Test
    public void rejectsQueryModeOnPostOperation() {
        OpenAPISupport spec = new OpenAPISupport();
        spec.post("/things", "createThing");
        spec.openAPI().getPaths().get("/things").getPost()
                .setExtensions(new HashMap<>(Map.of("x-yaver-ng-mode", "query")));
        YaverNgClient codegen = new YaverNgClient();
        // The extension value is syntactically valid; the unsafe classification
        // must fail during operation post-processing.
        assertThrows(RuntimeException.class,
                () -> generate(codegen, spec.openAPI(), tempDir().resolve("reject-query")));
    }

    @Test
    public void rejectsStreamModeWithoutEventStreamResponse() {
        OpenAPISupport spec = new OpenAPISupport();
        spec.get("/things").getGet()
                .setExtensions(new HashMap<>(Map.of("x-yaver-ng-mode", "stream")));
        YaverNgClient codegen = new YaverNgClient();
        assertThrows(RuntimeException.class,
                () -> generate(codegen, spec.openAPI(), tempDir().resolve("reject-stream")));
    }

    // ------------------------------------------------------------------
    // Operation classification (via full generation of the fixture)
    // ------------------------------------------------------------------

    @Test
    public void classifiesOperationsAndNamesStably() throws IOException {
        Path out = generateFixture();
        String api = Files.readString(out.resolve("src/api/tenants.api.ts"), StandardCharsets.UTF_8);

        // GET JSON -> httpResource query initializer.
        assertTrue(api.contains("export function injectListTenantsResource("));
        assertTrue(api.contains("HttpResourceRef<TenantListResponse | undefined>"));
        assertTrue(api.contains("assertInInjectionContext(injectListTenantsResource);"));
        assertTrue(api.contains("httpResource<TenantListResponse>"));

        // POST JSON -> Observable command.
        assertTrue(api.contains("createTenant(params: CreateTenantParams): Observable<TenantDetail>"));
        assertTrue(api.contains(
                "createTenantResponse(params: CreateTenantParams): Observable<HttpResponse<TenantDetail>>"));
        // Stable, documented body parameter naming.
        assertTrue(api.contains("readonly body:"), "params interface must expose `body`");
        assertTrue(api.contains("const body: unknown = params.body;"));

        // No query functions for commands, no command methods for queries.
        assertFalse(api.contains("injectCreateTenantResource"));
        assertFalse(api.contains("listTenants(params: ListTenantsParams): Observable<"));

        String reports = Files.readString(out.resolve("src/api/reports.api.ts"), StandardCharsets.UTF_8);
        // Binary download -> command with Blob + events variant.
        assertTrue(reports.contains("downloadReport(params: DownloadReportParams): Observable<Blob>"));
        assertTrue(reports.contains("downloadReportEvents(params: DownloadReportParams): Observable<HttpEvent<Blob>>"));
        // SSE -> stream API only (no plain body method).
        assertTrue(reports.contains("streamEventsEvents(params: StreamEventsParams): Observable<HttpEvent<string>>"));
        assertFalse(reports.contains("streamEvents(params: StreamEventsParams): Observable<string>"));
        // 204 command -> void.
        assertTrue(reports.contains("logAuditEvent(params: LogAuditEventParams): Observable<void>"));
        // Arbitrary JSON -> intentional typed representation, not `any`.
        assertTrue(reports.contains("Observable<{ [key: string]: unknown; }>"));
    }

    @Test
    public void generatesSignalFormsSchemas() throws IOException {
        Path out = generateFixture();
        String forms = Files.readString(out.resolve("forms/forms.ts"), StandardCharsets.UTF_8);
        assertTrue(forms.contains("export const createTenantRequestSchema = schema<CreateTenantRequest>((p) => {"));
        assertTrue(forms.contains("required(p.name);"));
        assertTrue(forms.contains("email(p.adminEmail);"));
        // Nullable/optional properties are guarded by applyWhenValue.
        assertTrue(forms.contains("applyWhenValue(p.homepage!, (v): v is NonNullable<string> => v != null"));
        assertTrue(forms.contains("applyEach(v, contactSchema);"));
        String meta = Files.readString(out.resolve("forms/form-meta.ts"), StandardCharsets.UTF_8);
        assertTrue(meta.contains("createTenantRequestFormMeta"));
        assertTrue(meta.contains("control: 'select'"));
    }

    @Test
    public void generatesTypedConfigurationProvider() throws IOException {
        Path out = generateFixture();
        String configuration = Files.readString(out.resolve("src/configuration.ts"), StandardCharsets.UTF_8);
        assertTrue(configuration.contains("readonly basePath: string;"));
        assertTrue(configuration.contains("export function provideYaverTestClient"));
        // The client must not touch the HTTP stack: no @angular/common import.
        assertFalse(configuration.contains("@angular/common"));
    }

    @Test
    public void generatedOutputContainsNoForbiddenPatterns() throws IOException {
        Path out = generateFixture();
        Files.walk(out)
                .filter(path -> path.toString().endsWith(".ts"))
                .forEach(path -> {
                    try {
                        String content = Files.readString(path, StandardCharsets.UTF_8);
                        assertFalse(content.contains("@ts-ignore"), "@ts-ignore in " + path);
                        assertFalse(content.contains("<any>"), "<any> in " + path);
                        assertFalse(content.contains("Observable<any>"), "Observable<any> in " + path);
                        assertFalse(content.contains("observe: any"), "observe: any in " + path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Test
    public void generationIsDeterministic() throws Exception {
        Path first = generateFixture();
        Path second = tempDir().resolve("lib2");
        YaverNgClient codegen = new YaverNgClient();
        codegen.setOutputDir(second.toString());
        codegen.additionalProperties().putAll(Map.of(
                "npmName", "@yaver/test",
                "npmVersion", "1.0.0",
                "clientPrefix", "YaverTest"));
        ClientOptInput input = new ClientOptInput()
                .openAPI(new OpenAPIV3Parser().read(FIXTURE))
                .config(codegen);
        new DefaultGenerator().opts(input).generate();

        assertEquals(treeDigest(first), treeDigest(second),
                "two generations of the same fixture must be identical");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Path tempDir() {
        try {
            return Files.createTempDirectory("yaver-ng-client-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path generateFixture() {
        return generate(null, null, tempDir().resolve("lib"));
    }

    private Path generate(YaverNgClient codegen, io.swagger.v3.oas.models.OpenAPI openAPI, Path outputDir) {
        YaverNgClient generator = codegen != null ? codegen : new YaverNgClient();
        generator.setOutputDir(outputDir.toString());
        generator.additionalProperties().putAll(Map.of(
                "npmName", "@yaver/test",
                "npmVersion", "1.0.0",
                "clientPrefix", "YaverTest"));
        io.swagger.v3.oas.models.OpenAPI spec = openAPI != null
                ? openAPI
                : new OpenAPIV3Parser().read(FIXTURE);
        ClientOptInput input = new ClientOptInput().openAPI(spec).config(generator);
        new DefaultGenerator().opts(input).generate();
        return outputDir.toAbsolutePath();
    }

    private String treeDigest(Path root) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("node_modules"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(root::relativize));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            md.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Small builder for in-memory OpenAPI specs used in validation tests. */
    private static final class OpenAPISupport {
        private final io.swagger.v3.oas.models.OpenAPI openAPI =
                new io.swagger.v3.oas.models.OpenAPI()
                        .openapi("3.0.3")
                        .paths(new io.swagger.v3.oas.models.Paths())
                        .info(new io.swagger.v3.oas.models.info.Info().title("t").version("1"));

        io.swagger.v3.oas.models.OpenAPI openAPI() {
            return openAPI;
        }

        io.swagger.v3.oas.models.PathItem get(String path) {
            io.swagger.v3.oas.models.PathItem item = new io.swagger.v3.oas.models.PathItem();
            io.swagger.v3.oas.models.Operation operation = new io.swagger.v3.oas.models.Operation()
                    .operationId("op" + path.replace("/", ""))
                    .responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                            .addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse()
                                    .description("ok")
                                    .content(new io.swagger.v3.oas.models.media.Content()
                                            .addMediaType("application/json",
                                                    new io.swagger.v3.oas.models.media.MediaType()
                                                            .schema(new io.swagger.v3.oas.models.media.ObjectSchema())))));
            item.setGet(operation);
            openAPI.getPaths().addPathItem(path, item);
            return item;
        }

        void post(String path, String operationId) {
            io.swagger.v3.oas.models.PathItem item =
                    openAPI.getPaths().getOrDefault(path, new io.swagger.v3.oas.models.PathItem());
            io.swagger.v3.oas.models.Operation operation = new io.swagger.v3.oas.models.Operation()
                    .operationId(operationId)
                    .responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                            .addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse()
                                    .description("ok")
                                    .content(new io.swagger.v3.oas.models.media.Content()
                                            .addMediaType("application/json",
                                                    new io.swagger.v3.oas.models.media.MediaType()
                                                            .schema(new io.swagger.v3.oas.models.media.ObjectSchema())))));
            item.setPost(operation);
            openAPI.getPaths().addPathItem(path, item);
        }
    }
}
