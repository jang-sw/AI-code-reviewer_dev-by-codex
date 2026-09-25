package com.aicreviewer.operations;

import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OperationsController {
    private final OperationsService operations;

    public OperationsController(OperationsService operations) { this.operations = operations; }

    @GetMapping("/admin/operations")
    @PreAuthorize("hasRole('ADMIN')")
    public String operations(Principal principal, @RequestParam(defaultValue = "ATTENTION") String filter,
                             @RequestParam(defaultValue = "0") int page, Model model) {
        var result = operations.list(principal.getName(), filter, page);
        model.addAttribute("pageTitle", "운영 현황");
        model.addAttribute("operations", result);
        return "admin/operations";
    }
}
