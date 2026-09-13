package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Hit(String id, double score, Product product) {}
