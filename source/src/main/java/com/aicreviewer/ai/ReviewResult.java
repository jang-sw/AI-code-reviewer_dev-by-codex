package com.aicreviewer.ai;

import java.util.List;

public record ReviewResult(String summary, List<ReviewFinding> findings) {
    public ReviewResult { findings = List.copyOf(findings); }
}
