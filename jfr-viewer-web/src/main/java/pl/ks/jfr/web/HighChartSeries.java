package pl.ks.jfr.web;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class HighChartSeries {
    String name;
    Object[] data;
}
