package gov.irs.directfile.api.dataimport.gating;

import java.time.ZonedDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DataImportGatingConfig {
    private List<Percentage> percentages;
    private List<Windowing> windowing;

    @Getter
    @Setter
    public static class Percentage {
        private String behavior;
        private int percentage;
    }

    @Getter
    @Setter
    public static class Windowing {
        private ZonedDateTime start;
        private ZonedDateTime end;
    }
}
