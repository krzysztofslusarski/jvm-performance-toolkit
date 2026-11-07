package pl.ks.jfr.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import pl.ks.jfr.parser.JfrParsedFile;

@Value
@Builder
public class StatefulJfrFile {
    UUID id;
    String customName;
    Instant parseStartDate;
    List<String> filenames;
    List<String> loadFilters;
    String methodName;
    JfrParsedFile.Direction direction;
}
