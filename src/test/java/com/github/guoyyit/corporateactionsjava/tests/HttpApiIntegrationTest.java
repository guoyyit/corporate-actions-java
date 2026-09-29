package com.github.guoyyit.corporateactionsjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class HttpApiIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerDataFile(DynamicPropertyRegistry registry) {
        registry.add("corporate-actions.data-file",
                () -> tempDir.resolve("http-api.json").toString());
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private static final Pattern ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void fullLifecycleThroughHttp() throws Exception {
        mockMvc.perform(post("/api/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"XYZ","points":[
                                  {"date":"2024-01-02","close":100},
                                  {"date":"2024-01-03","close":60}
                                ]}
                                """))
                .andExpect(status().isNoContent());

        String registerBody = """
                {"symbol":"XYZ","effectiveDate":"2024-01-03",
                 "params":{"kind":"SPLIT","ratio":2}}
                """;
        String createdBody = mockMvc.perform(post("/api/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("SPLIT"))
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = ID_PATTERN.matcher(createdBody);
        matcher.find();
        String eventId = matcher.group(1);

        // 重复登记 -> 409 DUPLICATE_EVENT
        mockMvc.perform(post("/api/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EVENT"));

        // 同日冲突事件 -> 409 CONFLICTING_EVENT
        mockMvc.perform(post("/api/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"XYZ","effectiveDate":"2024-01-03",
                                 "params":{"kind":"DIVIDEND","perShare":1}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICTING_EVENT"));

        // 非法参数 -> 400 INVALID_PARAM
        mockMvc.perform(post("/api/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"XYZ","effectiveDate":"2024-01-03",
                                 "params":{"kind":"SPLIT","ratio":0.5}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        // 三口径查询：前复权 50 / 原始 100 / 后复权 100（第一天）
        mockMvc.perform(get("/api/prices/XYZ").param("view", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].forward").value(50.0))
                .andExpect(jsonPath("$.points[0].raw").value(100.0))
                .andExpect(jsonPath("$.points[0].backward").value(100.0))
                .andExpect(jsonPath("$.points[1].backward").value(120.0));

        // 修改事件为 1:3 拆股
        mockMvc.perform(put("/api/actions/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"XYZ","effectiveDate":"2024-01-03",
                                 "params":{"kind":"SPLIT","ratio":3}}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/prices/XYZ"))
                .andExpect(jsonPath("$.points[1].backward").value(180.0));

        // 撤销
        mockMvc.perform(delete("/api/actions/" + eventId))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/actions").param("symbol", "XYZ"))
                .andExpect(jsonPath("$[0]").doesNotExist());
        mockMvc.perform(delete("/api/actions/" + eventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
