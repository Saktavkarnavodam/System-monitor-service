package ru.diplom.monitoring.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.diplom.monitoring.repository.AlertRepository;
import ru.diplom.monitoring.repository.AlertRuleRepository;
import ru.diplom.monitoring.repository.MetricRepository;
import ru.diplom.monitoring.repository.NodeRepository;
import ru.diplom.monitoring.repository.UserRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end интеграционный тест: регистрация, вход, регистрация узлов
 * и проверка изоляции данных между пользователями.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthAndMultiTenancyTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository userRepo;
    @Autowired private NodeRepository nodeRepo;
    @Autowired private MetricRepository metricRepo;
    @Autowired private AlertRepository alertRepo;
    @Autowired private AlertRuleRepository ruleRepo;

    @BeforeEach
    void cleanup() {
        alertRepo.deleteAll();
        ruleRepo.deleteAll();
        metricRepo.deleteAll();
        nodeRepo.deleteAll();
        userRepo.deleteAll();
    }

    private String registerAndGetToken(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult res = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(res.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    private String registerNode(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/nodes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"host\":\"localhost\",\"port\":9000}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void register_login_me_workEndToEnd() throws Exception {
        String token = registerAndGetToken("alice", "secret123");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));

        // повторная регистрация — должна провалиться
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"abcdef\"}"))
                .andExpect(status().is4xxClientError());

        // вход
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void unauthorized_requestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/nodes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dashboard/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void multiTenancy_userCannotSeeAnotherUsersNodes() throws Exception {
        String aliceToken = registerAndGetToken("alice", "alicepw1");
        String bobToken = registerAndGetToken("bob", "bobpw1234");

        String aliceNodeId = registerNode(aliceToken, "alice-node");
        String bobNodeId = registerNode(bobToken, "bob-node");

        // alice видит только свой узел
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(aliceNodeId));

        // bob видит только свой
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bobNodeId));

        // alice не может получить узел bob
        mvc.perform(get("/api/v1/nodes/" + bobNodeId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        // alice не может удалить узел bob
        mvc.perform(delete("/api/v1/nodes/" + bobNodeId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void multiTenancy_metricsAreIsolated() throws Exception {
        String aliceToken = registerAndGetToken("alice", "alicepw1");
        String bobToken = registerAndGetToken("bob", "bobpw1234");

        String aliceNode = registerNode(aliceToken, "alice-node");
        registerNode(bobToken, "bob-node");

        // alice отправляет метрику
        mvc.perform(post("/api/v1/metrics/nodes/" + aliceNode)
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cpu.usage\",\"value\":72.5}"))
                .andExpect(status().isOk());

        // alice видит её
        mvc.perform(get("/api/v1/metrics").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // bob — нет
        mvc.perform(get("/api/v1/metrics").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // bob не может отправить метрику от имени alice-node (узел не его)
        mvc.perform(post("/api/v1/metrics/nodes/" + aliceNode)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cpu.usage\",\"value\":99}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void timeseriesEndpoint_works() throws Exception {
        String token = registerAndGetToken("alice", "alicepw1");
        String nodeId = registerNode(token, "alice-node");
        // запушим 3 метрики в одном бакете
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/metrics/nodes/" + nodeId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"cpu.usage\",\"value\":" + (10 + i) + "}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/metrics/timeseries")
                        .param("name", "cpu.usage")
                        .param("nodeId", nodeId)
                        .param("bucketSeconds", "3600")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].count").value(3));
    }
}
