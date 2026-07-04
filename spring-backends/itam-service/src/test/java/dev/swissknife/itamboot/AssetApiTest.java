package dev.swissknife.itamboot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AssetApiTest {
    @Autowired MockMvc mvc;

    @Test void createsAndInventoriesAsset() throws Exception {
        mvc.perform(post("/api/v1/assets").contentType(MediaType.APPLICATION_JSON)
            .content("{\"tag\":\"NB-TEST\",\"name\":\"Notebook\",\"type\":\"COMPUTER\",\"purchaseValue\":5000}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("IN_USE"));
        mvc.perform(get("/api/v1/assets")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].tag").value("NB-TEST"));
        mvc.perform(get("/api/v1/inventory")).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }
}
