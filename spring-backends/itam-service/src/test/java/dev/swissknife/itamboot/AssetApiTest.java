package dev.swissknife.itamboot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import dev.swissknife.itamboot.domain.AssetRepository;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AssetApiTest {
    @Autowired MockMvc mvc;
    @Autowired AssetRepository repository;

    @Test void createsAndInventoriesAsset() throws Exception {
        mvc.perform(post("/api/v1/assets").contentType(MediaType.APPLICATION_JSON)
            .content("{\"tag\":\"NB-TEST\",\"name\":\"Notebook\",\"type\":\"COMPUTER\",\"purchaseValue\":5000}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("IN_USE"));
        mvc.perform(get("/api/v1/assets")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].tag").value("NB-TEST"));
        mvc.perform(get("/api/v1/inventory")).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
        var id=repository.findAll().getFirst().getId();
        mvc.perform(patch("/api/v1/assets/{id}/status",id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"MAINTENANCE\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("MAINTENANCE"))
            .andExpect(jsonPath("$.assignedTo").doesNotExist());
        mvc.perform(patch("/api/v1/assets/{id}/status",id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"IN_USE\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/assets/{id}/status",id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"IN_USE\",\"assignedTo\":\"maria\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.assignedTo").value("maria"));
        mvc.perform(get("/api/v1/assets").param("status","IN_USE").param("q","notebook"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
    }
}
