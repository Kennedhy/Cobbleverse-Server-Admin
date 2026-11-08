package br.com.cobbleverse.generations.web;

import br.com.cobbleverse.generations.config.GenerationConfig;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebPanelServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void duplicateValidationDoesNotReplaceRunningSessionJob() throws Exception {
        var service = new WebPanelService(null);
        Field jobsField = WebPanelService.class.getDeclaredField("jobs");
        jobsField.setAccessible(true);
        var jobs = (Map<String, WebPanelService.Job>) jobsField.get(service);
        var active = new WebPanelService.Job();
        String session = "admin-session";
        jobs.put(session, active);

        var request = new WebPanelService.ActionRequest("validate", 1, 0, new GenerationConfig(), "");
        var problem = assertThrows(WebPanelService.Problem.class, () -> service.validate(session, request));

        assertEquals(409, problem.status);
        assertSame(active, jobs.get(session));
        assertEquals("running", active.status);
    }
}
