package com.betterf.foundation.controller;

import com.betterf.foundation.internal.http.ApiEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Import;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ResponseContractTests.FixtureController.class)
@Import(ResponseContractTests.FixtureController.class)
@AutoConfigureMockMvc(addFilters = false)
class ResponseContractTests {
    @Autowired MockMvc mvc;

    @Test
    void wrappingPreservesStatusHeadersAndAvoidsDoubleWrapping() throws Exception {
        mvc.perform(get("/fixture/created")).andExpect(status().isCreated())
            .andExpect(header().string("Location", "/fixture/1"))
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.error").isEmpty());
        mvc.perform(get("/fixture/wrapped")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.data.data").doesNotExist());
    }

    @Test
    void noContentAndDownloadsRemainUnwrapped() throws Exception {
        mvc.perform(get("/fixture/empty")).andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/fixture/download")).andExpect(status().isOk())
            .andExpect(content().contentType("application/octet-stream"))
            .andExpect(content().bytes(new byte[] {1, 2, 3}));
    }

    @Test
    void unexpectedExceptionsUseSafeErrorContract() throws Exception {
        mvc.perform(get("/fixture/failure")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."));
    }

    @RestController
    static class FixtureController {
        record Payload(String status) {}
        @GetMapping("/fixture/created")
        ResponseEntity<Payload> created() { return ResponseEntity.created(java.net.URI.create("/fixture/1")).body(new Payload("UP")); }
        @GetMapping("/fixture/wrapped")
        ApiEnvelope<Payload> wrapped() { return ApiEnvelope.success(new Payload("UP")); }
        @GetMapping("/fixture/empty")
        ResponseEntity<Void> empty() { return ResponseEntity.noContent().build(); }
        @GetMapping(value = "/fixture/download", produces = "application/octet-stream")
        byte[] download() { return new byte[] {1, 2, 3}; }
        @GetMapping("/fixture/failure")
        Payload failure() { throw new IllegalStateException("Private diagnostic details"); }
    }
}
