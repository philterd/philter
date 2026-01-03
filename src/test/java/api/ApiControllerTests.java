package api;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.philter.api.controllers.StatusApiController;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.util.Properties;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Ignore
@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
public class ApiControllerTests {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext ctx;

    @Configuration
    @EnableWebMvc
    static class ContextConfiguration {

//        @Bean
//        public FilterApiController filterApiController() {
//            return new FilterApiController(filterService());
//        }

        @Bean
        public StatusApiController statusApiControllerApiController() throws IOException {
            return new StatusApiController();
        }

        @Bean
        public PlainTextFilterService filterService() {
            return Mockito.mock(PlainTextFilterService.class);
        }

        @Bean
        public Gson gson() {
            return new Gson();
        }

        @Bean
        public PhileasConfiguration phileasConfiguration() {
            return new PhileasConfiguration(new Properties());
        }

    }

    @Before
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @Test
    public void filterTestNoBody() throws Exception {

        mockMvc.perform(post("/api/filter").contentType("text/plain").accept("text/plain")).andExpect(status().isBadRequest());

    }

//    @Test
//    @Ignore("Throwing an NPE")
//    public void filterTest() throws Exception {
//
//        final Policy policy = new Policy();
//        when(filterService.filter(policy, "George Washington was president.", "none")).thenReturn(new TextFilterResult("*** was president.", "none", 0, new Explanation(Collections.emptyList(), Collections.emptyList()), Collections.emptyList(), 0));
//
//        mockMvc.perform(post("/api/filter").content("George Washington was president.").param("c", "none").param("d", "none").contentType("text/plain")).andExpect(status().isOk());
//
//    }

    @Test()
    public void statusTest() throws Exception {

        mockMvc.perform(get("/api/status")).andExpect(status().isServiceUnavailable());

    }

}
