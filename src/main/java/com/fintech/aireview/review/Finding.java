package com.fintech.aireview.review;

/** Deserialised by Gson - every field is nullable on purpose. */
public class Finding {
    private String severity;
    private String title;
    private String category;
    private String rule;
    private String file;
    private Integer line;
    private String explanation;
    private String impact;
    private String original;
    private String suggestion;
    private String language;

    public Severity severityEnum() {
        return Severity.parse(severity);
    }

    public String titleOrDefault() {
        return title != null && !title.isBlank() ? title : "Finding";
    }

    public String location() {
        if (file == null || file.isBlank()) return "";
        return line != null && line > 0 ? file + ":" + line : file;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public Integer getLine() {
        return line;
    }

    public void setLine(Integer line) {
        this.line = line;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getImpact() {
        return impact;
    }

    public void setImpact(String impact) {
        this.impact = impact;
    }

    public String getOriginal() {
        return original;
    }

    public void setOriginal(String original) {
        this.original = original;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
