package api.model;

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.philter.api.model.ResponseSpan;
import com.google.gson.Gson;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ResponseSpanTest {

    private static final Logger LOGGER = LogManager.getLogger(ResponseSpanTest.class);

    @Test
    public void equalsContract() {
        EqualsVerifier.forClass(ResponseSpan.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void spansJson() {

        final String[] window = new String[]{};

        final Span span1 = Span.make(1, 6, FilterType.AGE, "context", 1.0, "test", "***", "", false, true, window, 0);
        final Span span2 = Span.make(8, 12, FilterType.AGE, "context", 1.0, "test", "***", "", false, true, window, 0);
        final Span span3 = Span.make(14, 20, FilterType.AGE, "context",  1.0, "test", "***", "", false, true, window, 0);

        final List<Span> spans = Arrays.asList(span1, span2, span3);

        final List<ResponseSpan> responseSpans = ResponseSpan.fromSpans(spans);

        for(int i = 0; i < responseSpans.size(); i++) {

            Assert.assertEquals(spans.get(i).getCharacterStart(), responseSpans.get(i).getCharacterStart());
            Assert.assertEquals(spans.get(i).getCharacterEnd(), responseSpans.get(i).getCharacterEnd());
            Assert.assertEquals(spans.get(i).getConfidence(), responseSpans.get(i).getConfidence(), 0);
            Assert.assertEquals(spans.get(i).getContext(), responseSpans.get(i).getContext());
            Assert.assertEquals(spans.get(i).getReplacement(), responseSpans.get(i).getReplacement());
            Assert.assertEquals(spans.get(i).getFilterType().toString(), responseSpans.get(i).getFilterType());

        }

        final Gson gson = new Gson();
        LOGGER.info(gson.toJson(responseSpans));

    }

}
