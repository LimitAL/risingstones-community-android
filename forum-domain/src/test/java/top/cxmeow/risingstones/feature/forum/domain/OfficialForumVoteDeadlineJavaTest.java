package top.cxmeow.risingstones.feature.forum.domain;

import java.time.Instant;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OfficialForumVoteDeadlineJavaTest {
    @Test
    public void existingJavaConstructorCanUseTheOptionalDeadlineHelper() {
        assertEquals(Instant.ofEpochSecond(1700000000L),
            OfficialForumVoteDeadlinesKt.deadline(vote("1700000000")));
        assertEquals(Instant.ofEpochMilli(1700000000123L),
            OfficialForumVoteDeadlinesKt.deadline(vote("1700000000123")));
    }

    @Test
    public void javaCallersReceiveNullForAbsentOrInvalidDates() {
        for (String text : new String[] {null, "", "invalid", "NaN", "Infinity", "1e999"}) {
            assertNull(OfficialForumVoteDeadlinesKt.deadline(vote(text)));
        }
    }

    private static OfficialForumPostVote vote(String endDate) {
        return new OfficialForumPostVote("fixture", "Fixture", 1, null, null, 0,
            endDate, 0, Collections.emptyList());
    }
}
