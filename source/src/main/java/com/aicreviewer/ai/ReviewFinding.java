package com.aicreviewer.ai;

public record ReviewFinding(String severity, String title, String filePath, Integer lineNumber,
                            String description, String suggestion) { }
