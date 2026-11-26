package com.fintech.aireview.review;

import java.util.List;

/** Deserialised by Gson - every field is nullable on purpose. */
public class ReviewResult {
    private String summary;
    private String riskLevel;
    private List<Finding> findings;

    public List<Finding> findingList() {
        return findings != null ? findings : List.of();
    }

    public int countOf(Severity severity) {
        int n = 0;
        for (Finding f : findingList()) {
            if (f.severityEnum() == severity) n++;
        }
        return n;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public void setFindings(List<Finding> findings) {
        this.findings = findings;
    }
}
