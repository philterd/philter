package ai.philterd.philter.ui.controllers;

import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.BinaryFilterResponse;
import ai.philterd.philter.model.ExplainResponse;
import ai.philterd.philter.ui.domain.Policy;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Controller
public class PhilterUiController {

    private static final Logger LOGGER = LogManager.getLogger(PhilterUiController.class);

    @Autowired
    private Environment environment;

    @Autowired
    private Gson gson;

    @Autowired
    private PhilterClient philterClient;

    @RequestMapping(method = RequestMethod.GET, value = "/status")
    public ResponseEntity<String> status() {

        return new ResponseEntity<>("Healthy", HttpStatus.OK);

    }

    @RequestMapping(method = RequestMethod.GET, value = "/")
    public ModelAndView index(Model model) throws IOException {

        ModelAndView modelAndView = new ModelAndView("index");
        modelAndView.addObject("policies", getFilterProfiles());

        return modelAndView;

    }

    @RequestMapping(method = RequestMethod.POST, value = "/newpolicy")
    public ModelAndView newFilterProfile(@RequestParam(name = "json") String json) throws IOException {

        philterClient.savePolicy(json);

        ModelAndView modelAndView = new ModelAndView("index");
        modelAndView.addObject("policies", getFilterProfiles());

        return modelAndView;

    }

    @RequestMapping(method = RequestMethod.GET, value = "/deletepolicy")
    public ModelAndView deleteFilterProfile(@RequestParam(name = "name") String name) throws IOException {

        philterClient.deletePolicy(name);

        ModelAndView modelAndView = new ModelAndView("index");
        modelAndView.addObject("policies", getFilterProfiles());

        return modelAndView;

    }

    @RequestMapping(method = RequestMethod.POST, value = "/filter")
    public ModelAndView filterText(Model model,
                                   @RequestParam(name = "context") String context,
                                   @RequestParam(name = "profile") String profile,
                                   @RequestParam(name = "text") String text) throws IOException {

        ModelAndView modelAndView = new ModelAndView("index");
        modelAndView.addObject("policies", getFilterProfiles());

        LOGGER.info("Using policy: {}", profile);

        final ExplainResponse explainResponse = philterClient.explain(context, "", profile, text);

        modelAndView.addObject("explainResponse", explainResponse);
        modelAndView.addObject("explanation", gson.toJson(explainResponse.getExplanation()));

        return modelAndView;

    }

    @RequestMapping(method = RequestMethod.GET, value = "/pdf")
    public void filterPdf(Model model,
                          @RequestParam(name = "context", defaultValue = "context") String context,
                          @RequestParam(name = "profile", defaultValue = "default") String profile,
                          @RequestParam("file") MultipartFile multipartFile,
                          HttpServletResponse response) throws IOException {

        final File file = File.createTempFile("temp", "pdf");
        multipartFile.transferTo(file);

        LOGGER.info("Using policy: {}", profile);
        LOGGER.info("Uploading file with length: {}", file.length());

        final BinaryFilterResponse binaryFilterResponse = philterClient.filter(context, "", profile, file);

        // TODO: Don't write this to disk. Write it to memory instead.
        final File tempFile = File.createTempFile("philter", ".zip");
        FileUtils.writeByteArrayToFile(tempFile, binaryFilterResponse.getContent());

        final ZipFile zipFile = new ZipFile(tempFile.getAbsolutePath());
        final Enumeration<? extends ZipEntry> entries = zipFile.entries();
        final ZipEntry zipEntry = entries.nextElement();
        final InputStream inputStream = zipFile.getInputStream(zipEntry);

        response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        IOUtils.copy(inputStream, response.getOutputStream());

    }

    private List<Policy> getFilterProfiles() throws IOException {

        final List<Policy> policies = new LinkedList<>();

        final List<String> policyNames = philterClient.getPolicies();

        for(final String policyName : policyNames) {

            // TODO: Parse the policy.
            /*final String policy = philterClient.getFilterProfile(policyName);

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(policy);
            JsonObject jsonObject = element.getAsJsonObject();
            String description = jsonObject.get("description").getAsString();*/

            final Policy policy1 = new Policy();
            policy1.setName(policyName);
         //   profile1.setDescription(description);
            policy1.setFilters(8);
            policy1.setEnabledFilters(5);
            policies.add(policy1);

        }

        return policies;

    }

}